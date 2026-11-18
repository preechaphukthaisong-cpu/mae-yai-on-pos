# Phase 3 — Android foundation and real-device acceptance

- Branch: `phase-3-foundation`
- Status: IN PROGRESS — NOT ACCEPTED
- Scope: Phase 3 in `docs/SPEC.md`; decisions D-062, D-063 and D-064 apply.

## Implemented in source (runtime/CI acceptance is separate)

- [x] Android Java application scaffold, Java 17, minSdk 26.
- [x] Room v4 database, WAL mode, explicit non-destructive migrations 1→2→3→4.
- [x] Foundation entities/DAOs, database guards, stock ledger and append-only operation/backup journals.
- [x] Product registration and barcode allocation in a Room transaction; generated barcode sequence starts at 2000001.
- [x] Integer-satang money and bill-level 5% Half Up fee calculator with unit tests.
- [x] SQLite/WAL snapshot, integrity validation, staging restore and rollback-oriented database activation code.
- [x] AES-256-GCM portable backup envelope, Recovery Password and Recovery Key wrapping, Android Keystore local DEK protection, and cryptographic tests.
- [x] Google Drive appDataFolder upload worker; it now performs bounded structural validation of the encrypted envelope before upload. This does not replace GCM authentication during restore.
- [x] CameraX + bundled ML Kit offline barcode scanner source and duplicate-emission tests.
- [x] Thai TTS selection/service source and fallback logic.
- [x] Room schema v4 JSON committed; CI workflow checks generated schema drift.
- [x] Restore lifecycle fix: Room singleton can be closed for restore and Application resolves the current singleton after activation.

## Outstanding Phase 3 acceptance blockers

- [ ] Latest CI run must finish successfully on the latest PR head, including unit tests, APK build, schema verification and instrumentation tests. A cancelled or compile-only run is not acceptance.
- [ ] Commit reproducible Room schema history for every supported historical version, or formally revise D-063 with evidence that older schemas were never Room-generated. Do not fabricate historical JSON.
- [ ] Prove `busy_timeout=5000` and `synchronous=FULL` on every effective Room connection; current callback/test coverage does not prove pool-wide enforcement.
- [ ] Complete Drive OAuth consent/account selection, remote list/download, and a tested upload→download→decrypt→staged-restore round trip.
- [ ] Implement durable journal delta cursor/reconciliation and daily full-snapshot scheduling; current worker uploads a supplied encrypted snapshot file only.
- [ ] Add restore activation rollback tests, including failure after the candidate database has been opened.
- [ ] Reduce backup memory pressure: current crypto/file-store path materializes large payloads in byte arrays and can exhaust handset memory near the 512 MiB limit.
- [ ] Audit every production write path so snapshot/restore maintenance cannot be bypassed by direct DAO/database writes.
- [ ] Resolve append-only ledger retention through a verifiable archive/reconciliation design before any purge implementation.
- [ ] Physical-device acceptance: migration, process death, power loss, scanner behavior, TTS voice/lifecycle, encrypted backup and recovery.

## Security gates

- Never upload a plaintext database, snapshot, journal or unvalidated file.
- Upload is limited to app-private `.myobak` files with a structurally valid versioned envelope.
- GCM authenticity and payload integrity must be verified during decrypt/restore.
- Recovery Password and Recovery Key are independent recovery paths; neither is uploaded.
- D-060 app lock and SQLCipher database-at-rest encryption remain Phase 10.

## Closure rule

Phase 3 remains open until every mandatory blocker above has evidence, latest CI is green, and physical-device acceptance is recorded. No merge to `main`, tag, or Phase 4 start before acceptance.
