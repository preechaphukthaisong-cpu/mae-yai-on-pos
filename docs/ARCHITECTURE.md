# สถาปัตยกรรมระบบ POS ร้านแม่ใหญ่อ่อน (ARCHITECTURE)

- รุ่นเอกสาร: 1.0.6
- เฟส: 3 — Foundation implementation in progress
- สถานะ: สถาปัตยกรรมอ้างอิงตาม D-001 ถึง D-061; มี Android Java source และ Room schema v4 foundation แล้ว แต่ business features และ device acceptance ยังไม่เสร็จ
- อ้างอิงหลัก: docs/SPEC.md, docs/DECISIONS.md
- Package หลัก: com.maeyaion.pos

## 1. ขอบเขตที่ยึดถือ

- Android native, Java, minSdk 26
- โทรศัพท์เครื่องเดียว, Offline-first
- Room บน SQLite เพียงไฟล์เดียว เป็น single source of truth ของทุกเมนู
- Google Drive ใช้เก็บไฟล์สำรองเท่านั้น ไม่ใช่ฐานข้อมูลกลางและไม่ใช่ Cloud sync
- v1 ไม่มีระบบพิมพ์จริง; มีเพียง PrinterService interface ว่าง
- ไม่มี payment gateway, backend/API runtime, multi-device sync หรือ LAN replication
- เงินเก็บเป็นจำนวนเต็มหน่วยสตางค์ชนิด long; ห้ามใช้ float/double
- จำนวนสินค้าเก็บเป็น integer milli-unit (1 หน่วย = 1,000); ห้ามใช้ floating point
- วันธุรกิจใช้ Asia/Bangkok ตัดวัน 00:00 น.; timestamp เก็บ UTC

เอกสารนี้เป็นสถาปัตยกรรมอ้างอิง; การมี source code หรือ CI ผ่านไม่ถือเป็นหลักฐานว่า feature runtime หรือการยอมรับบนอุปกรณ์จริงเสร็จสมบูรณ์

## 2. ภาพรวมสถาปัตยกรรม

Android UI
  |
  +-- Home / Navigation (อ่าน MENU_LOCK.json)
  |
  +-- Feature screens: sales, inventory, barcode, history, store, reports
  |
  v
Feature Use Cases / Services
  |
  v
Core public contracts
  |-- db: AppDatabase, Room DAOs, migrations
  |-- tx: TransactionRunner
  |-- money: Satang, fee and total calculation
  |-- backup: journal, snapshot, Drive backup, restore
  |-- retention: purge guards and scheduled cleanup
  |-- scan: CameraX + bundled ML Kit
  |-- speech: Android TextToSpeech
  |-- notify: NotificationService, AlertBanner
  |-- ui: ConfirmDialog, SearchBar, shared components
  |-- print: PrinterService interface only
  |
  v
One Room AppDatabase / one SQLite file / WAL

UI ไม่มีสิทธิ์เขียน SQL หรือคำนวณยอดที่เป็นข้อเท็จจริงทางธุรกิจเอง ทุกการเปลี่ยนข้อมูลต้องผ่าน use case/service และ transaction กลาง รายงานอ่านจากข้อมูลที่ commit แล้วในฐานข้อมูลเดียวกัน

## 3. Package layout

โครงสร้างเริ่มต้นที่ต้องรักษา:

app/src/main/java/com/maeyaion/pos/
  app/                 # Application และ composition root
  core/
    db/                # AppDatabase, entities, DAOs, migrations
    tx/                # TransactionRunner
    money/             # Satang และ pure calculation
    backup/            # journal, snapshot, DriveUploader, restore
    retention/         # purge scheduler, guards
    notify/            # NotificationService, AlertBanner
    scan/              # ScannerView
    speech/            # SpeechService
    ui/                # ConfirmDialog, SearchBar, shared UI
    print/             # PrinterService interface ว่าง
    navigation/        # route IDs และ public navigation contracts
  home/
  feature/
    sales/
    inventory/
    barcode/
    history/
    store/
    reports/
