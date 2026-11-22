# แผนปิดงานและส่งมอบ Maeyai-On-POS

- วันที่จัดทำ: 22 พฤศจิกายน 2026
- สถานะ: แผนดำเนินงานที่ผู้ใช้กำหนดให้บันทึกใน GitHub
- เป้าหมายหลัก: ส่งมอบ POS v1.0 ที่ทำงานถูกต้อง ปลอดภัย สำรอง/กู้คืนได้ และอัปเดตทับรุ่นเดิมโดยไม่สูญเสียข้อมูล
- หลักการ: วัดความสำเร็จจากผลการทดสอบและการใช้งานจริง ไม่ใช่จากการมีไฟล์หรือคลาสใน repository

> กำหนดการเป็นเป้าหมาย ไม่ใช่การรับรองวันส่งมอบ หากเกณฑ์ตรวจสอบใดไม่ผ่าน ให้แก้ไขและเลื่อนการส่งมอบ ห้ามลดข้อกำหนดเพื่อให้ทันกำหนด

## 1. เอกสารและกฎที่ต้องยึดถือ

เอกสารหลักของโครงการ:
- [SPEC.md](SPEC.md) — ข้อกำหนดการทำงานและขอบเขต POS v1
- [DECISIONS.md](DECISIONS.md) — การตัดสินใจที่ผู้ใช้ยืนยัน
- [ARCHITECTURE.md](ARCHITECTURE.md) — สถาปัตยกรรมระบบ
- [SCHEMA.md](SCHEMA.md) — แบบฐานข้อมูล
- [MENU_LOCK.json](MENU_LOCK.json) — ล็อกเมนูหลัก 6 เมนู
- [UPDATE_COMPATIBILITY_POLICY.md](UPDATE_COMPATIBILITY_POLICY.md) — กฎการอัปเดตและความเข้ากันได้

หากเอกสารขัดแย้งกัน ให้ยึด SPEC.md และ DECISIONS.md เป็นหลัก และหยุดเพื่อทบทวนก่อนเปลี่ยนข้อกำหนดที่ผู้ใช้อนุมัติแล้ว

## 2. กฎควบคุมการพัฒนาและอัปเดต

1. ต้องรักษา applicationId `com.maeyaion.pos` และ Release Keystore/ลายเซ็นเดิม
2. ทุก Release ต้องเพิ่ม `versionCode`; ห้ามลด versionCode
3. ต้องติดตั้งอัปเดตทับแอปเดิมได้ ห้ามบังคับถอนการติดตั้ง ล้างข้อมูล หรือ destructive migration
4. การเปลี่ยน schema ต้องมี Room migration ที่รองรับฐานข้อมูลเดิม และทดสอบ migration จริง
5. ต้องสำรองข้อมูลก่อนการดำเนินการที่มีความเสี่ยงต่อข้อมูล และพิสูจน์ restore/rollback ได้
6. ต้องตรวจ APK ที่ลงนามแล้วและทดสอบ update-in-place บนเครื่องจริง
7. Build ผ่านไม่ได้แปลว่าระบบผ่าน ต้องตรวจผล test jobs ว่าทำงานจริงและผ่านทั้งหมด
8. ห้าม Merge PR, สร้าง Release tag หรือเผยแพร่ APK โดยไม่มีคำสั่งอนุมัติจากผู้ใช้
9. ห้ามเปิด Live Sync ก่อนผ่าน security และ data-integrity gates และได้รับอนุมัติ
10. ห้ามสร้างทรัพยากร Cloud ที่มีค่าใช้จ่ายโดยไม่แจ้งค่าใช้จ่ายและได้รับอนุมัติก่อน
11. ห้ามเปลี่ยนข้อกำหนดทางธุรกิจ ความถูกต้องของข้อมูล หรือความปลอดภัยเพื่อเร่งกำหนดส่ง
12. วิธีการและเทคโนโลยีพัฒนาเปลี่ยนได้ตามความเหมาะสม แต่ผลลัพธ์ที่กำหนดไว้ต้องไม่ลดลง

## 3. ขอบเขต v1 และระบบหลายเครื่อง

POS v1 เป็น Android เครื่องเดียวแบบ offline-first โดย Room/SQLite ภายในเครื่องเป็นแหล่งข้อมูลหลัก และ Google Drive ใช้เก็บไฟล์สำรองที่เข้ารหัสเท่านั้น

ระบบ Backend/Cloud Sync/หลายเครื่องเป็นงานระยะถัดไป แยกจากการส่งมอบ v1 ห้ามนำ Sync runtime มาเป็นเงื่อนไขให้การขายบนเครื่องเดียวต้องพึ่งอินเทอร์เน็ต และห้ามเปิดใช้งานจริงก่อนผ่านเกณฑ์ความปลอดภัยและความถูกต้องครบถ้วน

