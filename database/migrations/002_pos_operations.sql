-- Migration 2: operational foundation for purchasing, expenses and cash control.
-- Apply this file once, after database/schema.sql, inside a transaction.
-- The migration runner must insert version=2 into schema_migrations only after success.

CREATE TABLE IF NOT EXISTS suppliers (
    id            TEXT PRIMARY KEY,
    supplier_code TEXT COLLATE NOCASE UNIQUE,
    name          TEXT NOT NULL,
    phone         TEXT,
    email         TEXT,
    address       TEXT,
    tax_id        TEXT,
    note          TEXT,
    is_active     INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS purchases (
    id                  TEXT PRIMARY KEY,
    purchase_number     TEXT NOT NULL COLLATE NOCASE UNIQUE,
    supplier_id         TEXT REFERENCES suppliers(id) ON DELETE RESTRICT,
    status              TEXT NOT NULL DEFAULT 'draft'
                        CHECK (status IN ('draft', 'received', 'voided')),
    subtotal_satang     INTEGER NOT NULL DEFAULT 0 CHECK (subtotal_satang >= 0),
    discount_satang     INTEGER NOT NULL DEFAULT 0 CHECK (discount_satang >= 0),
    total_satang        INTEGER NOT NULL DEFAULT 0 CHECK (total_satang >= 0),
    note                TEXT,
    purchased_at        TEXT NOT NULL,
    received_at         TEXT,
    voided_at           TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    CHECK (discount_satang <= subtotal_satang)
);

CREATE TABLE IF NOT EXISTS purchase_items (
    id                    TEXT PRIMARY KEY,
    purchase_id           TEXT NOT NULL REFERENCES purchases(id) ON DELETE RESTRICT,
    product_id            TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    product_name_snapshot TEXT NOT NULL,
    quantity              INTEGER NOT NULL CHECK (quantity > 0),
    unit_cost_satang      INTEGER NOT NULL CHECK (unit_cost_satang >= 0),
    discount_satang       INTEGER NOT NULL DEFAULT 0 CHECK (discount_satang >= 0),
    line_total_satang     INTEGER NOT NULL CHECK (line_total_satang >= 0),
    created_at            TEXT NOT NULL,
    CHECK (discount_satang <= quantity * unit_cost_satang)
);

CREATE TABLE IF NOT EXISTS expenses (
    id                TEXT PRIMARY KEY,
    expense_number    TEXT NOT NULL COLLATE NOCASE UNIQUE,
    category          TEXT NOT NULL,
    description       TEXT NOT NULL,
    amount_satang     INTEGER NOT NULL CHECK (amount_satang > 0),
    payment_method    TEXT NOT NULL
                      CHECK (payment_method IN ('cash', 'transfer', 'card', 'other')),
    reference_number  TEXT,
    note              TEXT,
    incurred_at       TEXT NOT NULL,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cash_sessions (
    id                    TEXT PRIMARY KEY,
    session_number        TEXT NOT NULL COLLATE NOCASE UNIQUE,
    opened_by             TEXT,
    closed_by             TEXT,
    opening_cash_satang   INTEGER NOT NULL CHECK (opening_cash_satang >= 0),
    expected_cash_satang  INTEGER CHECK (expected_cash_satang >= 0),
    counted_cash_satang   INTEGER CHECK (counted_cash_satang >= 0),
    difference_satang     INTEGER,
    status                TEXT NOT NULL DEFAULT 'open'
                          CHECK (status IN ('open', 'closed')),
    opened_at             TEXT NOT NULL,
    closed_at             TEXT,
    note                  TEXT,
    CHECK (
      (status = 'open' AND closed_at IS NULL AND counted_cash_satang IS NULL)
      OR
      (status = 'closed' AND closed_at IS NOT NULL AND counted_cash_satang IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_cash_sessions_single_open
    ON cash_sessions(status) WHERE status = 'open';

CREATE TABLE IF NOT EXISTS cash_movements (
    id                TEXT PRIMARY KEY,
    session_id        TEXT NOT NULL REFERENCES cash_sessions(id) ON DELETE RESTRICT,
    direction         TEXT NOT NULL CHECK (direction IN ('in', 'out')),
    movement_type     TEXT NOT NULL
                      CHECK (movement_type IN ('opening', 'sale', 'refund', 'expense', 'cash_in', 'cash_out', 'closing_adjustment')),
    amount_satang     INTEGER NOT NULL CHECK (amount_satang > 0),
    reference_type    TEXT,
    reference_id      TEXT,
    description       TEXT,
    occurred_at       TEXT NOT NULL,
    created_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sale_returns (
    id                TEXT PRIMARY KEY,
    return_number     TEXT NOT NULL COLLATE NOCASE UNIQUE,
    sale_id           TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
    status            TEXT NOT NULL DEFAULT 'confirmed'
                      CHECK (status IN ('confirmed', 'voided')),
    refund_satang     INTEGER NOT NULL CHECK (refund_satang >= 0),
    reason            TEXT,
    returned_at       TEXT NOT NULL,
    created_at        TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sale_return_items (
    id                    TEXT PRIMARY KEY,
    sale_return_id        TEXT NOT NULL REFERENCES sale_returns(id) ON DELETE RESTRICT,
    sale_item_id          TEXT NOT NULL REFERENCES sale_items(id) ON DELETE RESTRICT,
    product_id            TEXT REFERENCES products(id) ON DELETE RESTRICT,
    product_name_snapshot TEXT NOT NULL,
    quantity              INTEGER NOT NULL CHECK (quantity > 0),
    refund_satang         INTEGER NOT NULL CHECK (refund_satang >= 0),
    created_at            TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_suppliers_name
    ON suppliers(name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_purchases_date
    ON purchases(purchased_at DESC);
CREATE INDEX IF NOT EXISTS idx_purchases_supplier
    ON purchases(supplier_id, purchased_at DESC);
CREATE INDEX IF NOT EXISTS idx_purchase_items_purchase
    ON purchase_items(purchase_id);
CREATE INDEX IF NOT EXISTS idx_expenses_date
    ON expenses(incurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_cash_movements_session
    ON cash_movements(session_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_cash_movements_reference
    ON cash_movements(reference_type, reference_id);
CREATE INDEX IF NOT EXISTS idx_sale_returns_sale
    ON sale_returns(sale_id, returned_at DESC);
CREATE INDEX IF NOT EXISTS idx_sale_return_items_return
    ON sale_return_items(sale_return_id);

-- Inventory is a ledger: append corrections; never edit or delete old entries.
-- The trigger maintains the cached products.stock_quantity atomically.
CREATE TRIGGER IF NOT EXISTS trg_inventory_movement_validate
BEFORE INSERT ON inventory_movements
WHEN (SELECT track_stock FROM products WHERE id = NEW.product_id) = 1
 AND (SELECT stock_quantity + NEW.quantity_delta FROM products WHERE id = NEW.product_id) < 0
BEGIN
    SELECT RAISE(ABORT, 'INSUFFICIENT_STOCK');
END;

CREATE TRIGGER IF NOT EXISTS trg_inventory_movement_apply
AFTER INSERT ON inventory_movements
WHEN (SELECT track_stock FROM products WHERE id = NEW.product_id) = 1
BEGIN
    UPDATE products
       SET stock_quantity = stock_quantity + NEW.quantity_delta,
           updated_at = NEW.created_at
     WHERE id = NEW.product_id;
END;

CREATE TRIGGER IF NOT EXISTS trg_inventory_movement_no_update
BEFORE UPDATE ON inventory_movements
BEGIN
    SELECT RAISE(ABORT, 'INVENTORY_LEDGER_IMMUTABLE');
END;

CREATE TRIGGER IF NOT EXISTS trg_inventory_movement_no_delete
BEFORE DELETE ON inventory_movements
BEGIN
    SELECT RAISE(ABORT, 'INVENTORY_LEDGER_IMMUTABLE');
END;

-- A confirmed purchase is immutable; corrections must use a void/reversal workflow.
CREATE TRIGGER IF NOT EXISTS trg_received_purchase_no_update
BEFORE UPDATE ON purchases
WHEN OLD.status = 'received'
BEGIN
    SELECT RAISE(ABORT, 'RECEIVED_PURCHASE_REQUIRES_REVERSAL');
END;

CREATE TRIGGER IF NOT EXISTS trg_confirmed_sale_no_delete
BEFORE DELETE ON sales
WHEN OLD.status = 'confirmed'
BEGIN
    SELECT RAISE(ABORT, 'CONFIRMED_SALE_CANNOT_BE_DELETED');
END;
