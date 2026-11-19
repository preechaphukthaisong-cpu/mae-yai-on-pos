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
| G2 — Delta replay/apply | App implementation | IN_PROGRESS | Parse and validate delta envelope; apply to staging DB only; enforce schema compatibility, contiguous sequence, operation identity/idempotency, duplicate detection, out-of-order rejection, sequence-gap rejection, and crash-safe replay/restart. Prove interrupted-upload reconciliation against Drive. Replay handlers cover every operation type introduced by the app. | Automated tests for duplicate, replay, gap, out-of-order, malformed payload/hash, schema mismatch, interrupted-upload/retry; staging DB integrity and resulting rows. |
| G3 — Secure unattended full-backup lifecycle | App implementation + security review + owner approval | IN_PROGRESS | Implement the owner-approved opt-in device-bound AES-256-GCM credential vault, dedicated Android Keystore key/AAD, daily full snapshot, safe WorkManager scheduling, offline Recovery Key acknowledgement, failure notification, checksum verification, monthly staging-restore verification, and 7–14 day local cache retention without deleting the last known-good or pending file. | Owner approval recorded; threat model; vault lifecycle and invalidation tests; WorkManager input inspection; scheduler and retention tests; restore verification and redacted logs. |
| G4 — Physical-device qualification | Device operator + app implementation | NOT_STARTED | On target handset, test fresh install and migration, process death, force-stop/reboot/power interruption, scanner start/stop/recreation and duplicate suppression, Thai TTS init/speak/shutdown/fallback, encrypted backup/restore and rollback. | Device model, Android build, test matrix, screen/log evidence with secrets and personal data redacted. |
| G5 — Database write/maintenance serialization | App implementation | PASSED | Inventory every production mutation path. All writes and maintenance/snapshot/restore operations use one documented serialization boundary with consistent lock ordering; no application code writes through raw DAO/Room access outside the approved transaction API. Verify concurrent writer vs snapshot, failure rollback, and DB integrity. | Source call-site inventory, static audit, concurrency/instrumentation tests, integrity/FK checks, lock-order analysis. |
| G6 — Append-only ledger archive/reconciliation policy | Product owner + app implementation | IN_PROGRESS | Implement five-year conservative retention baseline (configurable through seven years), non-destructive Dry Run with row counts/totals/exclusions, encrypted snapshot and independent staging-restore verification, reconciliation proof, audit metadata, legal-hold/open-balance protection. Live purge remains disabled in Phase 3. | Owner approval recorded; retention calculation tests; Dry Run immutability tests; archive integrity/restore tests; proof/reconciliation tests; static proof no live purge entry point is enabled. |

## Execution sequence and current disposition

1. **G5 passed.** See `docs/DB_WRITE_PATH_AUDIT.md`; public write-service construction requires `RoomTransactionRunner`.
2. **G2 in progress.** Staging-only replay exists for currently implemented product-create operations. Emulator tests cover replay, idempotency, pending-journal acknowledgement, sequence gaps, atomic rollback, and negative paths. Drive interrupted-upload reconciliation still requires deterministic transport tests and real Drive qualification.
3. **G3 approved; implementation in progress.** See `docs/PHASE_3_BACKUP_CREDENTIAL_POLICY_PROPOSAL.md`. Approval authorizes implementation, not production enablement.
4. **G1 requires real Google account consent and target handset execution.**
5. **G4 requires the physical target handset; emulator results are supplementary only.**
6. **G6 approved for non-destructive implementation only.** See `docs/LEDGER_ARCHIVE_POLICY_PROPOSAL.md`. Five-year conservative retention, seven-year configurability, Dry Run, and restore verification are required. Live purge remains prohibited.
7. Re-audit evidence, update progress, and record a new formal decision only when required gates pass.

## Execution constraints and external dependencies

- G1 requires the owner's Google account consent and target handset interaction. Do not claim it passed from CI.
- G4 requires the physical target handset and operator execution. Emulator results are supplementary only.
- G3/G6 owner decisions are approved as recorded in their policy documents; implementation and verification are still required.
- Never upload plaintext database/backup payloads or recovery credentials to CI artifacts, logs, or Drive.
- Never enable live purge under G6 in Phase 3.
- Do not alter D-065's foundation-closure boundary or claim production readiness until all release gates pass.

## Evidence log

| Date (UTC) | Gate | Change / test | Result | Evidence |
|---|---|---|---|---|
| 2026-11-19 | G5 | Audited production write/maintenance paths; fixed Drive upload acknowledgement to use the shared runner; restricted public service constructors to RoomTransactionRunner; added writer-vs-snapshot instrumentation. | PASSED | `docs/DB_WRITE_PATH_AUDIT.md`; CI [#335](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44446065844); CI [#350](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44448431288) |
| 2026-11-19 | G2 | Added authenticated staging-only delta replay and four negative-path instrumentation tests. | IN_PROGRESS | `DeltaReplayCoordinator.java`; `BackupSnapshotRestoreTest.java`; CI [#354](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44456764728) — 29/29 emulator instrumentation tests passed |
| 2026-11-19 | G3 | Owner approved the credential-vault design with offline Recovery Key acknowledgement, failure notification, integrity verification, monthly staging restore, and local cache retention controls. | IN_PROGRESS | `docs/PHASE_3_BACKUP_CREDENTIAL_POLICY_PROPOSAL.md`; implementation and CI evidence pending |
| 2026-11-19 | G6 | Owner approved five-year conservative evidence retention, seven-year configurability, Dry Run and restore verification; explicitly prohibited live purge in Phase 3. | IN_PROGRESS | `docs/LEDGER_ARCHIVE_POLICY_PROPOSAL.md`; non-destructive implementation and CI evidence pending |
