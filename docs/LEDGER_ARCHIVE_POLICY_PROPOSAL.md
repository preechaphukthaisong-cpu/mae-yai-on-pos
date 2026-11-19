# Append-Only Ledger Archive and Reconciliation Policy — Approved Specification

**Status: OWNER APPROVED — IMPLEMENTATION IN PROGRESS; LIVE PURGE PROHIBITED**  
**Decision date:** 2026-11-19  
**Scope:** G6 and existing retention decisions D-022, D-023, D-042, D-043, D-054.

## 1. Retention policy

The owner selects a conservative five-year POS evidence-retention baseline. This is a product policy, not a legal conclusion that every record or every unregistered sole proprietor is subject to the same statutory duty. Applicable tax/accounting requirements must be confirmed for the shop's actual status; where a longer period applies, the longer period controls. The implementation must support configuration up to seven years without schema redesign.

- Sales bills, receipts, credit settlement/payment history, returns, cancellations, and supporting transaction evidence: retain at least five years.
- Stock movement and price history: retain at least five years under this conservative POS policy.
- Daily/product summaries and reconciliation proofs: retain at least five years.
- Product and barcode registry: retain permanently.
- Any bill with an outstanding balance greater than zero: never purge.
- Records under dispute, tax/accounting review, legal hold, or unresolved reconciliation: never purge.
- Parent rows remain ineligible while any retained child references them. Do not weaken foreign keys or use cascade deletion to bypass retention.

Retention age is measured from the relevant business/transaction event and must be documented per table. It must not be shortened by app reinstall, device clock changes, or a failed backup.

## 2. Required pre-purge evidence

For each business date and proposed archive batch:

1. Create a consistent encrypted, integrity-checked snapshot. Record SHA-256 digest, schema version, byte length, and creation time.
2. Verify that the encrypted archive can be decrypted and restored into isolated staging storage. A file-exists check or envelope-header check alone is insufficient.
3. Build immutable reconciliation proof containing only approved D-054 fields: hash reference, business date, sale type, subtotal, fee charged, returned goods/fee, cancelled goods/fee, and net sales.
4. Exclude customer name, phone, customer ID, and other personal data from the proof.
5. Recompute totals from source ledger rows and independently compare daily/product summaries. Any mismatch blocks the entire batch.
6. Check outstanding credit balances, retention timestamps, legal holds, foreign-key dependencies, child-row retention, and SQL guards.
7. Execute a Dry Run first. Show the owner the proposed cutoff, table-by-table row counts, totals, excluded records, archive identifier, and proof hash. Dry Run must not mutate source rows.
8. Require a separate explicit owner confirmation for any future purge. Confirmation must identify the exact Dry Run batch and expire if source data changes.
9. Commit proof before deletion. A failure at any stage means no rows are purged.

## 3. Archive retention and recovery

- Encrypted archives and evidence needed to substantiate retained accounting records must be retained for at least five years, or longer where applicable law or a legal hold requires it.
- The system must support a seven-year retention configuration.
- Keep at least two independently verified archive copies where storage facilities permit; do not claim redundancy when both copies share the same failure domain.
- A backup is not a lawful substitute for required original records merely because it is encrypted. Preserve readability, authenticity, integrity, export/print capability, and retrieval evidence.
- Never delete the last known-good archive. Archive expiry must be blocked while it is the only recoverable copy of retained evidence.

## 4. Execution and recovery

- This phase implements Dry Run, evidence generation, retention evaluation, and tests only.
- **No live purge, SQL delete job, or retention worker may be enabled in Phase 3.** A separate implementation decision and gate are required after tax/accounting applicability and archive retrieval have been validated.
- Any future purge must bind eligibility, proof reference, exact deletion set, and audit record in one transaction.
- Purge must be idempotent. A retry must not duplicate proof or delete additional rows.
- Record batch ID, cutoff, counts by table, proof hash, snapshot hash, result, and failure reason without personal data.
- Disk pressure is never a reason to bypass retention or purge safeguards.

## 5. Owner decision

The owner approved the five-year conservative retention baseline, seven-year configurability, pre-purge Dry Run, independent restore verification, and prohibition on live purge in Phase 3.

**Approval status:** APPROVED for policy and non-destructive implementation only. Live purge remains prohibited pending a separate legal/accounting applicability review and release decision.
