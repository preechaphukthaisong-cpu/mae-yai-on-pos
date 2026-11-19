# Project Progress — POS Master Plan v2.1

- Current phase: **Phase 3 — Foundation and real-device qualification**
- Active branch: `phase-3-release-gates`
- Base implementation branch: `phase-3-foundation` (merged)
- Latest code commit: `db1baa947608b96546845bcbd39274699962acb2` (Drive lookup now fails closed; CI pending).
- Latest completed CI evidence: [Run #361](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44471430760) — success on `805c4cba7291229aaad445e22a00654d58b349c5`; unit tests, debug APK, Room schema verification and database instrumentation passed.
- Phase 4 status: **BLOCKED** until every Phase 3 acceptance criterion in v2.1 is met.

## Acceptance status

| Gate | Status | Evidence / remaining work |
|---|---|---|
| G1 — Real Google Drive qualification | BLOCKED — owner/device required | Real OAuth consent, encrypted upload/list/download/decrypt/staged restore, token/account/offline cases on target handset. |
| G2 — Delta replay and interrupted upload | IN PROGRESS | Staging replay and negative-path tests exist. Fixed lookup so 401/non-2xx/malformed/paginated responses cannot be treated as absence; exact-name reconciliation is now wired. CI passed on Run #361 for the reconciliation fix. Deterministic transport interruption tests and real Drive qualification remain. |
| G3 — Secure unattended backup lifecycle | IN PROGRESS | Owner-approved policy is documented; credential vault, opt-in, scheduler, notifications, local retention and monthly staging verification need implementation and tests. |
| G4 — Physical-device qualification | BLOCKED — target handset required | Migration, process death, force-stop/reboot/power interruption, scanner lifecycle, Thai TTS, backup/restore and rollback. |
| G5 — Database write/maintenance serialization | PASSED | `docs/DB_WRITE_PATH_AUDIT.md`; CI evidence recorded in `docs/PHASE_3_RELEASE_GATES.md`. |
| G6 — Non-destructive archive/reconciliation | IN PROGRESS | Owner-approved policy documented; Dry Run, proof, archive verification and tests remain. Live purge is prohibited in Phase 3. |

## Delivered

- Android Java foundation, Room v4, non-destructive migrations 1→2→3→4, exported schema and CI drift check.
- SQLite WAL/durability configuration and transaction/write serialization.
- Integer-satang money, product registration/barcode foundation and backup journal.
- AES-256-GCM portable backup envelope, Recovery Password/Key wrappers, Keystore-protected local DEK, staged restore and rollback tests.
- Drive appData upload/list/download worker source; encrypted delta packaging and staging-only replay for implemented product-create operations.
- CameraX + bundled ML Kit scanner source and Thai TextToSpeech service/fallback source.
- G5 write-path audit and concurrency/integrity instrumentation.

## Current blockers

1. G2, G3 and G6 implementation/test work remains in the Phase 3 branch.
2. G1 and G4 require the owner's Google account and physical target handset. Emulator CI cannot satisfy these criteria.
3. The repository does not yet contain the release-signing/update proof required by Phase 3.1; Phase 3.1 starts only after Phase 3 passes.
4. Do not start Phase 3.1 or Phase 4 until Phase 3 acceptance is complete. Do not merge PR #4 or mark any gate passed without its required evidence.

## Work log

- 2026-11-19: Read `docs/DECISIONS.md` and the existing `docs/phase-3-progress.md`; established this canonical v2.1 progress file because `docs/PROGRESS.md` was missing.
- 2026-11-19: Verified PR #4 remains open at `c85293e24acac5f4615a294f2235b79968102a4c`; CI Run #358 succeeded. No device/OAuth evidence was available in the repository.

- 2026-11-19: Fixed `GoogleDriveBackupUploadWorker.findExistingDriveFile`: clear expired token and fail closed on 401, fail closed on every non-2xx, request up to 1000 results, reject paginated/incomplete lookup, and use `DriveUploadReconciliation` for exact-name/duplicate detection. Commit `db1baa947608b96546845bcbd39274699962acb2`; CI Run #361 passed (unit tests, APK build, Room schema verification, database instrumentation).

- 2026-11-19: CI Run #361 completed successfully for `805c4cba7291229aaad445e22a00654d58b349c5`: unit tests, debug APK assembly, Room schema verification/export, and database instrumentation passed. This does not satisfy real-device/Google-account acceptance gates.

- 2026-11-19: Added `docs/PHASE_3_TEST_PLAN.md` with ordered manual/automated test procedures and exact source/test file matrix for G1–G6, migration, and target-device qualification. Document commit `1988fb8702a2822376eb7fcb5f39ee22f01eff1e`.
