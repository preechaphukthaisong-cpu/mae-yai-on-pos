# Phase 3 — Test Procedure and File Matrix (v2.1)

ใช้ทดสอบ branch `phase-3-release-gates` เท่านั้น ผล CI/Emulator ไม่ทดแทนการทดสอบ Google account และมือถือจริง ห้ามใช้ข้อมูลร้านจริงในการทดสอบ restore/rollback

## 1. APK และเครื่องมือ
- APK ทดสอบรอบก่อน: GitHub Actions Run #361 artifact `app-debug`: https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/actions/runs/44471430760
- Run #361 ตรวจ commit `805c4cba7291229aaad445e22a00654d58b349c5`; ตรวจ PR #4 ว่ามี commit ใหม่กว่าหรือไม่ก่อนเริ่ม
- เตรียม Android เครื่องเป้าหมาย, USB debugging (ถ้าจะเก็บ logcat), Wi-Fi/อินเทอร์เน็ต, บาร์โค้ด Code 128 อย่างน้อย 3 ค่า, บัญชี Google ที่เจ้าของเลือก และข้อมูลทดสอบเท่านั้น
- จดรุ่นมือถือ, Android build, เวลา, APK SHA-256, commit SHA และผลทุกข้อ
- ห้ามบันทึก OAuth token, Recovery Password/Key, keystore password หรือ PII ลง log/หลักฐาน

## 2. ไฟล์ที่ต้องทดสอบ

### Database / Migration
- `app/src/main/java/com/maeyaion/pos/core/db/AppDatabase.java`
- `app/src/main/java/com/maeyaion/pos/core/db/Migration1To2.java`
- `app/src/main/java/com/maeyaion/pos/core/db/Migration2To3.java`
- `app/src/main/java/com/maeyaion/pos/core/db/Migration3To4.java`
- `app/schemas/com.maeyaion.pos.core.db.AppDatabase/4.json`
- `app/src/androidTest/java/com/maeyaion/pos/core/db/AppDatabaseMigrationTest.java`

### Transaction / Durability
- `app/src/main/java/com/maeyaion/pos/core/tx/TransactionRunner.java`
- `app/src/main/java/com/maeyaion/pos/core/tx/RoomTransactionRunner.java`
- `app/src/main/java/com/maeyaion/pos/core/tx/DatabaseMaintenanceGate.java`
- `app/src/main/java/com/maeyaion/pos/core/db/DatabaseGuards.java`
- `app/src/androidTest/java/com/maeyaion/pos/core/db/ProductRegistrationRoomTest.java`

### Backup / Restore / Drive
- `app/src/main/java/com/maeyaion/pos/core/backup/BackupCrypto.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/AndroidKeystoreKeyProtector.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/EncryptedBackupFileStore.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/FullBackupCoordinator.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/DeltaBackupCoordinator.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/DeltaReplayCoordinator.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/RoomDatabaseSnapshotter.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/RoomDatabaseRestorer.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/SQLiteStagedRestoreValidator.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/BackupRestoreCoordinator.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/GoogleDriveBackupUploadWorker.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/GoogleDriveBackupDownloadWorker.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/GoogleDriveBackupListWorker.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/DriveBackupWorkScheduler.java`
- `app/src/main/java/com/maeyaion/pos/core/backup/DriveUploadReconciliation.java`
- `app/src/main/java/com/maeyaion/pos/core/db/BackupJournalDao.java`
- Tests: `app/src/test/java/com/maeyaion/pos/core/backup/{BackupCryptoTest,DeltaBackupCoordinatorTest,DriveUploadReconciliationTest,GoogleDriveBackupUploadWorkerTest,GoogleDriveBackupDownloadWorkerTest,FullBackupCoordinatorTest}.java`
- Instrumentation: `app/src/androidTest/java/com/maeyaion/pos/core/backup/{AndroidKeystoreKeyProtectorTest,BackupSnapshotRestoreTest,RoomDatabaseRestorerTest}.java`

