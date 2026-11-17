-- Migration 3: retail POS platform foundation.
-- Apply once after migrations 001 and 002, in one transaction.
-- Store secret hashes only. Never store raw passwords, PINs, access tokens,
-- payment credentials, CVV, or card numbers in this database.

CREATE TABLE IF NOT EXISTS staff (
 id TEXT PRIMARY KEY, staff_code TEXT NOT NULL COLLATE NOCASE UNIQUE,
 display_name TEXT NOT NULL, phone TEXT, email TEXT, password_hash TEXT, pin_hash TEXT,
 is_owner INTEGER NOT NULL DEFAULT 0 CHECK(is_owner IN (0,1)),
 is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
 hired_at TEXT, terminated_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 CHECK(password_hash IS NOT NULL OR pin_hash IS NOT NULL)
);
CREATE TABLE IF NOT EXISTS roles (
 id TEXT PRIMARY KEY, role_key TEXT NOT NULL COLLATE NOCASE UNIQUE,
 role_name TEXT NOT NULL, description TEXT,
 is_system INTEGER NOT NULL DEFAULT 0 CHECK(is_system IN (0,1)),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS permissions (
 id TEXT PRIMARY KEY, permission_key TEXT NOT NULL COLLATE NOCASE UNIQUE,
 description TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS staff_roles (
 staff_id TEXT NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
 role_id TEXT NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
 assigned_at TEXT NOT NULL, PRIMARY KEY(staff_id,role_id)
);
CREATE TABLE IF NOT EXISTS role_permissions (
 role_id TEXT NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
 permission_id TEXT NOT NULL REFERENCES permissions(id) ON DELETE RESTRICT,
 PRIMARY KEY(role_id,permission_id)
);
CREATE TABLE IF NOT EXISTS auth_sessions (
 id TEXT PRIMARY KEY, staff_id TEXT NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
 refresh_token_hash TEXT NOT NULL UNIQUE, device_id TEXT, expires_at TEXT NOT NULL,
 revoked_at TEXT, created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_login_attempts (
 id TEXT PRIMARY KEY, staff_code TEXT NOT NULL, device_id TEXT,
 succeeded INTEGER NOT NULL CHECK(succeeded IN (0,1)), attempted_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS attendance_records (
 id TEXT PRIMARY KEY, staff_id TEXT NOT NULL REFERENCES staff(id) ON DELETE RESTRICT,
 clock_in_at TEXT NOT NULL, clock_out_at TEXT, clock_in_device_id TEXT,
 clock_out_device_id TEXT, note TEXT, created_at TEXT NOT NULL,
 CHECK(clock_out_at IS NULL OR clock_out_at >= clock_in_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_attendance_one_open_per_staff
 ON attendance_records(staff_id) WHERE clock_out_at IS NULL;

CREATE TABLE IF NOT EXISTS product_barcodes (
 id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
 barcode TEXT NOT NULL COLLATE NOCASE UNIQUE,
 barcode_type TEXT NOT NULL DEFAULT 'CODE128'
 CHECK(barcode_type IN ('EAN13','EAN8','UPC_A','CODE128','QR')),
 is_primary INTEGER NOT NULL DEFAULT 0 CHECK(is_primary IN (0,1)), created_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_product_one_primary_barcode
 ON product_barcodes(product_id) WHERE is_primary=1;
CREATE TABLE IF NOT EXISTS product_images (
 id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
 storage_uri TEXT NOT NULL, alt_text TEXT, sort_order INTEGER NOT NULL DEFAULT 0,
 created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS product_variant_groups (
 id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
 name TEXT NOT NULL, is_required INTEGER NOT NULL DEFAULT 0 CHECK(is_required IN (0,1)),
 min_select INTEGER NOT NULL DEFAULT 0 CHECK(min_select>=0),
 max_select INTEGER NOT NULL DEFAULT 1 CHECK(max_select>=min_select),
 sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS product_variant_options (
 id TEXT PRIMARY KEY, group_id TEXT NOT NULL REFERENCES product_variant_groups(id) ON DELETE RESTRICT,
 option_name TEXT NOT NULL, price_delta_satang INTEGER NOT NULL DEFAULT 0,
 is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)), sort_order INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS sale_item_options (
 id TEXT PRIMARY KEY, sale_item_id TEXT NOT NULL REFERENCES sale_items(id) ON DELETE RESTRICT,
 variant_option_id TEXT REFERENCES product_variant_options(id) ON DELETE RESTRICT,
 option_name_snapshot TEXT NOT NULL, price_delta_satang INTEGER NOT NULL,
 quantity INTEGER NOT NULL DEFAULT 1 CHECK(quantity>0)
);

CREATE TABLE IF NOT EXISTS promotions (
 id TEXT PRIMARY KEY, promotion_code TEXT COLLATE NOCASE UNIQUE, name TEXT NOT NULL,
 promotion_type TEXT NOT NULL CHECK(promotion_type IN ('percentage','fixed_amount','buy_x_get_y','price_override')),
 discount_value INTEGER NOT NULL CHECK(discount_value>=0), starts_at TEXT, ends_at TEXT,
 usage_limit INTEGER CHECK(usage_limit IS NULL OR usage_limit>=0),
 usage_count INTEGER NOT NULL DEFAULT 0 CHECK(usage_count>=0),
 is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 CHECK(ends_at IS NULL OR starts_at IS NULL OR ends_at>starts_at)
);
CREATE TABLE IF NOT EXISTS promotion_products (
 promotion_id TEXT NOT NULL REFERENCES promotions(id) ON DELETE RESTRICT,
 product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
 PRIMARY KEY(promotion_id,product_id)
);
CREATE TABLE IF NOT EXISTS coupons (
 id TEXT PRIMARY KEY, promotion_id TEXT NOT NULL REFERENCES promotions(id) ON DELETE RESTRICT,
 coupon_code TEXT NOT NULL COLLATE NOCASE UNIQUE,
 max_uses INTEGER CHECK(max_uses IS NULL OR max_uses>=0),
 uses_count INTEGER NOT NULL DEFAULT 0 CHECK(uses_count>=0),
 expires_at TEXT, is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
 created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sale_discounts (
 id TEXT PRIMARY KEY, sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
 promotion_id TEXT REFERENCES promotions(id) ON DELETE RESTRICT,
 coupon_id TEXT REFERENCES coupons(id) ON DELETE RESTRICT,
 description TEXT NOT NULL, discount_satang INTEGER NOT NULL CHECK(discount_satang>=0),
 created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS loyalty_accounts (
 id TEXT PRIMARY KEY, customer_id TEXT NOT NULL UNIQUE REFERENCES customers(id) ON DELETE RESTRICT,
 points_balance INTEGER NOT NULL DEFAULT 0 CHECK(points_balance>=0),
 tier TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS loyalty_movements (
 id TEXT PRIMARY KEY,
 loyalty_account_id TEXT NOT NULL REFERENCES loyalty_accounts(id) ON DELETE RESTRICT,
 sale_id TEXT REFERENCES sales(id) ON DELETE RESTRICT,
 movement_type TEXT NOT NULL CHECK(movement_type IN ('earn','redeem','adjust','expire','reverse')),
 points_delta INTEGER NOT NULL CHECK(points_delta<>0), note TEXT, created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS payment_gateway_transactions (
 id TEXT PRIMARY KEY, payment_id TEXT REFERENCES payments(id) ON DELETE RESTRICT,
 sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
 provider TEXT NOT NULL,
 channel TEXT NOT NULL CHECK(channel IN ('promptpay_qr','card','bank_transfer','other')),
 provider_reference TEXT, idempotency_key TEXT NOT NULL UNIQUE,
 amount_satang INTEGER NOT NULL CHECK(amount_satang>0),
 status TEXT NOT NULL CHECK(status IN ('pending','succeeded','failed','expired','refunded')),
 request_json TEXT, response_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 UNIQUE(provider,provider_reference)
);
CREATE TABLE IF NOT EXISTS payment_webhook_events (
 id TEXT PRIMARY KEY, provider TEXT NOT NULL, provider_event_id TEXT NOT NULL,
 event_type TEXT NOT NULL, payload_json TEXT NOT NULL, received_at TEXT NOT NULL,
 processed_at TEXT,
 processing_status TEXT NOT NULL DEFAULT 'pending'
 CHECK(processing_status IN ('pending','processed','failed','ignored')),
 UNIQUE(provider,provider_event_id)
);

CREATE TABLE IF NOT EXISTS receipt_delivery_jobs (
 id TEXT PRIMARY KEY, sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,
 channel TEXT NOT NULL CHECK(channel IN ('email','line','other')),
 destination TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending'
 CHECK(status IN ('pending','sent','failed')),
 provider_reference TEXT, error_code TEXT, created_at TEXT NOT NULL, sent_at TEXT
);
CREATE TABLE IF NOT EXISTS devices (
 id TEXT PRIMARY KEY, device_code TEXT NOT NULL COLLATE NOCASE UNIQUE,
 device_name TEXT NOT NULL,
 device_type TEXT NOT NULL CHECK(device_type IN ('pos_terminal','printer','barcode_scanner','cash_drawer','kds','other')),
 platform TEXT, last_seen_at TEXT,
 is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS printer_configs (
 id TEXT PRIMARY KEY, device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
 printer_role TEXT NOT NULL CHECK(printer_role IN ('receipt','kitchen','label')),
 connection_type TEXT NOT NULL CHECK(connection_type IN ('bluetooth','usb','network','system')),
 connection_config_json TEXT NOT NULL, paper_width_mm INTEGER,
 is_default INTEGER NOT NULL DEFAULT 0 CHECK(is_default IN (0,1)),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_printer_default_role
 ON printer_configs(printer_role) WHERE is_default=1;
CREATE TABLE IF NOT EXISTS print_jobs (
 id TEXT PRIMARY KEY, device_id TEXT REFERENCES devices(id) ON DELETE RESTRICT,
 sale_id TEXT REFERENCES sales(id) ON DELETE RESTRICT,
 job_type TEXT NOT NULL CHECK(job_type IN ('receipt','kitchen_order','barcode_label','report')),
 payload_json TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'queued'
 CHECK(status IN ('queued','printing','printed','failed','cancelled')),
 attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts>=0),
 last_error TEXT, created_at TEXT NOT NULL, completed_at TEXT
);

CREATE TABLE IF NOT EXISTS sync_outbox (
 id TEXT PRIMARY KEY, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL,
 operation TEXT NOT NULL CHECK(operation IN ('upsert','delete','event')),
 payload_json TEXT NOT NULL, idempotency_key TEXT NOT NULL UNIQUE,
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','processing','synced','failed')),
 attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 next_attempt_at TEXT, last_error TEXT, created_at TEXT NOT NULL, synced_at TEXT
);
CREATE TABLE IF NOT EXISTS sync_inbox (
 id TEXT PRIMARY KEY, remote_event_id TEXT NOT NULL UNIQUE,
 entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, payload_json TEXT NOT NULL,
 received_at TEXT NOT NULL, applied_at TEXT,
 status TEXT NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','applied','conflict','failed'))
);
CREATE TABLE IF NOT EXISTS sync_state (
 scope_key TEXT PRIMARY KEY, cursor_value TEXT, last_sync_at TEXT, updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS api_idempotency (
 idempotency_key TEXT PRIMARY KEY, operation_name TEXT NOT NULL, request_hash TEXT NOT NULL,
 response_code INTEGER, response_json TEXT, created_at TEXT NOT NULL, expires_at TEXT NOT NULL
);


-- Link business events to the responsible staff member without rewriting history.
ALTER TABLE sales ADD COLUMN staff_id TEXT REFERENCES staff(id) ON DELETE RESTRICT;
ALTER TABLE sales ADD COLUMN idempotency_key TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_sales_idempotency_key
    ON sales(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sales_staff_time
    ON sales(staff_id, created_at DESC);

ALTER TABLE payments ADD COLUMN payment_channel TEXT NOT NULL DEFAULT 'standard'
    CHECK(payment_channel IN ('standard','promptpay_qr','bank_transfer','card_terminal'));
ALTER TABLE payments ADD COLUMN payment_provider TEXT;

ALTER TABLE cash_sessions ADD COLUMN opened_by_staff_id TEXT
    REFERENCES staff(id) ON DELETE RESTRICT;
ALTER TABLE cash_sessions ADD COLUMN closed_by_staff_id TEXT
    REFERENCES staff(id) ON DELETE RESTRICT;

ALTER TABLE products ADD COLUMN low_stock_threshold INTEGER NOT NULL DEFAULT 0
    CHECK(low_stock_threshold >= 0);

CREATE INDEX IF NOT EXISTS idx_staff_active ON staff(is_active,display_name);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_staff_expiry ON auth_sessions(staff_id,expires_at);
CREATE INDEX IF NOT EXISTS idx_auth_attempts_staff_time ON auth_login_attempts(staff_code,attempted_at DESC);
CREATE INDEX IF NOT EXISTS idx_product_barcodes_product ON product_barcodes(product_id);
CREATE INDEX IF NOT EXISTS idx_variant_groups_product ON product_variant_groups(product_id,sort_order);
CREATE INDEX IF NOT EXISTS idx_variant_options_group ON product_variant_options(group_id,sort_order);
CREATE INDEX IF NOT EXISTS idx_loyalty_movements_account ON loyalty_movements(loyalty_account_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gateway_sale ON payment_gateway_transactions(sale_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gateway_status ON payment_gateway_transactions(status,created_at);
CREATE INDEX IF NOT EXISTS idx_webhook_pending ON payment_webhook_events(processing_status,received_at);
CREATE INDEX IF NOT EXISTS idx_receipt_delivery_pending ON receipt_delivery_jobs(status,created_at);
CREATE INDEX IF NOT EXISTS idx_print_jobs_queue ON print_jobs(status,created_at);
CREATE INDEX IF NOT EXISTS idx_sync_outbox_queue ON sync_outbox(status,next_attempt_at,created_at);
CREATE INDEX IF NOT EXISTS idx_sync_inbox_pending ON sync_inbox(status,received_at);
CREATE INDEX IF NOT EXISTS idx_api_idempotency_expiry ON api_idempotency(expires_at);

CREATE TRIGGER IF NOT EXISTS trg_audit_logs_no_update
BEFORE UPDATE ON audit_logs BEGIN SELECT RAISE(ABORT,'AUDIT_LOG_IMMUTABLE'); END;
CREATE TRIGGER IF NOT EXISTS trg_audit_logs_no_delete
BEFORE DELETE ON audit_logs BEGIN SELECT RAISE(ABORT,'AUDIT_LOG_IMMUTABLE'); END;
CREATE TRIGGER IF NOT EXISTS trg_loyalty_movement_validate
BEFORE INSERT ON loyalty_movements
WHEN (SELECT points_balance+NEW.points_delta FROM loyalty_accounts WHERE id=NEW.loyalty_account_id)<0
BEGIN SELECT RAISE(ABORT,'INSUFFICIENT_LOYALTY_POINTS'); END;
CREATE TRIGGER IF NOT EXISTS trg_loyalty_movement_apply
AFTER INSERT ON loyalty_movements
BEGIN
 UPDATE loyalty_accounts SET points_balance=points_balance+NEW.points_delta, updated_at=NEW.created_at
 WHERE id=NEW.loyalty_account_id;
END;
CREATE TRIGGER IF NOT EXISTS trg_loyalty_movement_no_update
BEFORE UPDATE ON loyalty_movements BEGIN SELECT RAISE(ABORT,'LOYALTY_LEDGER_IMMUTABLE'); END;
CREATE TRIGGER IF NOT EXISTS trg_loyalty_movement_no_delete
BEFORE DELETE ON loyalty_movements BEGIN SELECT RAISE(ABORT,'LOYALTY_LEDGER_IMMUTABLE'); END;
