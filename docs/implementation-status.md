# Implementation status

The existing repository was a framework-neutral specification and SQL foundation, not a runnable Android application. The new Android source is the first executable application layer.

## Authoritative phases
0. Android foundation, single SQLite provider, migrations, backup/restore and device verification.
1. Barcode sequence and barcode catalogue.
2. Inventory: add/list/count/low-stock threshold.
3. Cash sales and stock deduction.
4. Credit sales and customer debt ledger.
5. Sales history and safe retention.
6. Store/product price management.
7. Reports and sales-screen summaries.
8. In-store parallel acceptance test for 1–2 weeks.

## Required invariants
- One app-private SQLite database; every screen uses the same provider.
- Financial and stock writes are transactional and audited.
- Money is integer satang.
- Sale lines snapshot product name, barcode, cost and price.
- Technical retries use unique idempotency keys; repeated legitimate purchases remain allowed.
- No cloud, sync, payment gateway or multi-device behavior in this single-device scope.
- Never purge unpaid debt, audit history or inventory evidence.
- Backup and restore must be verified before retention purge.

## Decisions not to invent
Credit service fee, stock-zero policy, reminder schedule, return/void permissions and legally applicable retention periods require explicit shop rules. These remain disabled until confirmed.

## Physical-device acceptance
CI success is not device acceptance. Camera barcode scanning, speech/audio, backup export/restore, power-loss recovery and the 1–2 week parallel shop trial require real-device verification.