app/src/test/
app/src/androidTest/

การเพิ่มคลาสย่อยทำได้โดยไม่เปลี่ยนขอบเขตเมนูหรือกฎ dependency ห้ามรวมหลาย feature ไว้ในคลาสเดียว โครงสร้าง Gradle multi-module ยังไม่ถูกกำหนด; ให้เริ่มจาก package boundary ในแอปเดียวก่อน เว้นแต่ผู้ใช้อนุมัติการแยก Gradle module ภายหลัง

## 4. Dependency rules — บังคับใช้

1. แต่ละ feature ใช้ได้เฉพาะ public API ของ core และ Android/AndroidX
2. Feature ห้าม import, instantiate หรือเรียก use case/repository/view ของ feature อื่นโดยตรง
3. core ห้าม import package home หรือ feature.*
4. home อ่านรายการเมนูจาก MENU_LOCK และเรียก navigation contract เท่านั้น ห้ามผูกกับ implementation class ของ feature
5. Composition root ใน app เป็นจุดเดียวที่ประกอบ feature screen factories เข้ากับ route registry ได้
6. การแลกข้อมูลข้าม feature ให้ผ่าน DTO/interface/use case contract ที่ประกาศใน core เท่านั้น
7. Shared scanner, speech, dialogs, notification และ UI components อยู่ใน core ไม่คัดลอกไปแต่ละ feature
8. MenuLockTest และ dependency-boundary test ต้องตรวจข้อกำหนดเหล่านี้ในเฟส 4

| ผู้เรียก | อนุญาตให้พึ่งพา |
|---|---|
| feature.* | core.*, Android/AndroidX |
| home | core.navigation, core.ui, Android/AndroidX |
| core.* | Android/AndroidX และ Room ตามความจำเป็น; ห้ามพึ่งพา feature |
| app composition root | core, home และ feature entry contracts เพื่อประกอบแอป |

## 5. ฐานข้อมูลกลาง

- สร้าง Room AppDatabase singleton ระดับ Application process เพียง instance เดียว
- ใช้ไฟล์ฐานข้อมูลเดียว เช่น maeyaion_pos.db; ห้ามสร้าง DB ต่อเมนู/feature
- ทุก DAO ต้องมาจาก AppDatabase instance เดียวกัน
- เปิด WAL ตอน initialize และตรวจค่าที่ SQLite คืนกลับ
- เปิด foreign keys และ busy timeout ทุก connection; ห้ามใช้ allowMainThreadQueries
- export Room schema ทุก version และเก็บ migration ใน source control; schema v4 ใช้ migration 1→2→3→4 แบบไม่ทำลายข้อมูล
- ใช้ DatabaseGuards installer ชุดเดียวกันทั้ง fresh install และ migration; Migration 3→4 refreshes guards สำหรับฐานข้อมูลที่อยู่ v3
- ห้าม destructive migration หรือแก้ปัญหาด้วยการลบฐานข้อมูล
- Entity/DAO เป็น persistence boundary; UI ห้าม query ฐานข้อมูลโดยตรง

## 6. Transaction และการไหลของข้อมูล

### 6.1 รูปแบบทั่วไป

User action
 -> Screen validation (รูปแบบข้อมูลเท่านั้น)
 -> Feature use case (กฎธุรกิจ)
 -> TransactionRunner
 -> Room DAO writes + audit/stock/summary/backup journal
 -> COMMIT
 -> ส่งผลสำเร็จกลับ UI

แสดงผลว่าสำเร็จหลัง transaction commit เท่านั้น หากขั้นตอนใดล้มเหลวต้อง rollback ข้อมูลทั้งชุดและส่ง error ที่ปลอดภัยกลับ UI

### 6.2 Transaction ที่ต้อง atomic

