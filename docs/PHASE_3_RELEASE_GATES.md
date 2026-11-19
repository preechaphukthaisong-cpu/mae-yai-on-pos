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
| G2 — Delta replay/apply | App implementation | IN_PROGRESS | Parse and validate delta envelope; apply to a staging DB only; enforce schema compatibility, contiguous sequence, operation identity/idempotency, duplicate detection, out-of-order rejection, sequence-gap rejection, and crash-safe replay/restart. Prove interrupted-upload reconciliation against Drive. Replay handlers must cover every operation type introduced by the app. | Automated tests for duplicate, replay, gap, out-of-order, malformed payload/hash, schema mismatch, interrupted upload/retry; staging DB integrity and resulting rows. |
| G3 — Secure unattended full-backup lifecycle | App implementation + security review + owner approval | NOT_STARTED | Obtain explicit approval of the credential-vault threat model; implement opt-in encrypted credential storage with Android Keystore; schedule daily full snapshot with WorkManager; no recovery secret, DEK plaintext, or OAuth token in WorkManager input, logs, or plaintext preferences. Handle missing/invalidated credentials safely. | Approved decision, threat model, scheduler tests, input-data inspection, credential lifecycle tests, redacted worker logs. |
| G4 — Physical-device qualification | Device operator + app implementation | NOT_STARTED | On target handset, test fresh install and migration, process death, force-stop/reboot/power interruption, scanner start/stop/recreation and duplicate suppression, Thai TTS init/speak/shutdown/fallback, encrypted backup/restore and rollback. | Device model, Android build, test matrix, screen/log evidence with secrets and personal data redacted. |
| G5 — Database write/maintenance serialization | App implementation | PASSED | Inventory every production mutation path. All writes and maintenance/snapshot/restore operations use one documented serialization boundary with consistent lock ordering; no application code writes through raw DAO/Room access outside the approved transaction API. Verify concurrent writer vs snapshot, failure rollback, and DB integrity. | Source call-site inventory, static audit, concurrency/instrumentation tests, integrity/FK checks, lock-order analysis. |
| G6 — Append-only ledger archive/reconciliation policy | Product owner approval + app implementation | NOT_STARTED | Obtain explicit approval of the archive/reconciliation policy covering retention, cutoff, immutable evidence, reconciliation totals, recovery, auditability, and failure handling. No purge until approval. | Approved policy decision, reconciliation examples/tests, proof no purge path is enabled before approval. |

## Execution sequence and current disposition

1. **G5 passed.** See `docs/DB_WRITE_PATH_AUDIT.md`; public write-service construction now requires `RoomTransactionRunner`.
2. **G2 in progress.** Staging-only replay exists for the currently implemented product-create operations. Emulator tests cover replay, idempotency, pending-journal acknowledgement, sequence gaps, and atomic rollback. Real Drive interrupted-upload recovery and future operation-handler coverage remain to be qualified.
3. **G3 awaiting owner decision.** See `docs/PHASE_3_BACKUP_CREDENTIAL_POLICY_PROPOSAL.md`. No credential vault or unattended schedule is implemented before approval.
4. **G1 requires real Google account consent and target handset execution.**
5. **G4 requires the physical target handset; emulator results are supplementary only.**
6. **G6 awaiting owner decision.** See `docs/LEDGER_ARCHIVE_POLICY_PROPOSAL.md`. No purge implementation is permitted before approval.
7. Re-audit all evidence, update `docs/phase-3-progress.md`, and record a new formal decision after D-065 only when the required gates pass.

## Execution constraints and external dependencies

- G1 requires the owner's Google account consent and target handset interaction. Do not claim it passed from CI.
- G4 requires the physical target handset and operator execution. Emulator results are supplementary only.
- G3 and G6 approvals are owner decisions and must not be fabricated by implementation.
- Never upload plaintext database/backup payloads or recovery credentials to CI artifacts, logs, or Drive.
- Do not alter D-065's foundation-closure boundary or claim production readiness until all release gates pass.
- Any gate blocked by missing owner/device/account input must remain explicitly blocked; do not mark it PASSED.

## Evidence log

| Date (UTC) | Gate | Change / test | Result | Evidence |
|---|---|---|---|---|
| 2026-11-19 | G5 | Audited production write/maintenance paths; fixed Drive upload acknowledgement to use the shared runner; restricted public service constructors to RoomTransactionRunner; added writer-vs-snapshot instrumentation. | PASSED | `docs/DB_WRITE_PATH_AUDIT.md`; CI [#335](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44446065844); CI [#350](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44448431288) |
| 2026-11-19 | G2 | Added authenticated delta decryption and staging-only replay for current product-create operations; enforces schema 4, contiguous sequences, hash/operation validation, idempotent duplicate replay, pending-row acknowledgement, gap/overlap rejection, atomic transaction, and integrity/FK checks. | IN_PROGRESS | `DeltaReplayCoordinator.java`; `BackupSnapshotRestoreTest.java`; CI [#347](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44447635122); CI [#349](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44448021034) |
| 2026-11-19 | G3 | Drafted opt-in encrypted Android Keystore credential-vault proposal; no credential persistence or daily schedule implemented. | AWAITING OWNER APPROVAL | `docs/PHASE_3_BACKUP_CREDENTIAL_POLICY_PROPOSAL.md` |
| 2026-11-19 | G6 | Drafted archive/reconciliation policy aligned with D-042/D-043/D-054; purge remains prohibited. | AWAITING OWNER APPROVAL | `docs/LEDGER_ARCHIVE_POLICY_PROPOSAL.md` |
