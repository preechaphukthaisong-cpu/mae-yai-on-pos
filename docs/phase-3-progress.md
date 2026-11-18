# Phase 3 — Foundation and real-device proof
- Branch: `phase-3-foundation`
- Status: IN PROGRESS
- Approved scope: `docs/SPEC.md` Phase 3 and `docs/DECISIONS.md` D-058–D-061.

## Implemented in source (not all runtime-verified)
- [x] Android Java Gradle application skeleton; package `com.maeyaion.pos`; minSdk 26.
- [x] Single Room database entry point, WAL mode, no destructive migration fallback.
- [x] Integer-satang value object and bill-level 5% Half Up fee calculation with unit tests.
- [x] Shared Room transaction-runner contract and adapter.
- [x] Room v4 foundational entities/DAOs: barcode registry/counter, product, customer, stock ledger, operation log and backup journal.
- [x] Explicit non-destructive migrations 1→2→3→4; v1 and v3 upgrade tests are authored.
- [x] Database guard installer protects barcode registry/counter, product identity/retention, initial zero stock, stock ledger, operation log and backup journal.
- [x] Transactional barcode allocator preserves manufacturer barcode strings, generates from 2000001 and skips registered collisions.
- [x] ProductRegistrationService atomically writes product, barcode registry, operation log and backup journal; UUID idempotency keys protect retries.
- [x] Offline Thai TTS voice selection and fallback service source exists; real-device voice behavior is not verified.
- [x] Android CI has unit tests, emulator instrumentation tests, debug APK assembly and Room schema export validation.

## Remaining in Phase 3
- [ ] Obtain a successful CI run in which instrumentation tests actually execute, the Room v4 schema is generated/validated and the APK artifact uploads.
- [ ] Commit the generated Room v4 schema JSON to source control after verifying the exact Room-generated artifact.
- [ ] Consistent SQLite/WAL snapshot, manifest/checksum validation, staged restore and safety snapshot flow.
- [ ] Google account authorization limited to `drive.appdata`; remote transfer stays disabled until D-060 encryption requirements are implemented and tested.
- [ ] CameraX + bundled ML Kit offline scanner and lifecycle handling.
- [ ] Verify Thai female TTS selection and fallback on the target physical device.
- [ ] Resolve append-only ledger versus 90-day retention using a verifiable archive/reconciliation process before implementing purge.
- [ ] Physical-device acceptance for migration, process death, power loss and recovery.

## Security gate
D-060 assigns app lock, SQLCipher and password-encrypted portable backup implementation to Phase 10. Until that phase is implemented and tested, do not upload a plaintext database or plaintext backup payload to Google Drive. Drive authorization may be implemented independently, but remote backup transfer must remain disabled.

## Build and acceptance policy
A successful CI build is not device acceptance. No APK is release-ready until backup/restore, scanner, TTS, process-death/power-loss recovery and required physical-device tests pass.
