# Implementation status

## Approved baseline
Phase 2 design and business decisions O-001 through O-030 were approved and merged into `main` in PR #2 on 17 November 2026. The pre-reset implementation is preserved in `archive/pre-code-reset-2026-11-17`.

## Authoritative feature phases
0. Android foundation, one shared SQLite provider, versioned migrations, safe backup/restore and CI.
1. Barcode sequence and barcode catalogue.
2. Inventory: add/list/count/low-stock threshold.
3. Cash sales and stock deduction.
4. Credit sales and customer debt ledger.
5. Sales history and safe retention.
6. Store/product price management.
7. Reports and sales-screen summaries.
8. In-store parallel acceptance test for 1–2 weeks.

The current branch implements only a subset of the foundation. It is not a complete POS.

## Required invariants
- One app-private SQLite database; every screen uses the same provider.
- Financial and stock writes are transactional and audited.
- Money is integer satang.
- Sale lines snapshot product name, barcode, cost and price.
- Technical retries use unique idempotency keys; repeated legitimate purchases remain allowed.
- No cloud sync, payment gateway or multi-device behavior.
- Never purge unpaid debt, audit history or inventory evidence.
- Backup and restore must be verified before retention purge.
- Never transfer plaintext database or backup data to Google Drive.

## Verified implementation at current branch head
- Android Java scaffold, Java 17, minSdk 26, compile/target SDK 36.
- Room database version 4 with explicit migrations 1→2→3→4.
- Foundation entities/DAOs for app metadata, barcode registry/counter, product, customer, stock ledger, operation log and backup journal.
- Database triggers protect barcode identity, product identity, zero-stock creation, stock-ledger consistency and append-only audit/journal rows.
- Barcode allocation and product registration use transaction boundaries; idempotency is implemented for product registration.
- Integer-satang value object and bill-level 5% Half Up fee calculator.
- Offline Thai TTS selector/service exists, but actual device voice availability and lifecycle behavior are not device-verified.

## Not implemented / not release-ready
- Full POS screens and business workflows (sales, inventory UI, debt, reports, returns/cancellations and product management).
- Google Drive account authorization and remote backup workflow.
- Consistent SQLite snapshot, manifest validation, restore staging and recovery.
- Retention archive/reconciliation and controlled purge. Append-only evidence must not be deleted until a verified archive/reconciliation gate exists.
- SQLCipher, app lock and password-encrypted backup are assigned to Phase 10; plaintext remote transfer remains disabled until then.
- CameraX/ML Kit offline scanner.
- Real-device migration, process-death, power-loss, backup/restore and operational acceptance.

## Acceptance policy
A successful CI build proves only the checks that actually ran. It does not prove the complete POS is implemented, backup is recoverable, or the app is safe for live shop operations. Do not call this branch production-ready until the outstanding items above are implemented and tested.
