# Phase 3 — Foundation and real-device proof
- Branch: `phase-3-foundation`
- Status: IN PROGRESS
- Approved scope: `docs/SPEC.md` Phase 3 and `docs/DECISIONS.md` D-058–D-061.
## Completed in the initial foundation scaffold
- [x] Android Java Gradle application skeleton; package `com.maeyaion.pos`; minSdk 26.
- [x] Single Room database entry point and WAL mode; destructive migration fallback is prohibited.
- [x] Core `Satang`, bill-level 5% Half Up fee calculation, and transaction-runner contract.
- [x] GitHub Actions workflow for unit tests and debug APK assembly.
- [x] Disabled Android platform auto-backup so the database is not silently copied outside the app's explicit backup flow.
## Not yet complete
- [ ] Full Room entities/DAOs and versioned migrations from `docs/SCHEMA.md`.
- [ ] Backup journal, consistent SQLite snapshots, manifest/checksum validation, staging restore and safety snapshots.
- [ ] Google account authorization using only `drive.appdata`, Drive upload and WorkManager retry.
- [ ] CameraX + bundled ML Kit offline scanner and lifecycle tests.
- [ ] Thai female TTS selection and fallback behavior.
- [ ] Instrumentation/migration tests and physical-device acceptance.
## Security gate
D-060 assigns app lock, SQLCipher and password-encrypted portable backup implementation to Phase 10. Until that work is implemented and tested, do not upload a plaintext database or plaintext backup payload to Google Drive. Drive authorization can be implemented independently, but remote backup transfer must remain disabled until the encrypted backup envelope and portable key-wrapping design are available. Do not weaken this gate to make a Phase 3 checklist appear complete.
## Build and acceptance policy
A successful CI build is not device acceptance. No APK is release-ready until backup/restore, scanner, TTS, process-death/power-loss recovery and the required physical-device tests pass.
