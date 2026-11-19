# Phase 3 Release Gate Acceptance Tracker

**Branch:** `phase-3-release-gates`  
**Baseline:** `main` at `cb7111926fb28d80d164dffadaad706dc32fefec`  
**Rule:** A gate is not PASSED by compilation or emulator CI alone. Evidence must be linked or recorded below. No production use is authorized until all required gates pass.

## Status vocabulary

- `NOT_STARTED` — no implementation/evidence work recorded.
- `IN_PROGRESS` — implementation or verification is underway.
- `EVIDENCE_READY` — implementation is complete; evidence is ready for independent review.
- `PASSED` — all gate-specific DoD is met and evidence is recorded.

## Gate register

| Gate | Owner | Status | Definition of Done | Required evidence |
|---|---|---|---|---|
| G1 — Google Drive real-account qualification | App implementation + device operator | NOT_STARTED | On target handset, complete OAuth consent and prove encrypted upload → list → download → decrypt → staged restore; test expired/revoked token, account change, offline/network interruption, and ensure live DB is untouched until explicit validated restore. | Redacted test log, Drive object IDs/metadata, envelope validation result, staged restore and rollback result, handset/Android version. |
| G2 — Delta replay/apply | App implementation | NOT_STARTED | Parse and validate delta envelope; apply to a staging DB only; enforce schema compatibility, contiguous sequence, operation identity/idempotency, duplicate detection, out-of-order rejection, sequence-gap rejection, and crash-safe replay/restart. Acknowledgement occurs only after remote upload confirmation and must not imply replay completion. | Automated tests for duplicate, replay, gap, out-of-order, malformed payload/hash, schema mismatch, interruption/restart; staging DB integrity and resulting rows. |
| G3 — Secure unattended full-backup lifecycle | App implementation + security review | NOT_STARTED | Define user-consented credential lifecycle and recovery model; schedule daily full snapshot with WorkManager; no Recovery Password/Key, DEK plaintext, OAuth token, or other secret in WorkManager input, logs, or plaintext preferences. Handle locked/unavailable credentials safely and surface actionable state. | Threat model/decision record, scheduler tests, input-data inspection, credential lifecycle tests, redacted worker logs. |
| G4 — Physical-device qualification | Device operator + app implementation | NOT_STARTED | On target handset, test fresh install and migration, process death, force-stop/reboot/power interruption, scanner start/stop/recreation and duplicate suppression, Thai TTS init/speak/shutdown/fallback, encrypted backup/restore and rollback. | Device model, Android build, test matrix, screen/log evidence with secrets and personal data redacted. |
| G5 — Database write/maintenance serialization | App implementation | IN_PROGRESS | Inventory every production mutation path. All writes and maintenance/snapshot/restore operations use one documented serialization boundary with consistent lock ordering; no application code writes through raw DAO/Room access outside the approved transaction API. Verify concurrent writer vs snapshot/checkpoint/restore, failure rollback, and DB integrity. | Source call-site inventory, static audit, concurrency/instrumentation tests, integrity/FK checks, lock-order analysis. |
| G6 — Append-only ledger archive/reconciliation policy | Product owner approval + app implementation | NOT_STARTED | Write a verifiable archive/reconciliation policy covering retention, cutoff, immutable evidence, reconciliation totals, recovery, auditability, and failure handling. Obtain explicit owner approval before implementing any purge. Until approval, purge remains prohibited. | Approved policy decision, reconciliation examples/tests, proof no purge path is enabled before approval. |

## Execution sequence

1. Complete G5 source inventory and harden the common write/maintenance boundary.
2. Implement G2 replay/apply into staging and its failure/recovery tests.
3. Decide and implement G3 secure daily full-backup credential lifecycle and scheduling.
4. Complete G1 real Google OAuth/Drive end-to-end qualification.
5. Complete G4 physical-device qualification; begin device preparation in parallel where possible.
6. Finish G5 concurrency and integrity verification.
7. Draft G6 policy; obtain explicit approval before any purge implementation.
8. Audit evidence for all gates, update `docs/phase-3-progress.md`, and record a new formal decision after D-065.

## Execution constraints and external dependencies

- G1 requires the owner's Google account consent and target handset interaction. Do not claim it passed from CI.
- G4 requires the physical target handset and operator execution. Emulator results are supplementary only.
- G6 policy approval is a business decision and must not be fabricated by implementation.
- Never upload plaintext database/backup payloads or recovery credentials to CI artifacts, logs, or Drive.
- Do not alter D-065's foundation-closure boundary or claim production readiness until all release gates pass.
- Any gate blocked by missing owner/device/account input must remain explicitly blocked; do not mark it PASSED.

## Evidence log

| Date (UTC) | Gate | Change / test | Result | Evidence |
|---|---|---|---|---|
| 2026-11-19 | G5 | Initial source review: `DatabaseMaintenanceGate` and `RoomTransactionRunner` exist; full mutation and maintenance call-site inventory is pending. | IN_PROGRESS | `app/src/main/java/com/maeyaion/pos/core/tx/DatabaseMaintenanceGate.java`; `RoomTransactionRunner.java` |
