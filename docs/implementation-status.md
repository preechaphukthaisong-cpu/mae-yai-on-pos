# Implementation status

## Repository and approved baseline
Phase 2 design and business decisions O-001 through O-030 were approved and merged into `main` in PR #2 on 17 November 2026. The previous pre-reset implementation remains preserved in branch `archive/pre-code-reset-2026-11-17`.

## Authoritative development phases
0. Rebuild the Android foundation, one shared SQLite provider, versioned migrations, safe backup/restore, and CI.
1. Barcode sequence and barcode catalogue.
2. Inventory: add/list/count/low-stock threshold.
3. Cash sales and stock deduction.
4. Credit sales and customer debt ledger.
5. Sales history and safe retention.
6. Store/product price management.
7. Reports and sales-screen summaries.
8. In-store parallel acceptance test for 1–2 weeks.

The approved SPEC.md Phase 3 foundation work is tracked in `docs/phase-3-progress.md`; phase numbering in that document is distinct from the feature implementation phase list above.

## Required invariants
- One app-private SQLite database; every screen uses the same provider.
- Financial and stock writes are transactional and audited.
- Money is integer satang.
- Sale lines snapshot product name, barcode, cost and price.
- Technical retries use unique idempotency keys; repeated legitimate purchases remain allowed.
- No cloud sync, payment gateway or multi-device behavior.
- Never purge unpaid debt, audit history or inventory evidence.
- Backup and restore must be verified before retention purge.

## Current implementation status
- Android Gradle project: Java application scaffold on `phase-3-foundation`.
- Room: version 2 with explicit 1→2 migration; foundational barcode registry/counter, product, customer, stock_log and op_log entities/DAOs.
- Database guards: stock changes only through stock_log insert trigger; stock and operation logs are append-only; barcode registry is immutable.
- Barcode: transactional allocator preserves manufacturer barcode strings and skips all registered generated-number collisions.
- Core money: integer-satang value object and bill-level 5% Half Up calculator with unit tests.
- CI: GitHub Actions test/build workflow; latest changes await validation.
- Backup/Drive, scanner, TTS, sales/debt entities, feature screens and full device acceptance: not complete.
- No feature should be described as implemented until its code and tests exist in the new baseline.
