# Central database architecture

## Single source of truth

All POS features must use one database provider and one shared transaction boundary:

```text
Screens / UI
    |
    v
Feature services (sales, catalog, inventory, purchasing, cash, reports)
    |
    v
Shared repositories + transaction manager
    |
    v
Single SQLite database
    |
    +-- schema migrations
    +-- audit log
    +-- append-only inventory and cash ledgers
```

UI code must not execute ad-hoc SQL or calculate authoritative totals. Reports read committed records from this same database; they do not maintain separate copies of sales or inventory.

## Required service boundaries
- Catalog service: products and categories.
- Customer/supplier service: master records.
- Sales service: sale, sale items, payments, stock movements, audit.
- Purchasing service: purchase receipt and stock movements.
- Inventory service: ledger queries and controlled adjustments.
- Cash service: cash sessions and cash movements.
- Expense service: expense records and cash movements.
- Reporting service: read-only queries over committed business data.
- Workforce/auth service: staff, sessions, roles, permissions and attendance.
- Promotion/loyalty service: coupons, promotion usage and points ledger.
- Payment integration service: gateway attempts, verified webhooks and split tenders.
- Device/printing service: printers, print queue, barcode labels and receipt delivery.
- Sync service: durable outbox/inbox, cursors, idempotency and conflict handling.
- Database service: connection lifecycle, migrations, transactions, and backup/restore.

## Concurrency and reliability
Use short write transactions. Never hold a transaction open while waiting for user input or network activity. On SQLITE_BUSY, retry only bounded, idempotent operations; do not blindly repeat a transaction that may already have committed. Use a stable operation/document ID to prevent duplicate submissions.

## Backup and recovery
The application layer must provide a consistent SQLite backup using the platform's SQLite backup API or a supported online-backup mechanism. Do not copy only the main database file while WAL is active. Verify backups by restoring to a temporary database and running integrity and foreign-key checks.

Required verification commands:
- `PRAGMA integrity_check;` must return `ok`.
- `PRAGMA foreign_key_check;` must return no rows.

## Security
Keep the database in application-private storage. Do not log passwords, payment secrets, or unnecessary personal data. Bind SQL parameters. Validate all business rules in services, not only in the UI.
