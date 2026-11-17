# Database validation checklist

Run against a fresh database and a copy upgraded from the previous migration.

- [ ] Apply migrations in order; verify each version is recorded once.
- [ ] PRAGMA integrity_check returns ok.
- [ ] PRAGMA foreign_key_check returns zero rows.
- [ ] A failed sale transaction rolls back sale, payment, stock, audit and outbox together.
- [ ] Tracked stock cannot become negative.
- [ ] Inventory, audit and loyalty ledgers reject update/delete.
- [ ] Only one open cash session exists.
- [ ] Only one open attendance record per staff member exists.
- [ ] Duplicate barcode, receipt, coupon and idempotency keys are rejected.
- [ ] Duplicate provider webhook event IDs are rejected.
- [ ] Replayed sync events do not duplicate financial records.
- [ ] Print/delivery failure does not roll back a committed sale.
- [ ] Backup restore while WAL is enabled passes integrity and foreign-key checks.
- [ ] Unauthorized staff cannot void sales, refund, override prices or reconcile cash.
