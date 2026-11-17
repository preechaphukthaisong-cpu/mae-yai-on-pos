# สถาปัตยกรรมระบบ POS ร้านแม่ใหญ่อ่อน (ARCHITECTURE)

- รุ่นเอกสาร: 1.0-draft
- เฟส: 2 — ออกแบบโครงสร้าง
- สถานะ: Draft; เมนูหลักและเมนูย่อยได้รับการยืนยันและล็อกแล้ว แต่ยังมีประเด็นเปิดด้าน schema/ธุรกิจที่ต้องปิดก่อน finalize และเริ่มเฟส 3
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

เอกสารนี้เป็นแบบออกแบบ ไม่ใช่หลักฐานว่ามี Android source, Gradle project หรือ feature runtime อยู่แล้ว

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
- export Room schema ทุก version และเก็บ migration ใน source control
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
- ชำระหนี้: เปลี่ยนสถานะบิลและ insert debt_payment; การชำระหลายบิลให้รอคำตอบ O-013
- แก้ราคาขาย: update product price และ insert price_history
- คืนสินค้า/ยกเลิก: insert reversal records, stock movements, report adjustments และ audit ตามกฎที่ยืนยัน
- Retention purge: backup/snapshot และตรวจ summary ก่อนลบ; ห้ามลบบิล UNPAID

ห้ามเปิด transaction ค้างระหว่างรอ input, กล้อง, TTS หรือ network call ห้ามเรียก Google Drive ภายใน transaction

### 6.3 Idempotency และการป้องกันข้อมูลซ้ำ

- ทุกคำสั่งยืนยันที่สร้างธุรกรรมต้องมี UUID operation/idempotency key
- sale.idem_key มี UNIQUE constraint
- ปิด/ล็อกปุ่มยืนยันตั้งแต่เริ่มส่งคำสั่งจนได้ผล
- การ retry ทางเทคนิคต้องคืนผลของ operation เดิม ไม่สร้างบิลใหม่
- duplicate-bill check ใช้ content hash แยกจาก idempotency; normalization ของ hash รอ O-014

## 7. Threading และ lifecycle

- งาน Room, การคำนวณที่หนัก และ backup ทำบน background executor/WorkManager
- ห้าม block main thread
- UI สังเกตข้อมูลจาก repository และยกเลิก observer/resource ตาม lifecycle
- CameraX ต้อง bind/unbind ตาม lifecycle; ปล่อยกล้องเมื่อหน้าจอหยุดหรือแอปไป background
- TextToSpeech ต้อง initialize/release ตาม lifecycle และมี fallback เมื่อเครื่องไม่มีเสียงไทย/เสียงผู้หญิงตาม O-024
- การไม่มีอินเทอร์เน็ตต้องไม่ทำให้ขาย รับสินค้า ค้นสินค้า หรืออ่านข้อมูลในเครื่องหยุดทำงาน

## 8. Backup, restore และ retention

- ทุก transaction ที่เปลี่ยนข้อมูลเขียน backup_journal ใน transaction เดียวกัน
- WorkManager ส่ง journal/snapshot ไป Google Drive ด้วย scope drive.appdata เมื่อมี network; backup failure ไม่ขวางการขาย
- สร้าง daily snapshot และ snapshot ก่อน purge
- ห้าม copy เฉพาะไฟล์ SQLite หลักขณะ WAL ทำงาน; ใช้วิธี snapshot ที่ consistent และทดสอบ restore
- ตรวจ checksum และ integrity/foreign-key checks ก่อนยอมรับไฟล์ backup
- ห้าม purge ก่อน snapshot สำเร็จและ summary ผ่านการตรวจ
- รายละเอียด payload, sequence, checksum, encryption, recovery ordering และ backup deadline ยังเป็น blocker ตาม O-021/O-022
- ห้ามทำ sync ข้อมูลธุรกิจหรือ replication ระหว่างอุปกรณ์

## 9. ความปลอดภัยและความถูกต้อง

- เก็บ DB ใน app-private storage
- ใช้ parameterized queries; ห้ามต่อ SQL จาก input
- ตรวจสิทธิ์/กฎธุรกิจใน use case ไม่ใช่ซ่อนปุ่มอย่างเดียว
- ไม่บันทึกรหัสผ่าน, token หรือข้อมูลส่วนบุคคลเกินจำเป็นลง log
- ห้ามเพิ่ม password lock หรือ DB encryption เองก่อนเฟส 10 (O-025)
- ห้าม hard-delete ข้อมูลการเงิน/สต๊อกเพื่อแก้รายการผิด; ใช้ reversal/audit records
- ข้อผิดพลาด DB ต้องไม่ถูกกลืนเงียบ; rollback และแจ้งข้อความที่ผู้ใช้แก้ไขได้

## 10. การทดสอบที่ผูกกับสถาปัตยกรรม

- Unit tests: Satang/fee calculation, content hash, date grouping, barcode allocation ที่ต้องข้ามเลข MANUFACTURER/SYSTEM ที่ถูกลงทะเบียนแล้ว
- Room in-memory tests: UNIQUE/FK/CHECK, triggers, DAO, transaction rollback
- Migration tests: fresh install และ upgrade จาก schema version ก่อนหน้า
- Architecture tests: ไม่มี feature-to-feature dependency และ core ไม่พึ่ง feature
- MenuLockTest: เมนูจริงตรงกับรายการที่ผู้ใช้อนุมัติ
- Integration tests: sale atomicity, idempotency, stock log, debt payment, return, retention, backup/restore
- Device tests: CameraX/ML Kit, TTS, lifecycle, WAL backup/restore และ power-loss recovery

CI ผ่านไม่เท่ากับ device acceptance; ต้องทดสอบบนมือถือจริงตามเกณฑ์ใน SPEC

## 11. ข้อกำหนดที่ยังไม่ล็อก

ห้ามเดาเพื่อเริ่ม implementation: ประเด็นที่ยังเปิดตาม DECISIONS โดยเฉพาะ fee rounding/base, quantity precision, stock underflow, business date, report formula, retention/archive, backup format และ cash validation; barcode source และ collision handling ถูกกำหนดแล้วใน D-033

## 12. เกณฑ์ผ่านเฟส 2

- มีเอกสาร ARCHITECTURE, SCHEMA และ MENU_LOCK ครบ
- ยืนยันได้ว่า Room/SQLite มีเพียงไฟล์เดียวและทุก feature ใช้ provider เดียว
- dependency rules ชัดเจนและไม่มี feature import feature อื่น
- เมนูหลัก 6 รายการไม่เปลี่ยน; เมนูย่อยจะ lock หลังผู้ใช้ยืนยัน
- schema ระบุ keys, indexes, constraints, transactions และ delete behavior
- ไม่มีการตัดสินใจแทนผู้ใช้ในประเด็นเปิด; O-001 และ O-002 ต้องแสดงเป็น RESOLVED ตาม DECISIONS
- ผู้ใช้ตรวจและยืนยันเอกสารก่อนเริ่มเฟส 3