## 4. รายการงานค้าง

### 4.1 ปิดปัญหา CI และฐานข้อมูลที่ขวางงาน

จากผล Android Emulator Instrumentation ของ PR #20 ที่ตรวจพบ ณ 22 พฤศจิกายน 2026 มี 11 tests ล้มเหลวในกลุ่มปัญหาต่อไปนี้:
- Backup Restore integrity/schema validation
- Restore rollback คาดหวัง schema v6 แต่ได้ v7
- Store Encryption Key creation ล้มเหลวหลาย tests
- Debt Payment Replay ติด SQLite trigger `sale_invalid`
- Inventory Replay ติด SQLite trigger `product_invalid_initial_state`

งาน:
- วิเคราะห์ root cause จาก test และ workflow logs
- แก้ schema/version compatibility และ restore rollback
- แก้ key creation/protection lifecycle
- แก้ domain replay และ trigger invariants โดยไม่ลดความเข้มงวดของกฎฐานข้อมูล
- รัน Android Unit Tests, Instrumentation Tests, Room Schema Export และ Backend CI ใหม่
- ยืนยันว่าทุก test job ที่จำเป็นได้ execute จริงและผ่าน

**Gate:** Android Instrumentation Tests และ CI ที่เกี่ยวข้องต้องผ่านทั้งหมดก่อนรับงานส่วนนี้

### 4.2 POS ทั้ง 6 เมนู

ทุกเมนูมี implementation บางส่วน แต่ยังไม่ถือว่าสมบูรณ์จนกว่าจะผ่าน end-to-end tests และตรวจผลลัพธ์บนเครื่องจริง

1. **การขาย**
   - เงินสด/เงินเชื่อ, คำนวณ subtotal/fee/total/เงินทอน
   - ค่าบริการเครดิต 5% และการปัด Half Up ระดับบิล
   - ตัดสต็อก, บันทึก audit/backup journal แบบ atomic
   - idempotency, duplicate bill, duplicate taps และ retry

2. **คลังสินค้า**
   - เพิ่มสินค้า/รับสินค้า/ปรับสต็อก/ตรวจนับ
   - stock log ครบทุกการเปลี่ยนแปลง
   - ป้องกัน stock underflow และการขายเมื่อสต็อกเป็นศูนย์
   - ตรวจ stock limit และแจ้งเตือนสินค้าต่ำ

3. **สร้างบาร์โค้ด**
   - Code 128, ทะเบียน barcode และการป้องกันเลขซ้ำ
   - ทดสอบการจองเลขพร้อมกันและการ rollback counter
   - ทดสอบ export ภาพ/PDF

4. **ประวัติการขาย**
   - รายการบิลและรายละเอียด
   - คืนสินค้าบางส่วน/ทั้งหมด และยกเลิกหลังคืนบางส่วน
   - คืนเงินสด/ลดหนี้ตามสถานะบิล
   - ตรวจ audit, stock movement และยอดคงเหลือย้อนหลัง

5. **จัดการร้านค้า**
   - ตรวจข้อมูลร้านค้าและลูกค้า
   - ตรวจการแก้ไขราคาและ price history
   - ตรวจการตั้งค่าและเครื่องมือที่ต้องอยู่ภายในเมนูนี้
   - ตรวจการทำงานเชื่อมโยงกับเมนูอื่น

6. **รายงานยอดขาย**
   - รายวัน/รายสัปดาห์/รายเดือน/ช่วงวันที่
   - สินค้าขายดี 20 อันดับและขายน้อยสุด 20 อันดับ
   - ตรวจยอดหลังคืนสินค้า/ยกเลิก
   - ไม่รวมการรับชำระหนี้เป็นยอดขายใหม่

**Gate:** ทุก workflow สำคัญต้องผ่าน end-to-end tests และยอดเงิน/สต็อกต้องตรงกับ ledger และหลักฐานธุรกรรม

### 4.3 ความปลอดภัย

- ออกแบบและติดตั้ง SQLCipher สำหรับฐานข้อมูล POS ในเครื่อง
- วางแผนย้ายฐานข้อมูล SQLite เดิมไปฐานข้อมูลเข้ารหัสโดยไม่สูญเสียข้อมูล
- เพิ่ม PIN/App Lock โดยไม่เก็บ PIN แบบ plaintext
- จัดการ database key ผ่าน Android Keystore
- ตรวจ backup encryption, recovery password/key และ key lifecycle
- ตรวจการรั่วของข้อมูลใน log, temporary files และ external storage
- ทดสอบ key loss, tampering, corrupted ciphertext และ recovery