- สร้างบาร์โค้ดของร้าน: อ่าน next_value, ข้ามเลขที่มีอยู่แล้วใน barcode_registry ไม่ว่าจะเป็น source_type ใด, insert registry เป็น SYSTEM และเลื่อน counter ใน transaction เดียว
- รับสินค้าที่มีบาร์โค้ดโรงงาน: ลงทะเบียนเลขเดิมเป็น MANUFACTURER และผูก product ใน transaction เดียว; ห้ามสร้างเลขใหม่ทับบาร์โค้ดโรงงาน
- รับสินค้า/ปรับสต๊อก/ตรวจนับ: บันทึก stock_log และปรับยอดคงเหลือใน transaction เดียว
- ขายเงินสด/ขายเชื่อ: sale, sale_line, stock movement, stock_log, summary, audit และ backup_journal
- ชำระหนี้หลายบิล: validate ยอดค้างเต็มทุกบิลและบันทึก debt_payment แยกต่อบิลใน transaction เดียว; หากรายการใดผิดพลาดให้ rollback ทั้งชุด (D-044)
- แก้ราคาขาย: update product price และ insert price_history
- คืนสินค้า: sale_return + sale_return_line, fee allocation แบบสะสม Half Up, stock movement ตาม restock, debt reduction หรือ cash refund, summary, audit และ backup journal ใน transaction เดียว
- ยกเลิก: sale_cancel + sale_cancel_line เฉพาะจำนวนคงเหลือหลังหัก return; refund/debt reduction, fee reversal ที่เหลือ, stock movement, summary, audit และ backup journal ใน transaction เดียว
- เงินคืนบิลที่ชำระแล้วจ่ายเงินสดหน้าร้านและบันทึกผู้รับ; restock=false ต้องมีเหตุผล
- Retention purge: backup/snapshot และตรวจ summary ก่อนลบ; ห้ามลบบิล UNPAID

ห้ามเปิด transaction ค้างระหว่างรอ input, กล้อง, TTS หรือ network call ห้ามเรียก Google Drive ภายใน transaction

### 6.3 Idempotency และการป้องกันข้อมูลซ้ำ

- ทุกคำสั่งยืนยันที่สร้างธุรกรรมต้องมี UUID operation/idempotency key
- sale.idem_key มี UNIQUE constraint
- ปิด/ล็อกปุ่มยืนยันตั้งแต่เริ่มส่งคำสั่งจนได้ผล
- การ retry ทางเทคนิคต้องคืนผลของ operation เดิม ไม่สร้างบิลใหม่
- duplicate-bill check ใช้ SHA-256 จาก canonical UTF-8 JSON versioned: sale type, customer_id, SKU ที่รวมซ้ำแล้ว, qty_milli และ price_snap_satang; เรียงตาม SKU และไม่รวมเวลา/เลขบิล/เงินรับ/เงินทอน. พบ hash เดิมใน 60 นาทีให้ถามยืนยัน (D-045)

## 7. Threading และ lifecycle

- งาน Room, การคำนวณที่หนัก และ backup ทำบน background executor/WorkManager
- ห้าม block main thread
- UI สังเกตข้อมูลจาก repository และยกเลิก observer/resource ตาม lifecycle
- CameraX ต้อง bind/unbind ตาม lifecycle; ปล่อยกล้องเมื่อหน้าจอหยุดหรือแอปไป background
- TextToSpeech ต้อง initialize/release ตาม lifecycle; ใช้เสียงผู้หญิงไทยเมื่อมี, fallback เสียงไทยเริ่มต้น, หากไม่มีเสียงไทยให้แสดงข้อความแทน (D-059)
- การไม่มีอินเทอร์เน็ตต้องไม่ทำให้ขาย รับสินค้า ค้นสินค้า หรืออ่านข้อมูลในเครื่องหยุดทำงาน

## 8. Backup, restore และ retention

