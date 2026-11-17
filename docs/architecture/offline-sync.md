# Offline sync contract

Every syncable local write and its outbox event must commit in the same SQLite transaction.

Outbox states: pending -> processing -> synced. Transient errors return to pending with a retry time. Permanent validation failures remain visible as failed for operator/admin resolution. Keep event IDs and idempotency keys unchanged across retries.

Server processing: authenticate device/staff; validate event version and payload size; deduplicate by idempotency key; revalidate business rules; apply atomically; persist acknowledgement; return cursor.

Conflict rules:
- Sales, payments, refunds and stock movements are immutable events; never last-write-wins.
- Concurrent stock adjustments are separate ledger entries followed by reconciliation.
- Catalog data may use server revision numbers and an explicit publishing policy.
- Conflicts are visible and never silently discarded.

Security: TLS; revocable per-device identity; secure token storage; no payment secrets or raw card data in sync payloads.