### Scanner / Speech
- `app/src/main/java/com/maeyaion/pos/core/scanner/OfflineBarcodeScanner.java`
- `app/src/main/java/com/maeyaion/pos/core/scanner/OfflineBarcodeAnalyzer.java`
- `app/src/main/java/com/maeyaion/pos/core/scanner/BarcodeEmissionGate.java`
- `app/src/main/java/com/maeyaion/pos/core/speech/ThaiSpeechService.java`
- `app/src/main/java/com/maeyaion/pos/core/speech/ThaiVoiceSelector.java`
- Tests: `app/src/test/java/com/maeyaion/pos/core/scanner/BarcodeEmissionGateTest.java`, `app/src/test/java/com/maeyaion/pos/core/speech/ThaiVoiceSelectorTest.java`

## 3. Automated CI
บน commit ที่จะรับรอง รัน:
```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```
ตรวจ Room schema verification ตาม workflow ใน `.github/workflows/` (ใช้ task จริงตามไฟล์ workflow ไม่เดาชื่อ task) เกณฑ์ผ่าน: ทุก job ผ่าน เก็บ Actions URL, commit SHA, test count และ logcat artifact

## 4. G4 — มือถือจริง
ทำตามลำดับ ใช้ข้อมูลทดสอบ:
1. ติดตั้ง APK ที่ระบุ เปิด/ปิดเปิด 3 รอบ — ต้องไม่ crash
2. สร้างข้อมูลทดสอบผ่านจุดใช้งานที่มีอยู่ ตรวจค่าก่อน/หลังปิดแอป — ข้อมูลที่ commit แล้วต้องอยู่ครบ
3. Force Stop แล้วเปิดใหม่ — DB ต้องเปิดได้และข้อมูล committed อยู่ครบ
4. Reboot เครื่องแล้วเปิดใหม่ — ผลเหมือนข้อ 3
5. Scanner: เริ่ม/หยุด/เริ่มใหม่; สแกน barcode 3 ค่า ค่าละ 10 รอบ — ค่าต้องตรงและไม่ emit ซ้ำภายใน suppression window
6. Screen lifecycle: เปิด scanner, ปิดจอ/ปลดล็อก/กลับแอป — camera ต้องไม่ค้างและเริ่มสแกนใหม่ได้
7. TTS: init → speak ภาษาไทย → speak ซ้ำ → shutdown → init ใหม่ — ไม่ crash; เลือกเสียงหญิงไทยเมื่อมี และ fallback ตาม D-059
8. เก็บ logcat โดย redact secrets/PII
หากไม่มี UI/entry point ให้ทดสอบ scanner, TTS หรือทำข้อมูล ให้บันทึก BLOCKED พร้อมไฟล์/จุดเชื่อมต่อที่ขาด ห้ามอ้างว่าผ่านจากการตรวจ source อย่างเดียว

## 5. Migration
1. รัน `AppDatabaseMigrationTest` ครอบคลุม 1→2, 2→3, 3→4 และเส้นทางต่อเนื่องที่ test fixture รองรับ
2. ตรวจ table/column/index/UNIQUE/FK และข้อมูลเดิมหลัง migration
3. ทดสอบบนมือถือโดยใช้ฐานข้อมูล schema เก่าที่สร้างจาก build/fixture จริง แล้วอัปเกรดโดยไม่ clear app data
4. ตรวจ `PRAGMA integrity_check` ได้ `ok`, `foreign_key_check` ไม่มีผลลัพธ์ และ schema ตรง `4.json`
5. ตรวจไม่มี `fallbackToDestructiveMigration`
ถ้าไม่มี APK/fixture รุ่นเก่า ให้สถานะ device migration เป็น BLOCKED ไม่ใช่ PASS