- ทุก transaction ที่เปลี่ยนข้อมูลเขียน backup_journal ใน transaction เดียวกัน
- Journal เป็น append-only canonical JSON UTF-8 มี sequence, operation_id, operation, schema version, timestamp และ delta ที่จำเป็น; แต่ละ record มี SHA-256
- Snapshot เป็น SQLite consistent snapshot ที่รองรับ WAL; ห้าม copy เฉพาะไฟล์ .db ขณะ WAL ยังมีข้อมูลที่ไม่ checkpoint
- บรรจุ snapshot เป็น ZIP พร้อม manifest ระบุ snapshot ID, schema version, เวลา UTC, journal sequence/checkpoint และ SHA-256
- Restore ตรวจ manifest/checksum/SQLite integrity/foreign keys ก่อน แล้ว restore snapshot และ replay journal ตาม seq ต่อเนื่องแบบ idempotent; ปฏิเสธ checksum ผิดหรือ sequence ขาด/ซ้ำ
- เก็บ snapshot ที่ตรวจผ่านหลายชุด; ห้ามลบ snapshot ล่าสุดที่กู้คืนได้ก่อน snapshot ใหม่ผ่านการตรวจ
- WorkManager ส่ง journal/snapshot ไป Google Drive ด้วย drive.appdata เมื่อมี network; backup failure ไม่ขวางการขาย
- หากรายการ backup ค้างส่งเกิน 24 ชั่วโมง ให้เตือนหน้าหลัก; offline ให้คงคิวไว้
- สร้าง daily snapshot และ snapshot ก่อน purge
- ก่อน purge ต้องสร้าง reconciliation proof แบบไม่มี PII เก็บ 15 เดือน และตรวจ summary/foreign-key/retention guards
- retention: paid sale 30 วันจาก created_at (cash) หรือ paid_at (credit); ยอดค้างจริงห้าม purge; settled by reversal เริ่มนับจากเวลา reversal; sale_return, sale_cancel, debt_payment, stock_log, price_history, op_log เก็บ 90 วัน; summary/product_daily/proof 15 เดือน; product/barcode registry ถาวร
- หาก child evidence ที่ยังอยู่ในช่วง 90 วันอ้างถึง sale/sale_line ให้เลื่อน purge parent จน child พ้น retention
- backup_journal prune ได้เมื่อส่งสำเร็จและถูกครอบคลุมด้วย snapshot ที่ตรวจแล้ว โดย recovery chain ยังสมบูรณ์
- Database ใช้ SQLCipher โดย database key ถูกป้องกันด้วย Android Keystore; backup ใช้ password-based AES-256-GCM envelope ที่ versioned ตาม D-060
- Google Drive auth ใช้ account ที่ผู้ใช้เลือกและ drive.appdata scope เท่านั้น; token/account failure ไม่ขวางการขาย (D-058)
- Restore entry point เป็น action ภายในหน้าจอจัดการร้านค้า; staging validation + safety snapshot ก่อนสลับ DB (D-061)
- ห้ามทำ sync ข้อมูลธุรกิจหรือ replication ระหว่างอุปกรณ์

## 9. ความปลอดภัยและความถูกต้อง

- เก็บ DB ใน app-private storage
- ใช้ parameterized queries; ห้ามต่อ SQL จาก input
- ตรวจสิทธิ์/กฎธุรกิจใน use case ไม่ใช่ซ่อนปุ่มอย่างเดียว
- ไม่บันทึกรหัสผ่าน, token หรือข้อมูลส่วนบุคคลเกินจำเป็นลง log
- Backup encryption + Recovery Password/Recovery Key ต้อง implement และผ่าน security tests ใน Phase 3 ก่อนเปิด Drive upload ตาม D-062; App Lock และ SQLCipher database-at-rest encryption ยังคงอยู่ Phase 10 ตาม D-060
- ห้าม hard-delete ข้อมูลการเงิน/สต๊อกเพื่อแก้รายการผิด; ใช้ reversal/audit records
- ข้อผิดพลาด DB ต้องไม่ถูกกลืนเงียบ; rollback และแจ้งข้อความที่ผู้ใช้แก้ไขได้

