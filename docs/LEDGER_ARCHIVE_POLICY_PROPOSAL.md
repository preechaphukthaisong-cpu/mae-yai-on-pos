# Append-Only Ledger Archive and Reconciliation Policy — Approval Draft

**Status: PENDING OWNER APPROVAL — PURGE PROHIBITED**  
**Scope:** G6 and the existing retention decisions D-022, D-023, D-042, D-043, D-054.

## 1. Retention rules (existing decisions; not changed by this proposal)

- Paid cash bills: eligible no earlier than 30 days after `created_at_utc`.
- Paid credit bills: eligible no earlier than 30 days after `paid_at_utc`.
- Credit bills settled to zero only by return/cancellation reversal: eligible no earlier than 30 days after `settled_by_reversal_at_utc`; this must not be represented as a debt payment.
- Any bill with a real outstanding balance greater than zero is never purge-eligible.
- `sale_return`, `sale_cancel`, `debt_payment`, `stock_log`, `price_history`, and `op_log`: retain at least 90 days from each row's creation timestamp.
- `daily_summary`, `product_daily`, `sale_reconciliation`, and reconciliation proof: retain 15 months.
- `product` and `barcode_registry`: retain permanently.
- A parent row remains ineligible while a retained child row still references it. Do not weaken foreign keys or use cascade deletion to bypass retention.

## 2. Required pre-purge evidence

For each business date and purge batch:

1. Create a consistent, encrypted, integrity-checked database snapshot and record its SHA-256 digest, schema version, byte length, and creation time. Do not treat an unencrypted temporary snapshot as an archive.
2. Build immutable reconciliation proof containing only the D-054 fields: hash reference, business date, sale type, subtotal, fee charged, returned goods/fee, cancelled goods/fee, and net sales.
3. Exclude customer name, phone, customer ID, and all other personal data from the proof.
4. Recompute totals from source ledger rows and independently compare them with daily and product summaries. Any mismatch blocks the entire batch.
5. Check outstanding credit balances, retention timestamps, foreign-key dependencies, child-row retention, and all SQL-level guards.
6. Commit proof before any deletion. A failure at any stage means no rows are purged.

## 3. Execution and recovery

- Purge only rows that satisfy all eligibility checks at the instant of the purge transaction.
- The eligibility check, proof reference, and deletion set must be bound to one transaction so a concurrent write cannot invalidate the proof.
- Preserve the encrypted pre-purge snapshot until a later verified backup cycle confirms the archive is recoverable. Define an operational retention period for these snapshots before implementation.
- Purge must be idempotent. Retrying a completed batch must not duplicate proof or remove additional rows.
- Record a batch identifier, cutoff, counts by table, proof hash, snapshot hash, result, and failure reason without storing personal data.
- Disk pressure is never a reason to bypass this policy; warn the owner instead.

## 4. Explicit prohibitions

- No purge implementation, SQL delete job, or retention worker may be enabled before this policy is approved.
- No deletion of open-balance bills.
- No purge if snapshot, proof, reconciliation, retention, or foreign-key validation fails.
- No removal of immutable product/barcode registry records.
- No modification of original sale/return/cancel/debt evidence to make reconciliation pass.

## 5. Owner approval required

The owner must approve:
- [ ] The pre-purge evidence and reconciliation fields above.
- [ ] The operational retention period for encrypted pre-purge snapshots.
- [ ] The atomicity boundary and idempotent batch behavior.
- [ ] The audit metadata retained after each purge.

**Approval status:** Not approved. D-022/D-023/D-042/D-043/D-054 remain authoritative; this draft adds no permission to purge.
