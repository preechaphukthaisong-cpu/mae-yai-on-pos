# Phase 3 — Android Foundation Delivery

- Implementation branch: `phase-3-foundation` (merged)
- Release-gate follow-up: PR [#4](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/pull/4), branch `phase-3-release-gates`
- Status: **FOUNDATION IMPLEMENTATION COMPLETE — RELEASE GATES STILL APPLY**
- Latest verified code commit on follow-up branch: `0d2ffb55271abf6a11dfaf55e341161805592b87`
- CI run #350 and #354: success; Unit Tests/APK Build and Database Instrumentation Tests passed. CI #354 executed 29/29 emulator instrumentation tests with 0 failures.

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
- [x] Staging-only delta replay for currently implemented product-create operations; hash/schema/sequence validation, idempotency, atomic rollback and pending-journal acknowledgement tests pass in emulator CI.
- [x] CameraX + bundled ML Kit scanner source and Thai TTS service/fallback source.
- [x] CI unit tests, APK build, Room schema check and emulator database instrumentation pass on verified follow-up branch commit; CI #354 passed all 29 instrumentation tests.

## Formal scope decisions

D-065 separates delivery of the foundation code from Device Qualification. This is necessary because OAuth consent, a real Google account, and physical scanner/TTS/power-loss behavior cannot be honestly certified by a GitHub emulator runner. D-063 is fulfilled prospectively: authentic Room exports are committed from v4 onward; historical v1–v3 JSON is not fabricated. Existing migration tests remain the compatibility evidence for those legacy versions.

## Release gates — must pass before using the POS in the shop

- [ ] Complete Google OAuth consent on the target handset and prove Drive upload→list→download→decrypt→staged restore, including expired/revoked token and interrupted-network recovery.
- [ ] Complete delta release qualification: interrupted-upload reconciliation against Drive and replay handlers for every operation type introduced by the app. Current product-create replay is implemented and emulator-tested.
- [ ] Approve and implement a secure credential lifecycle for unattended daily full snapshots; never place recovery secrets in WorkManager input or plaintext preferences. See `docs/PHASE_3_BACKUP_CREDENTIAL_POLICY_PROPOSAL.md`.
- [ ] Complete physical-device tests: migration, process death, power interruption, scanner lifecycle, Thai TTS lifecycle, backup and restore.
- [x] Complete final audit that every production mutation path is serialized with database maintenance. Public product/barcode service constructors require `RoomTransactionRunner`; the Drive upload acknowledgement also uses it. See `docs/DB_WRITE_PATH_AUDIT.md`.
- [ ] Approve a verifiable archive/reconciliation policy before any append-only ledger purge. No purge implementation is permitted until then. See `docs/LEDGER_ARCHIVE_POLICY_PROPOSAL.md`.

## Acceptance tracking

The live gate register, owners, definitions of done, and evidence are maintained in `docs/PHASE_3_RELEASE_GATES.md`. Current status: G5 PASSED; G2 IN_PROGRESS; G1/G3/G4/G6 remain pending their required device, account, or owner evidence.

## Explicit limitations

- The launcher remains a foundation/status screen; the release gates above do not certify a complete shop-ready workflow.
- Only Room schema v4 has an authentic committed Room export. No historical v1–v3 JSON was found in repository history.
- Delta replay is currently implemented for the product-create operation types present in the codebase; remote interrupted-upload recovery and future operation coverage remain release qualifications.
- Daily full-snapshot scheduling and credential-vault lifecycle await owner approval; no recovery secret is persisted.
- Emulator CI is not physical-device or real Google OAuth evidence.
- No plaintext database or backup payload may be uploaded.
- D-060 app lock and SQLCipher remain Phase 10.

## Closure boundary

The Phase 3 **foundation implementation** is delivered and CI-verified. The release gates above are mandatory before shop use; closing this implementation phase does not waive them, authorize production use, or claim all release qualifications are complete.