**Gate:** ห้ามเปิดใช้ encryption migration กับข้อมูลจริงจนกว่าจะพิสูจน์ backup, restore, rollback และ update compatibility ได้ครบ

### 4.4 Backup และ Restore

- Full snapshot รายวัน
- Backup Journal/delta และ durable upload cursor/reconciliation
- Google Drive appDataFolder upload/download/list และ OAuth/account selection
- WorkManager retry/constraints และ offline queue
- เก็บ backup หลายชุดและตรวจ checksum/integrity
- Restore จากไฟล์ในเครื่องและ Google Drive
- staged restore, integrity_check, foreign_key_check และ rollback เมื่อ restore ล้มเหลว
- Backup ล้มเหลวต้องไม่ขัดขวางการขาย
- แจ้งเตือน backup ค้างเกิน 24 ชั่วโมง
- ทดสอบบนโทรศัพท์จริง รวมถึง process death, power loss และพื้นที่หน่วยความจำจำกัด
- ออกแบบ crypto/file I/O ไม่ materialize payload ขนาดใหญ่ทั้งหมดใน memory โดยไม่จำเป็น

**Gate:** ต้องกู้คืนข้อมูลได้จริงบนเครื่องทดสอบ โดยฐานข้อมูลเดิมยังปลอดภัยหาก restore ไม่สำเร็จ

### 4.5 แจ้งเตือนและ Retention

- แจ้งเตือนสินค้าต่ำกว่าหรือเท่ากับ stock limit
- แจ้งเตือนหนี้ค้างเกิน 30 × 24 ชั่วโมงตามกฎใน DECISIONS.md
- แจ้งเตือน backup ค้างเกิน 24 ชั่วโมง
- ทำ retention ตามอายุข้อมูลที่อนุมัติ
- ห้ามลบบิลที่ยังมียอดค้างจริง
- ก่อน purge ต้องสร้าง snapshot และ reconciliation proof
- ตรวจ summary, foreign keys และ retention guards ก่อนลบ
- ห้ามลบข้อมูลเพียงเพราะพื้นที่จัดเก็บเต็ม

**Gate:** ทดสอบทั้งกรณีลบได้และลบไม่ได้ รวมถึงพิสูจน์ว่าหนี้ค้างและหลักฐานที่ต้องเก็บไม่ถูกลบ

### 4.6 คุณภาพ ประสิทธิภาพ และเครื่องจริง

- ทดสอบ CameraX + ML Kit bundled model แบบออฟไลน์
- ทดสอบ Thai TextToSpeech และเสียงที่อุปกรณ์รองรับ
- ทดสอบ lifecycle เมื่อจอดับ แอปถูกปิด และเปิดกลับ
- ทดสอบ 10,000 products และ 100,000 sale rows
- ตรวจ performance, memory, database durability และการทำงานต่อเนื่อง
- ทดสอบ Backup/Restore และ update-in-place บนโทรศัพท์จริง
- ทดลองใช้งานคู่ขนานกับวิธีเดิม 1–2 สัปดาห์ และกระทบยอดทุกวัน

### 4.7 เอกสารและสถานะโครงการ

- ปรับ implementation-status.md ให้ตรงกับ branch, commit, schema และ CI ล่าสุด
- ปรับ SCHEMA.md ให้ตรงกับ Room schema ที่ใช้งานจริงและ migration history
- แยกสถานะ implemented / tested / accepted / released ให้ชัดเจน
- บันทึกหลักฐาน test run และอุปกรณ์ทดสอบ
- ระบุรายการที่ยังไม่ทำและรายการที่อยู่นอกขอบเขต v1

## 5. กำหนดการเป้าหมาย

วันที่เป็นเป้าหมายการทำงานตามแผน ณ 22 พฤศจิกายน 2026 ไม่ใช่การอนุญาตให้ข้าม gate หรือรับรองวันเสร็จ

| ช่วงเวลา | เป้าหมาย | ผลส่งมอบ/เกณฑ์ผ่าน |
|---|---|---|
| 22–24 พ.ย. 2026 | ปิดปัญหา DB/Restore/Instrumentation | Instrumentation และ CI ที่เกี่ยวข้องผ่านทั้งหมด |
| 25–27 พ.ย. 2026 | ความปลอดภัยและ Backup/Restore | Encryption migration plan, key lifecycle, Drive restore และ rollback ผ่านการทดสอบ |
| 28 พ.ย.–2 ธ.ค. 2026 | ปิด workflow POS 6 เมนู | End-to-end tests, เงิน/หนี้/สต็อก/รายงานถูกต้อง |
| 3–5 ธ.ค. 2026 | แจ้งเตือนและ Retention | Alert และ retention/reconciliation tests ผ่าน |
| 6–8 ธ.ค. 2026 | Performance และเครื่องจริง | 10k products, 100k sale rows และ device acceptance ผ่าน |
| 9–10 ธ.ค. 2026 | เตรียม Release Candidate v1.0 | CI ผ่าน, signed APK, update-in-place และข้อมูลเดิมครบ |
| 11–24 ธ.ค. 2026 | ทดลองใช้งานจริง | ใช้คู่ขนาน 1–2 สัปดาห์และกระทบยอดรายวัน |
| หลัง 24 ธ.ค. 2026 | งานหลายเครื่องระยะถัดไป | PR #17/#20 ผ่าน security, replay, recovery และ conflict gates ก่อนเปิด Live Sync |

