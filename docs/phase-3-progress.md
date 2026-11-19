# Phase 3 — Android Foundation Delivery

- Branch: `phase-3-foundation`
- Status: **FOUNDATION IMPLEMENTATION COMPLETE — DEVICE QUALIFICATION REQUIRED BEFORE STORE USE**
- Latest verified CI commit before this closure record: `1cc25cd9131d49f2f8fad5bd4b97242160fab9fe`
- CI run #328 (push) and #329 (pull request): success; Unit Tests, APK Build, Room schema drift verification and Database Instrumentation Tests passed.

## Delivered and CI-verified

- [x] Android Java / Java 17 application scaffold, minSdk 26.
- [x] Room v4, SQLite WAL, explicit non-destructive migrations 1→2→3→4.
- [x] Room schema v4 committed and generated-schema drift checked by CI.
- [x] SQLite durability settings: busy_timeout=5000, synchronous=FULL, WAL; external writer contention and reopen tests pass.
- [x] Foundation entities, DAOs, stock ledger, operation log and backup journal.
- [x] Product registration transaction and barcode sequence starting at 2000001.
- [x] Integer-satang money and bill-level 5% Half Up fee calculator.
- [x] AES-256-GCM portable backup envelope, independent recovery password/key wrappers and Android Keystore local DEK protection.
- [x] Streaming full-backup encryption/decryption, SQLite integrity validation, staged restore and rollback tests.
- [x] Google Drive appDataFolder upload/list/download worker source with encrypted-envelope validation and upload reconciliation.
- [x] Bounded encrypted delta journal packaging and acknowledgement only after remote upload confirmation.
- [x] CameraX + bundled ML Kit scanner source and Thai TTS service/fallback source.
- [x] CI unit tests, APK build, Room schema check and emulator database instrumentation pass on the latest verified source commit.

## Formal scope decisions

D-065 separates delivery of the foundation code from Device Qualification. This is necessary because OAuth consent, a real Google account, and physical scanner/TTS/power-loss behavior cannot be honestly certified by a GitHub emulator runner. D-063 is fulfilled prospectively: authentic Room exports are committed from v4 onward; historical v1–v3 JSON is not fabricated. Existing migration tests remain the compatibility evidence for those legacy versions.

## Release gates — must pass before using the POS in the shop

- [ ] Complete Google OAuth consent on the target handset and prove Drive upload→list→download→decrypt→staged restore.
- [ ] Implement and verify delta replay/apply into a staging database; prove idempotency, sequence-gap detection, duplicate handling and interrupted-upload recovery.
- [ ] Define a secure credential lifecycle for unattended daily full snapshots; never place recovery secrets in WorkManager input or plaintext preferences.
- [ ] Complete physical-device tests: migration, process death, power interruption, scanner lifecycle, Thai TTS lifecycle, backup and restore.
- [ ] Complete a final audit that every production mutation path is serialized with database maintenance; feature-level write APIs are not yet implemented, and raw DAO access must not be used as an application write API.
- [ ] Approve a verifiable archive/reconciliation policy before any append-only ledger purge. No purge implementation is permitted until then.

## Explicit limitations

- The launcher remains a foundation/status screen; this branch is not a finished shop-ready POS UI.
- Only Room schema v4 has an authentic committed Room export. No historical v1–v3 JSON was found in repository history.
- Delta replay and unattended daily full-snapshot scheduling are not accepted as complete.
- Emulator CI is not physical-device or real Google OAuth evidence.
- No plaintext database or backup payload may be uploaded. Recovery secrets remain transient.
- D-060 app lock and SQLCipher remain Phase 10.

## Closure boundary

The Phase 3 **foundation implementation** is delivered and CI-verified. The release gates above are mandatory before shop use; closing this implementation phase does not waive them, authorize production use, or claim the complete POS application is finished.
