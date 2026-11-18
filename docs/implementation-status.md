# Implementation status

## Approved baseline

Phase 2 design and business decisions O-001 through O-030 were approved and merged into `main` in PR #2 on 17 November 2026. The pre-reset implementation remains in `archive/pre-code-reset-2026-11-17`.

## Current development state

Phase 3 is in progress on `phase-3-foundation` in PR #3. It is not accepted, is not production-ready, and is not a complete POS. Do not start Phase 4 until Phase 3 acceptance is complete.

## Implemented source areas

- Android Java scaffold, Java 17, minSdk 26.
- Room v4, WAL, explicit migrations 1→2→3→4, foundation entities and database guards.
- Product/barcode registration, operation log and backup journal transaction path.
- Integer-satang money and 5% bill-level Half Up fee calculation.
- SQLite/WAL snapshot, staged restore and rollback-oriented activation.
- AES-256-GCM backup envelope, Recovery Password/Recovery Key wrapping and Android Keystore local key protection.
- Google Drive appDataFolder upload worker with app-private path and envelope-structure preflight.
- CameraX/ML Kit offline scanner source and Thai TTS source.
- Unit and instrumentation test source plus Android CI workflow.

Source existence is not equivalent to passing CI, device acceptance or release readiness.

## Known outstanding acceptance items

- Latest CI must complete green on the current PR head; all test jobs must actually execute.
- Drive OAuth consent/account selection, remote download/list, and end-to-end remote restore are not complete.
- Durable delta-journal upload cursor/reconciliation and daily full-snapshot scheduling are not complete.
- Only Room schema v4 is committed; historical schema coverage required by D-063 is unresolved.
- Pool-wide SQLite durability PRAGMA enforcement required by D-064 is not proven.
- Restore rollback failure-path coverage and real-device recovery tests remain.
- Crypto and file storage currently materialize large payloads in memory; memory usage must be redesigned/tested for handset-sized resources.
- Every production database write path must be audited against the maintenance gate.
- Camera, Thai voice selection, process-death, power-loss and backup/recovery require physical-device acceptance.
- Append-only audit/ledger retention needs a verifiable archive/reconciliation gate before purge.

## Required invariants

- One app-private SQLite database.
- Financial and stock writes are atomic and audited.
- Money is integer satang; no floating-point money.
- Technical retries use idempotency keys.
- No cloud sync, payment gateway or multi-device operation in v1.
- Never transfer plaintext database or backup data to Google Drive.
- Never purge unpaid debt or audit/inventory evidence without the approved retention/archive safeguards.

## Acceptance policy

A successful build proves only the checks that actually ran. No merge, release tag, Phase 4 start or production-readiness claim is permitted until the Phase 3 checklist and physical-device acceptance are complete.
