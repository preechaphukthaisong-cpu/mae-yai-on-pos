# Phase 3 — Foundation and real-device proof
- Branch: `phase-3-foundation`
- Status: IN PROGRESS
- Approved scope: `docs/SPEC.md` Phase 3 and `docs/DECISIONS.md` D-058–D-061.

## Completed
- [x] Android Java Gradle application skeleton; package `com.maeyaion.pos`; minSdk 26.
- [x] Single Room database entry point, WAL mode, no destructive migration fallback.
- [x] Core integer-satang value object and bill-level 5% Half Up fee calculation with unit tests.
- [x] Shared Room transaction-runner contract and adapter.
- [x] GitHub Actions unit-test and debug-APK pipeline.
- [x] Disabled Android platform auto-backup pending the explicit encrypted backup design.
- [x] Room v2 foundational entities and DAOs: barcode registry/counter, product, customer, stock ledger and operation log.
- [x] Explicit non-destructive Migration 1→2; preserves the existing app_meta table.
- [x] Database guards: immutable barcode registry, append-only stock/op ledgers, stock before-balance validation, non-negative stock and stock cache update from stock_log only.
- [x] Barcode allocator: manufacturer values are preserved as TEXT; generated values start at 2000001, skip collisions, and reserve/update the counter in one transaction.
- [x] Unit tests for barcode collision skipping, leading-zero preservation, duplicate rejection and whitespace validation.
- [x] Fresh-install barcode counter seed.

## Remaining
- [ ] CI validation of the current database and barcode changes.
- [ ] Instrumentation tests for fresh creation, Migration 1→2, foreign keys, triggers and rollback behavior.
- [ ] Remaining business entities/DAOs from `docs/SCHEMA.md` (sales, returns, debt, summaries, backup journal and reconciliation).
- [ ] Backup journal, consistent SQLite snapshots, manifest/checksum validation, staged restore and safety snapshots.
- [ ] Google account authorization using only `drive.appdata`; remote transfer remains blocked by the encryption gate below.
- [ ] CameraX + bundled ML Kit offline scanner and lifecycle tests.
- [ ] Thai female TTS selection and fallback behavior.
- [ ] Physical-device acceptance for database upgrade, process death, power loss and recovery.

## Security gate
D-060 assigns app lock, SQLCipher and password-encrypted portable backup implementation to Phase 10. Until that work is implemented and tested, do not upload a plaintext database or plaintext backup payload to Google Drive. Drive authorization can be implemented independently, but remote backup transfer must remain disabled until the encrypted backup envelope and portable key-wrapping design are available. Do not weaken this gate to make a checklist appear complete.

## Build and acceptance policy
A successful CI build is not device acceptance. No APK is release-ready until backup/restore, scanner, TTS, process-death/power-loss recovery and required physical-device tests pass.
