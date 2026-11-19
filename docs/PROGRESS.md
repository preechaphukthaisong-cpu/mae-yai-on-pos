# Project Progress — Maeyai-On-POS

อัปเดต: 19 พฤศจิกายน 2026

## แผนที่ใช้บังคับ

ใช้แผน 6 ช่วง (เฟส 0–5) ที่ผู้ใช้ยืนยันเมื่อ 19 พฤศจิกายน 2026 แทนลำดับเฟส 10 เฟสและแผน release-gates เดิมสำหรับการพัฒนา v1

- Android Native Java, minSdk 26, เครื่องเดียว, Offline-first
- เงินใช้ `long` หน่วยสตางค์เท่านั้น
- Room + SQLite ไฟล์เดียว และต้องเปิด WAL
- การขาย/คืน/ตัดสต๊อกต้อง atomic ใน Room transaction เดียว
- เมนูหลัก 6 รายการและลำดับคงที่: การขาย, คลังสินค้า, สร้างบาร์โค้ด, ประวัติการขาย, จัดการร้านค้า, รายงานยอดขาย
- Google Sign-In ใช้เฉพาะ backup; ไม่บังคับ login เพื่อใช้ POS
- v1 ทำ backup local และ Drive แบบพื้นฐาน; ไม่ทำ cloud sync, multi-device, unattended backup ซับซ้อน, delta replay ซับซ้อน หรือ archive/retention ขั้นสูง
- ไม่ทำระบบพิมพ์ใน v1

## สถานะเฟส

| เฟส | สถานะ | หมายเหตุ |
|---|---|---|
| 0 — ล็อกกฎ | DONE | อ่าน DECISIONS.md และ SPEC.md; ยืนยันข้อกำหนดหลักและเมนู 6 รายการ; บันทึกแผนปัจจุบันในไฟล์นี้ |
| 1 — Foundation ที่ใช้งานได้ | IN PROGRESS | ปรับ Room เป็น WAL และเพิ่มหน้าหลัก 6 เมนูแล้ว; ยังต้องทำ cash-sale ขั้นต่ำ, ทดสอบ local/Drive backup และผ่านอุปกรณ์จริง |
| 2 — ขายและคลัง | NOT STARTED | ยังไม่มี sales ledger/atomic cash-sale flow ที่ผ่านเกณฑ์ |
| 3 — ขายเชื่อ คืนสินค้า และรายงาน | NOT STARTED | รอ Phase 2 ผ่าน |
| 4 — ติดตั้งทับและเสถียรภาพ | NOT STARTED | รอ Phase 3 ผ่าน |
| 5 — ใช้จริงและปล่อย | NOT STARTED | รอ Phase 4 ผ่าน |

## ผลตรวจ baseline

Baseline ที่ตรวจ: branch `phase-3-foundation`, commit `e44e3e9293e84068dda2a123f36f2777cf178871`.

มีอยู่แล้ว:
- Android Java, package `com.maeyaion.pos`, minSdk 26
- Room schema version 4 และ migration 1→2→3→4
- `TransactionRunner` และ `RoomTransactionRunner`
- backup journal, local snapshot/restore coordinator และ encrypted backup primitives
- Google Drive upload/download worker
- CameraX + bundled ML Kit scanner source
- Thai TextToSpeech service
- Product registration/barcode allocation service และ unit/instrumentation tests บางส่วน

ช่องว่างที่ขวางการผ่าน Phase 1:
- แก้แล้วใน branch `phase-0-lock-rules`: `AppDatabase` ใช้ `JournalMode.WRITE_AHEAD_LOGGING`; ปรับ database instrumentation builders ให้ใช้ WAL
- แก้แล้วใน branch `phase-0-lock-rules`: `MainActivity` แสดงเมนู 6 รายการตามลำดับล็อก; แต่ทั้ง 6 เมนูยังเป็น placeholder
- Room schema v4 ยังไม่มี sales/receipt ledger และยังไม่มี atomic cash-sale implementation
- ต้องตรวจและทดสอบการสร้างสินค้า + สแกน + ขายเงินสด atomic, local backup/restore และ Drive upload/download บนเครื่องจริงตามเกณฑ์ Phase 1
- การมี source code หรือ unit tests ยังไม่เท่ากับผ่านการทดสอบบนมือถือจริง

## ข้อจำกัดการดำเนินงาน

- ทำทีละเฟสและหยุดเมื่อเกณฑ์ผ่านยังไม่ครบ
- ห้ามเริ่ม Phase 2 ก่อน Phase 1 ผ่าน
- ไม่ทำงานต่อใน PR #4 ของ branch `phase-3-release-gates` ตามแผน release-gates เดิม; PR #4 ยังเปิดและยังไม่ merge
- โค้ด backup ขั้นสูงที่อยู่ใน branch/PR เดิมจะไม่ถูกรวมเข้ากับแผน v1 ใหม่นี้โดยอัตโนมัติ
- เมื่อจบงานให้รายงานเพียง: เสร็จพร้อมหลักฐาน, ค้าง, และสิ่งที่ต้องให้ผู้ใช้ตัดสินใจ (ไม่เกิน 2–3 ข้อ)

## งาน Phase 1 ที่ทำแล้ว

- เปลี่ยน Room journal mode เป็น WAL โดยให้ Room จัดการ WAL lifecycle; คง busy timeout 5 วินาทีและ synchronous FULL ใน callback
- เพิ่มหน้าหลัก 6 เมนูตามลำดับที่กำหนด; Google Sign-In อยู่ในส่วนสำรองข้อมูลและไม่บังคับใช้งาน POS
- ปรับ instrumentation database builders ให้ใช้ WAL
- ยังไม่มีผล CI หลังการเปลี่ยนแปลงนี้ และยังไม่ได้ทดสอบบนมือถือจริง

## สถานะปัจจุบัน

Phase 0 DONE. Phase 1 IN PROGRESS; ยังห้ามเริ่ม Phase 2 จนกว่าเกณฑ์ Phase 1 ผ่านครบ
