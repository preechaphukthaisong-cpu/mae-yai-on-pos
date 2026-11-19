# Phase 3 — Android Foundation Delivery

- Implementation branch: `phase-3-foundation` (merged)
- Release-gate follow-up: PR [#4](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/pull/4), branch `phase-3-release-gates`
- Status: **FOUNDATION IMPLEMENTATION COMPLETE — RELEASE GATES IN PROGRESS**
- Latest previously CI-verified code commit: `b3e753ee196929860ecf23b0943caa66d9a21df3`
- CI #354 passed all 29 emulator instrumentation tests; CI #355 passed Unit Tests/APK Build and Database Instrumentation.

## Delivered and CI-verified

- [x] Android Java / Java 17 application scaffold, minSdk 26.
- [x] Room v4, SQLite WAL, explicit non-destructive migrations 1→2→3→4.
- [x] Room schema v4 committed and generated-schema drift checked by CI.
- [x] SQLite durability settings: busy_timeout=5000, synchronous=FULL, WAL.
- [x] Foundation entities, DAOs, stock ledger, operation log and backup journal.
- [x] Product registration transaction and barcode sequence starting at 2000001.
- [x] Integer-satang money and bill-level 5% Half Up fee calculator.
- [x] AES-256-GCM portable backup envelope, independent recovery password/key wrappers and Android Keystore local DEK protection.
- [x] Streaming full-backup encryption/decryption, SQLite integrity validation, staged restore and rollback tests.
- [x] Google Drive appDataFolder upload/list/download worker source.
- [x] Bounded encrypted delta journal packaging and staging-only replay for current product-create operations.
- [x] CameraX + bundled ML Kit scanner source and Thai TTS service/fallback source.
- [x] G5 source audit and concurrency/integrity tests.

## Owner decisions recorded — 2026-11-19

- [x] G3 approved for implementation with mandatory offline Recovery Key acknowledgement, device-bound Keystore vault, fail-closed behavior, backup failure notification, integrity verification, monthly isolated restore verification, and 7–14 day local cache policy. Approval is not implementation evidence.
- [x] G6 approved for non-destructive implementation: five-year conservative POS evidence-retention baseline, configurable up to seven years, Dry Run, encrypted archive and independent staging-restore verification. **Live purge remains prohibited in Phase 3.**
- [x] G6 retention is a conservative product policy, not a claim that every record of every unregistered sole proprietor is subject to an identical statutory five-year duty. Applicable tax/accounting status must be checked; longer requirements/legal holds prevail.

## Release gates

- [ ] G1 — Real Google OAuth consent and Drive upload/list/download/decrypt/staged-restore qualification on target handset.
- [ ] G2 — Deterministic interrupted-upload reconciliation tests and real Drive qualification; current operation handlers must cover all mutation types actually introduced.
- [ ] G3 — Implement approved credential vault, opt-in UI, scheduler, notifications, local retention and monthly restore verification; pass CI and security tests.
- [ ] G4 — Physical-device migration, process death, force-stop/reboot/power interruption, scanner lifecycle, Thai TTS, backup/restore and rollback.
- [x] G5 — Database write/maintenance serialization passed. See `docs/DB_WRITE_PATH_AUDIT.md`.
- [ ] G6 — Implement retention evaluation, Dry Run, proof and archive verification tests. Live purge is explicitly out of scope and prohibited in Phase 3.

## External dependencies

G1 requires the owner's Google account consent and target handset. G4 requires the physical target handset and operator execution. These cannot be certified by emulator CI.

## Closure boundary

D-065 remains unchanged. Foundation implementation is delivered; release qualification is not complete. No production use is authorized until all required gates pass. No live ledger purge is authorized in Phase 3.
