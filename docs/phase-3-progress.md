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
- [x] Room v4 foundational entities and DAOs: barcode registry/counter, product, customer, stock ledger, operation log and backup journal.
- [x] Explicit non-destructive migrations 1→2→3→4; preserve existing records, refresh database guards for v3 installations and reconcile legacy stock balances into a migration baseline ledger entry.
- [x] Shared database guard installer is called for fresh creation and migrations; protects barcode registry/counter, product identity/retention, initial zero stock, stock ledger, operation log and backup journal.
- [x] Barcode allocator preserves manufacturer values as TEXT; generated values start at 2000001, skip collisions and reserve/update the counter in one transaction.
- [x] ProductRegistrationService creates product + barcode + operation log + backup journal in one transaction; product stock always starts at zero and UUID idempotency keys protect retries.
- [x] Rollback unit tests verify that product insert failure reverts barcode registry, counter, operation log and backup journal.
- [x] Unit tests for barcode collision skipping, leading-zero preservation, duplicate rejection and whitespace validation.
- [x] Fresh-install barcode counter seed and database guards.

## Remaining
- [ ] Export and commit Room schema JSON for every database version; current `app/schemas/` is not yet populated.
- [ ] Resolve the append-only versus 90-day retention conflict with a verified archive/reconciliation design before implementing purge. Current stock/op log delete guards intentionally block deletion; retention is not yet implemented.
- [ ] CI validation of the current schema-v4 changes; latest run must pass unit tests, migration instrumentation tests and APK build before this item can be checked. Latest PR validation run: #102 (pending at last check).
- [ ] Instrumentation tests for fresh creation, Migration 1→2→3→4 (including upgrade from v3), foreign keys, stock/product triggers, backup-journal immutability and rollback behavior (tests added; awaiting CI execution).
- [ ] Phase 3 backup foundation services: snapshot manifest, reconciliation metadata and safe restore state (`backup_journal` table/DAO and migration are now implemented). Sales/returns/debt/reporting entities belong to their implementation phases (7–9), not Phase 3.
- [ ] Backup journal, consistent SQLite snapshots, manifest/checksum validation, staged restore and safety snapshots.
- [ ] Google account authorization using only `drive.appdata`; remote transfer remains blocked by the encryption gate below.
- [ ] CameraX + bundled ML Kit offline scanner and lifecycle tests.
- [ ] Thai female TTS selection and fallback behavior.
- [ ] Physical-device acceptance for database upgrade, process death, power loss and recovery.

## Security gate
D-060 assigns app lock, SQLCipher and password-encrypted portable backup implementation to Phase 10. Until that work is implemented and tested, do not upload a plaintext database or plaintext backup payload to Google Drive. Drive authorization can be implemented independently, but remote backup transfer must remain disabled until the encrypted backup envelope and portable key-wrapping design are available. Do not weaken this gate to make a checklist appear complete.

## Build and acceptance policy
A successful CI build is not device acceptance. No APK is release-ready until backup/restore, scanner, TTS, process-death/power-loss recovery and required physical-device tests pass.
