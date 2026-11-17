# Mae Yai On POS — Central Database

## Purpose
One SQLite database is the single source of truth for every POS screen and feature. Screens must call shared repositories/services; they must not maintain independent copies of business data.

## Files
- `schema.sql`: baseline schema (version 1).
- `migrations/002_pos_operations.sql`: purchasing, expenses, cash sessions, cash movements, returns, and inventory-ledger safeguards.
- `migrations/003_platform_foundation.sql`: staff/RBAC, attendance, barcodes, variants, promotions, loyalty, payment gateway metadata, devices, printing, receipt delivery and offline sync.
- `seeds/001_default_roles.sql`: idempotent owner/manager/cashier roles and permission catalogue.
- Other seed data must be stable reference data only; never put real customer, sales, or financial data here.

## Core domains
- Catalog: `product_categories`, `products`
- Customers and suppliers: `customers`, `suppliers`
- Sales: `sales`, `sale_items`, `payments`
- Purchasing: `purchases`, `purchase_items`, `purchase_reversals`, `purchase_reversal_items`
- Inventory: `inventory_movements` (append-only ledger), cached balance in `products.stock_quantity`
- Cash control: `cash_sessions`, `cash_movements`
- Expenses: `expenses`
- Returns: `sale_returns`, `sale_return_items`
- Workforce/security: `staff`, `roles`, `permissions`, `staff_roles`, `role_permissions`, `auth_sessions`, `attendance_records`
- Product extensions: `product_barcodes`, `product_images`, `product_variant_groups`, `product_variant_options`
- Promotions/loyalty: `promotions`, `coupons`, `sale_discounts`, `loyalty_accounts`, `loyalty_movements`
- Integrations: `payment_gateway_transactions`, `payment_webhook_events`, `receipt_delivery_jobs`, `devices`, `printer_configs`, `print_jobs`
- Offline sync: `sync_outbox`, `sync_inbox`, `sync_state`, `api_idempotency`
- System: `app_settings`, `schema_migrations`, `audit_logs`

## Mandatory connection settings
Every opened SQLite connection must enable and verify:
- `PRAGMA foreign_keys = ON;`
- `PRAGMA busy_timeout = 5000;`
- `PRAGMA journal_mode = WAL;` (set during database initialization; confirm the returned mode)
- `PRAGMA synchronous = FULL;` for durability-sensitive POS data.

Do not assume connection-local PRAGMAs persist across connections. Enable foreign keys and busy timeout each time. WAL and synchronous policy must be initialized and verified by the database provider.

## Money, IDs, and time
- Store money as integer satang. Never use floating point for financial calculations.
- Split tenders use multiple `payments` rows. PromptPay QR is represented by `payment_method='transfer'` and `payment_channel='promptpay_qr'`, with provider state/reference in `payment_gateway_transactions`.
- Use application-generated UUIDs for primary keys.
- Store timestamps in ISO-8601 UTC. Convert to local time only for display.
- Snapshot product names and prices in transaction lines so later catalog edits do not rewrite historical receipts.

## Transaction boundaries
A confirmed sale must be committed as one database transaction:
1. Validate product status, quantities, prices, discounts, and stock from the database.
2. Recompute line totals and document totals in the service layer using integer arithmetic.
3. Insert/update the sale and its lines.
4. Insert payment records.
5. Insert inventory ledger movements; the migration trigger updates cached stock.
6. Insert audit log records.
7. Commit only if every step succeeds; otherwise roll back everything.

Receiving a purchase follows the same atomic pattern: validate draft, insert purchase lines, mark received, write positive inventory movements, audit, commit. A received purchase is immutable. Correct it through `purchase_reversals` and `purchase_reversal_items`, with compensating negative inventory movements in the same transaction. A sale return/refund must be validated against the original sold quantities and prior returns before writing return and stock/cash movements.

## Data integrity rules
- Never delete confirmed sales, received purchases, payments, or inventory ledger entries.
- Correct mistakes with explicit void/reversal records and compensating ledger movements.
- Do not update stock directly in `products.stock_quantity`; use `inventory_movements`. The trigger maintains the cached quantity.
- Do not trust totals, product names, prices, or customer identifiers sent by a UI. Re-read and validate them in the shared service layer.
- Use parameterized SQL for every value. Never concatenate user input into SQL.
- Use foreign keys and keep `ON DELETE RESTRICT` for financial and historical relationships.
- Do not silently swallow database errors. Roll back and surface a safe, actionable error to the caller.

## Migration protocol
- Run migrations in ascending version order under a single exclusive write transaction.
- Check `schema_migrations` before applying a migration.
- Insert the migration version/name/applied timestamp only after all migration statements succeed.
- If any statement fails, roll back the entire migration.
- Never edit a migration already released to users; add a new numbered migration.
- Baseline install: execute `schema.sql`, then record version 1 in `schema_migrations`, then apply migrations 2 and 3 in order. Apply `seeds/001_default_roles.sql` only after migration 3.
- Test migrations against both a fresh database and a copy of the previous released database.

## Important scope boundary
This repository currently contains a database foundation, not an app-specific database driver. The driver/repository implementation must be written for the chosen app framework so every screen shares the same database instance and transaction service.