หากงานช่วงใดไม่ผ่าน ให้เลื่อนงานที่พึ่งพางานนั้น ไม่เริ่มเฟสถัดไปด้วยการข้าม acceptance criteria

## 6. งานหลายเครื่องระยะถัดไป

PR ที่เกี่ยวข้อง ณ วันที่จัดทำ:
- PR #17 — Backend multi-device foundation: https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/pull/17
- PR #20 — Android sync transport: https://github.com/preechaphukthaisong-cpu/mae-yai-on-pos/pull/20

งานที่ยังต้องทำ:
- typed domain replay ให้ครบทุก business domain
- key enrollment/recovery/rotation/revocation
- encrypted payload only; Backend ห้ามเห็น plaintext ธุรกรรม
- baseline snapshot export/import, schema validation, checksum และ atomic restore
- idempotent push/pull, durable acknowledgements และ retry
- conflict quarantine และ user-reviewed resolution
- ทดสอบ offline → online, duplicate delivery, device revocation และ key loss
- ประเมินต้นทุน Cloud Run/Cloud SQL/Secret Manager ก่อนสร้าง resource ที่คิดค่าใช้จ่าย

ห้าม merge PR หรือเปิด Live Sync โดยไม่มีการอนุมัติและหลักฐานผ่าน gate

## 7. Release Acceptance Checklist

- [ ] CI jobs ที่จำเป็นทั้งหมดผ่านและมีหลักฐาน run ล่าสุด
- [ ] Unit, Instrumentation, Migration และ database integrity tests ผ่าน
- [ ] POS ทั้ง 6 เมนูผ่าน end-to-end acceptance
- [ ] เงินทุกจำนวนใช้ integer satang; ไม่มี floating-point money
- [ ] ธุรกรรมการเงินและสต็อก atomic, idempotent และ audited
- [ ] คืน/ยกเลิก/รับชำระหนี้และรายงานถูกต้อง
- [ ] SQLCipher/PIN และ key management ผ่าน security tests
- [ ] Backup encryption, Drive upload/download และ restore ผ่าน
- [ ] Restore rollback ไม่ทำลายฐานข้อมูลเดิม
- [ ] Notifications และ retention/reconciliation ผ่าน
- [ ] Scanner และ Thai TTS ผ่าน physical-device tests
- [ ] Performance 10,000 products / 100,000 sale rows ผ่าน
- [ ] Signed APK ใช้ applicationId และ Release Keystore เดิม
- [ ] versionCode เพิ่มขึ้นและติดตั้งทับได้โดยไม่สูญเสียข้อมูล
- [ ] ทดลองใช้งานคู่ขนาน 1–2 สัปดาห์และกระทบยอดรายวัน
- [ ] เอกสารสถานะและ schema ตรงกับ implementation จริง
- [ ] ผู้ใช้ตรวจและอนุมัติก่อน merge/release

## 8. ข้อห้ามในการปิดงาน

- ห้ามนับงานว่าเสร็จจากการมี source code เพียงอย่างเดียว
- ห้ามข้ามหรือปิด test ที่ล้มเหลวโดยไม่แก้ root cause
- ห้าม destructive migration, uninstall หรือ clear app data เพื่อแก้ปัญหา
- ห้ามเปลี่ยน applicationId หรือ signing identity
- ห้ามเปิด Live Sync ก่อนผ่าน security/data-integrity gates
- ห้ามสร้าง Cloud resources ที่มีค่าใช้จ่ายโดยไม่แจ้งและได้รับอนุมัติ
- ห้าม merge PR, tag หรือ release APK โดยไม่มีคำสั่งอนุมัติ
- ห้ามลด scope ทางธุรกิจ ความปลอดภัย หรือความถูกต้องของข้อมูลเพื่อให้ทันวันที่

---
แผนนี้ใช้ร่วมกับ SPEC.md, DECISIONS.md และ UPDATE_COMPATIBILITY_POLICY.md ไม่ได้แทนที่เอกสารข้อกำหนดเหล่านั้น
