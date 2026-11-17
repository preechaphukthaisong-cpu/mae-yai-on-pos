# POS system overview

## Three logical layers

    FRONTEND: cashier and owner screens
      Sales | Cart | Checkout | Receipt | Cash Drawer
      Catalog | Inventory | CRM | Staff | Promotions | Reports
                         |
                         v
    BACKEND: business rules, API and integrations
      Auth/RBAC | Sales | Payments | Inventory | Purchasing
      Cash | CRM/Loyalty | Promotions | Workforce | Reports
      Sync Engine | Print Queue | Receipt Delivery
                         |
                         v
    DATA: local-first SQLite and optional cloud database
      Master Data | Sales/Payment/Stock Ledgers | Staff/Roles
      Loyalty | Promotions | Audit | Sync Inbox/Outbox | Devices

## Sales flow
1. Staff signs in; roles and permissions are checked.
2. Scan/search products and load current catalog and prices.
3. Add products/options to cart; calculate provisional totals.
4. Domain service validates product state, prices, discounts, promotions and stock.
5. Confirm sale with a stable idempotency key.
6. In one local transaction write sale, item snapshots, payment tenders, stock movements, audit event and sync outbox event.
7. Commit locally before showing the sale as saved. If offline, mark it pending sync.
8. For electronic payments, verify provider status. A displayed QR is not proof of payment.
9. Queue receipt print/delivery. Print failure must not undo a committed sale.
10. Reports query committed records; no duplicate report database is maintained.

## Offline-first sync
- Local SQLite stores committed transactions while offline.
- Every syncable write adds an outbox event in the same transaction.
- Send events with stable idempotency keys; retry with bounded backoff.
- Server deduplicates events, revalidates business rules and returns durable acknowledgements/cursors.
- Store inbound events in an inbox before applying them.
- Never silently overwrite financial records or stock. Resolve conflicts with explicit reversal/adjustment events and audit history.
- Catalog changes may use server revisions; inventory and money must use ledgers, not blind last-write-wins.

## Integrations
- Scanner: camera or keyboard-wedge input.
- Payment gateway: provider adapters for PromptPay QR, card and supported transfers; verify signatures/status.
- Printing: receipt, label and optional kitchen print adapters.
- Cash drawer: issue supported drawer command only after an authorized cash workflow.
- Email/LINE: asynchronous receipt-delivery jobs.
- Cloud: server persistence and multi-device sync; local SQLite remains usable offline.

## Non-functional requirements
Atomic writes; integer satang; UUIDs and idempotency; UTC timestamps; least-privilege authorization; app-private database storage; TLS; consistent backups; restore verification; migration tests; safe observability.