## 10. การทดสอบที่ผูกกับสถาปัตยกรรม

- Unit tests: Satang/fee calculation, cumulative return-fee allocation (รวมกรณี subtotal=0), milli-unit arithmetic, customer identity normalization, content hash SHA-256 canonicalization, Asia/Bangkok date grouping, barcode allocation ที่ต้องข้ามเลข MANUFACTURER/SYSTEM ที่ถูกลงทะเบียนแล้ว
- Unit tests: FeeRoundingHalfUpTest ครอบคลุมเศษต่ำกว่า/เท่ากับ/มากกว่า 0.5 สตางค์ โดยไม่ใช้ float/double
- Unit tests: BillLevelFeeTest ยืนยัน fee 5% จาก subtotal ทั้งบิลและปัด Half Up ครั้งเดียว; ReturnFeeAllocationTest ทดสอบคืนหลายครั้งแล้วไม่เกิน fee เดิมและคืนครบได้เต็ม
- Room in-memory tests: UNIQUE/FK/CHECK, triggers, DAO, transaction rollback
- Migration tests: fresh install และ upgrade จาก schema version ก่อนหน้า
- Architecture tests: ไม่มี feature-to-feature dependency และ core ไม่พึ่ง feature
- MenuLockTest: เมนูจริงตรงกับรายการที่ผู้ใช้อนุมัติ
- Integration tests: sale atomicity, idempotency, stock underflow, cash tender/change, batch debt payment atomicity, return/cancel after partial return, refund recipient/operator evidence, no-restock reason, report reconciliation, retention proof, backup/restore/replay, 24-hour backup warning
- Device tests: CameraX/ML Kit, TTS, lifecycle, WAL backup/restore และ power-loss recovery

CI ผ่านไม่เท่ากับ device acceptance; ต้องทดสอบบนมือถือจริงตามเกณฑ์ใน SPEC

## 11. สถานะการตัดสินใจและการจัดเฟส

O-001 ถึง O-030 ปิดครบแล้วตาม docs/DECISIONS.md (D-033 ถึง D-061). O-023 Google Drive authentication และ O-024 Thai TTS fallback ทำในเฟส 3; O-026 restore entry point/flow ทำในเฟส 3/4; O-025 app lock และ SQLCipher ทำในเฟส 10; backup encryption/Recovery Password/Recovery Key ทำในเฟส 3 ตาม D-062. การปิดประเด็นหมายถึงข้อกำหนดได้รับการอนุมัติ ไม่ได้หมายความว่า implementation เสร็จแล้ว.

## 12. เกณฑ์ผ่านเฟส 2

- มีเอกสาร ARCHITECTURE, SCHEMA และ MENU_LOCK ครบ
- ยืนยันได้ว่า Room/SQLite มีเพียงไฟล์เดียวและทุก feature ใช้ provider เดียว
- dependency rules ชัดเจนและไม่มี feature import feature อื่น
- เมนูหลัก 6 รายการไม่เปลี่ยน; เมนูย่อยทั้ง 20 รายการได้รับการยืนยันและล็อกไว้ใน MENU_LOCK.json แล้ว
- schema ระบุ keys, indexes, constraints, transactions, quantity scale, return/cancel ledger, reconciliation proof และ delete behavior
- O-001 ถึง O-030 แสดง RESOLVED ตาม DECISIONS; implementation ทำตามเฟสที่กำหนดใน D-058 ถึง D-061
- SPEC, DECISIONS, SCHEMA, ARCHITECTURE และ MENU_LOCK ต้องผ่าน cross-document consistency check
- ผู้ใช้ตรวจและยืนยันเอกสารก่อนเริ่มเฟส 3