## 6. G2 — Delta / upload retry
1. รัน DeltaBackupCoordinator, DeltaReplayCoordinator, DriveUploadReconciliation tests
2. ทดสอบ duplicate, gap, out-of-order, schema mismatch, hash mismatch และ atomic rollback; ต้อง replay ใน staging เท่านั้น
3. จำลอง Drive lookup 401, 403/404, 429, 5xx, malformed JSON, duplicate exact filename และ nextPageToken; เมื่อพิสูจน์ว่าไม่มีไฟล์เดิมไม่ได้ ต้อง fail/retry ไม่ upload ซ้ำ
4. จำลอง server รับ upload แล้ว client ไม่ได้รับ response; retry ต้องค้นพบ deterministic filename เดิมและไม่สร้างไฟล์ซ้ำ
5. ตรวจ journal acknowledgement ใช้ RoomTransactionRunner และไม่ mark uploaded ก่อนเงื่อนไขสำเร็จ
6. ทดสอบ Drive จริงใน G1

## 7. G1 — Google Drive จริง (เจ้าของบัญชีดำเนินการ)
1. เลือกบัญชี Google และยินยอม scope `drive.appdata`
2. สร้าง full backup จากข้อมูลทดสอบ; ยืนยัน encrypt ก่อน upload ไม่มี plaintext DB/journal ใน Drive
3. upload → list → download; จด Drive file ID/metadata (ไม่จด token)
4. ตรวจ envelope version, checksum, AES-GCM authentication และ Recovery Password/Key
5. restore ไป staging; ตรวจ schema, SQLite integrity, FK, จำนวนสินค้า/บิลและยอดทดสอบ
6. ยืนยัน live DB ไม่ถูกแทนก่อน validation/approval
7. ทดสอบ offline queue → online retry, token expired/revoked และเปลี่ยนบัญชี; local data ต้องไม่เสีย
8. ใช้ไฟล์ทดสอบเสียหายให้ validation fail; live DB ต้องไม่เปลี่ยน

## 8. G3 — Secure unattended backup
ทดสอบตาม `docs/PHASE_3_BACKUP_CREDENTIAL_POLICY_PROPOSAL.md`:
- opt-in และ offline Recovery Key acknowledgement
- Keystore device-bound key/AAD; key invalidation ต้อง fail closed
- WorkManager input ไม่มี token/password/recovery key; network constraint, retry/backoff, unique work และ reboot
- daily full snapshot, failure notification เมื่อค้างเกินเกณฑ์
- monthly isolated staging restore
- local cache 7–14 วัน แต่ไม่ลบ pending หรือ last known-good backup
ไฟล์หลัก: AndroidKeystoreKeyProtector, DriveBackupWorkScheduler, FullBackupCoordinator, EncryptedBackupFileStore และ Drive workers พร้อม automated tests ของแต่ละกรณี

## 9. G6 — Archive / Dry Run
1. ใช้ข้อมูลสังเคราะห์อายุ 4/5/7 ปี รวม legal hold, unpaid/open balance และสินค้า/บาร์โค้ดถาวร
2. Dry Run ต้องแสดงจำนวนแถว/ยอดรวม/รายการยกเว้นและไม่แก้ live DB
3. สร้าง encrypted archive ตรวจ checksum/authentication
4. restore ไป staging แล้วเทียบ row counts, totals, reconciliation proof
5. ตรวจ audit metadata และยืนยันไม่มี live purge entry point เปิดใช้งานใน Phase 3

## 10. เกณฑ์ปิด Phase 3
ปิดได้เมื่อ G1–G6 ผ่าน Definition of Done ใน `docs/PHASE_3_RELEASE_GATES.md` และมีหลักฐานทุกข้อ CI/emulator ไม่ทดแทน Google account/มือถือจริง; G5 ใช้ audit/CI หลักฐานเดิม ไม่ตรวจซ้ำหากไม่มีการแก้ไฟล์ที่เกี่ยวข้อง; G2/G3/G6 ต้องมี implementation + automated tests จริง; ห้ามเปิด live purge ใน Phase 3; ห้ามเริ่ม Phase 3.1/4 ก่อนครบ

บันทึกแต่ละรายการ PASS/FAIL/BLOCKED พร้อมวันที่, commit SHA, device/Android, คำสั่งทดสอบ, expected/actual, log/artifact link และ redact ข้อมูลลับ/PII ใน `docs/PROGRESS.md` และ `docs/PHASE_3_RELEASE_GATES.md`.
