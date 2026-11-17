# Implementation status

## Repository reset — 17 November 2026

The executable Android application, Gradle build files, GitHub Actions build workflow, and executable SQL schema/migration/seed scripts were removed from the current `main` branch to establish a clean implementation baseline.

This is a source-code reset, not a repository or history deletion. The previous implementation remains available in Git history and in branch `archive/pre-code-reset-2026-11-17`.

The documents retained in this repository are requirements and design references. They do not indicate that any feature is currently implemented.

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

## Required invariants

- One app-private SQLite database; every screen uses the same provider.
- Financial and stock writes are transactional and audited.
- Money is integer satang.
- Sale lines snapshot product name, barcode, cost and price.
- Technical retries use unique idempotency keys; repeated legitimate purchases remain allowed.
- No cloud, sync, payment gateway or multi-device behavior in this single-device scope unless separately approved.
- Never purge unpaid debt, audit history or inventory evidence.
- Backup and restore must be verified before retention purge.

## Decisions not to invent

Credit service fee, stock-zero policy, reminder schedule, return/void permissions and legally applicable retention periods require explicit shop rules. These remain disabled until confirmed.

## Current implementation status

- Android application source: not present.
- Gradle project and build configuration: not present.
- Runtime SQLite schema/migrations: not present; previous SQL implementation remains in Git history and the archive branch.
- GitHub Actions build workflow: not present.
- Automated tests: design checklist retained; executable test suite must be recreated.
- No feature should be described as implemented until its code and tests exist in the new baseline.

## Physical-device acceptance

CI success is not device acceptance. Camera barcode scanning, speech/audio, backup export/restore, power-loss recovery and the 1–2 week parallel shop trial require real-device verification.
