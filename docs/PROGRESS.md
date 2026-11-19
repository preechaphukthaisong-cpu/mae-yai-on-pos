# Project Progress — POS Master Plan v2.1

- Current phase: **Phase 3 — Foundation and real-device qualification**
- Active branch: `phase-3-release-gates`
- Base implementation branch: `phase-3-foundation` (merged)
- Latest relevant code commit: `57d959a1445a5a0210cb2d418f9125d9ce14a1f3` (Google Sign-In result diagnostics).
- Latest completed CI evidence: [Run #366](https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44476508437) — success; unit tests/APK build and database instrumentation tests passed. This is CI evidence only; it does not prove physical-device OAuth/Drive qualification.
- Phase 4 status: **BLOCKED** until every Phase 3 acceptance criterion in v2.1 is met.

## Product direction — Multi-Store Ready (D-066, 19 พฤศจิกายน 2026)

- เป้าหมายผลิตภัณฑ์: แอปชุดเดียวสำหรับร้านค้าทั่วไปหลายราย ไม่ hard-code ชื่อหรือข้อมูลเฉพาะร้านแม่ใหญ่อ่อน
- ขอบเขต v1: หนึ่ง installation / หนึ่งอุปกรณ์ / หนึ่งร้าน; ไม่ใช่ multi-tenant ในแอปเดียว และไม่มี cloud transaction database หรือ account ของผู้พัฒนาเป็นศูนย์กลาง
- ผู้ใช้ตั้งค่าโปรไฟล์ร้านเอง; การขายและงานหลักยัง offline-first และไม่ต้อง Google login
- Google Sign-In เป็น optional เฉพาะ backup/restore; ร้านใช้ Google account ของตนเอง ภายใต้ OAuth app configuration ชุดเดียว
- D-058 และ `drive.appdata` ยังคงเดิมในรอบนี้; ต้องสื่อสารว่าพื้นที่ appDataFolder ซ่อนจากมุมมองไฟล์ปกติใน Drive การเปลี่ยน scope/ปลายทางต้องขออนุมัติใหม่
- งานเตรียมเผยแพร่ทั่วไป (stable release signing, OAuth consent/configuration, privacy disclosure และ qualification) ยังต้องทำตามเฟสและ release gates; การอนุมัติ D-066 ไม่ได้ปิด G1–G6 หรืออนุญาตเริ่ม Phase 3.1/4

## Acceptance status

| Gate | Status | Evidence / remaining work |
|---|---|---|
| G1 — Real Google Drive qualification | BLOCKED — owner/device required | Real OAuth consent, encrypted upload/list/download/decrypt/staged restore, token/account/offline cases on target handset. |
| G2 — Delta replay and interrupted upload | IN PROGRESS | Staging replay and negative-path tests exist. Lookup fails closed on 401/non-2xx/malformed/paginated responses; exact-name reconciliation is wired. Deterministic transport interruption tests and real Drive qualification remain. |
| G3 — Secure unattended backup lifecycle | IN PROGRESS — implementation not yet evidenced | Approved policy exists, but source review found no credential vault, opt-in lifecycle, periodic full-backup worker, failure notification, local retention manager, or monthly staging verifier. Implement and test these before claiming completion. |
| G4 — Physical-device qualification | BLOCKED — target handset required | Migration, process death, force-stop/reboot/power interruption, scanner lifecycle, Thai TTS, backup/restore and rollback. |
| G5 — Database write/maintenance serialization | PASSED | `docs/DB_WRITE_PATH_AUDIT.md`; CI evidence recorded in `docs/PHASE_3_RELEASE_GATES.md`. |
| G6 — Non-destructive archive/reconciliation | BLOCKED — source ledger schema prerequisite missing | Room schema v4 contains app_meta, barcode_registry, barcode_counter, product, customer, stock_log, op_log and backup_journal; it does not contain the sale/receipt/return/debt/summary ledger tables required by D-054 proof and the approved G6 policy. Do not invent tables or claim a meaningful archive Dry Run until the approved ledger schema exists. Live purge remains prohibited in Phase 3. |

## Delivered

- Android Java foundation, Room v4, non-destructive migrations 1→2→3→4, exported schema and CI drift check.
- SQLite WAL/durability configuration and transaction/write serialization.
- Integer-satang money, product registration/barcode foundation and backup journal.
- AES-256-GCM portable backup envelope, Recovery Password/Key wrappers, Keystore-protected local DEK, staged restore and rollback tests.
- Drive appData upload/list/download worker source; encrypted delta packaging and staging-only replay for implemented product-create operations.
- CameraX + bundled ML Kit scanner source and Thai TextToSpeech service/fallback source.
- G5 write-path audit and concurrency/integrity instrumentation.

## Current blockers

1. G2 deterministic upload-interruption transport tests remain; existing lookup/reconciliation hardening is not equivalent to that evidence.
2. G3 credential vault, explicit opt-in, periodic backup lifecycle, notifications, retention and monthly staging verification are not yet evidenced in source.
3. G6 cannot produce valid sales/accounting reconciliation evidence against Room v4 because the required source ledger tables are absent. This is a schema/phase dependency, not permission to invent or prematurely add ledger schema in Phase 3.
4. G1 and G4 require the owner's Google account and physical target handset. Emulator CI cannot satisfy these criteria.
5. The repository does not yet contain the release-signing/update proof required by Phase 3.1; Phase 3.1 starts only after Phase 3 passes.
6. Do not start Phase 3.1 or Phase 4 until Phase 3 acceptance is complete. Do not merge PR #4 or mark any gate passed without its required evidence.

## Work log

- 2026-11-19: Fixed Google Sign-In result handling in `MainActivity`: inspect a returned Intent even when Activity resultCode is not RESULT_OK, and surface the Google Sign-In status code/string instead of reporting every non-OK result as user cancellation. Commit `57d959a1445a5a0210cb2d418f9125d9ce14a1f3`. Physical-device OAuth retest and CI are pending; this does not yet prove G1.
- 2026-11-19: Established this canonical v2.1 progress file and refreshed evidence against CI Run #366.
- 2026-11-19: Fixed Drive upload lookup to fail closed on 401/non-2xx/malformed/paginated responses and reconcile exact deterministic filenames. Commit `db1baa947608b96546845bcbd39274699962acb2`; CI Run #361 passed.
- 2026-11-19: Added Phase 3 manual/automated test procedures and source/test matrix. Commit `1988fb8702a2822376eb7fcb5f39ee22f01eff1e`.
- 2026-11-19: Recorded D-066 Multi-Store Ready; no schema, OAuth scope, menu, or gate status change was authorized.
- 2026-11-19: Source audit for follow-up implementation confirmed G3 lifecycle components are not yet evidenced. Confirmed Room schema v4 lacks the transaction-ledger tables required for G6 reconciliation proof; G6 is blocked on that prerequisite. No application source or schema was changed in this audit.
