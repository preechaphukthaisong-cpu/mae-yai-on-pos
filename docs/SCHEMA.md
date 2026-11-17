# แบบฐานข้อมูล POS ร้านแม่ใหญ่อ่อน (SCHEMA)

- รุ่นเอกสาร: 1.0-draft
- เฟส: 2 — ออกแบบโครงสร้าง
- สถานะ: Draft; schema นี้ยังไม่ใช่ Room entities หรือ migration ที่รันได้
- Database: SQLite ไฟล์เดียวผ่าน Room
- เงิน: INTEGER หน่วยสตางค์; Java ใช้ long
- อ้างอิง: docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md

## 1. กติกากลาง

1. ชื่อตาราง snake_case; primary key ของเอกสารใช้ UUID เก็บเป็น TEXT เว้นแต่ระบุเป็นอย่างอื่น
2. เงินทุกคอลัมน์ลงท้าย _satang, SQLite INTEGER, CHECK >= 0; ห้าม REAL/FLOAT/DOUBLE
3. เวลาเก็บเป็น ISO-8601 UTC; การแปลงเป็นวันท้องถิ่นและเวลาตัดวันรายงานรอ O-010
4. Foreign key เปิดทุก connection; ใช้ ON DELETE RESTRICT เป็นค่าเริ่มต้น ห้าม CASCADE ลบประวัติ
5. ตาราง ledger/audit เป็น append-only; แก้ข้อผิดพลาดด้วย reversal record
6. UI ส่ง identifier และ input เท่านั้น; service ต้องอ่านข้อมูลจริงจาก DB และคำนวณยอดใหม่
7. รายละเอียด precision ของ quantity รอ O-019; คอลัมน์ qty ที่แสดงในเอกสารนี้เป็น INTEGER ชั่วคราวและห้ามสร้าง migration จริงก่อนยืนยัน
8. สถานะการชำระเงินแยกจากสถานะการยกเลิก/คืนสินค้า

## 2. ตารางและคีย์

### 2.1 product

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| sku | TEXT | PK, NOT NULL |
| barcode | TEXT | NOT NULL, UNIQUE, FK → barcode_registry.barcode, RESTRICT |
| name | TEXT | NOT NULL |
| price_satang | INTEGER | NOT NULL, CHECK >= 0 |
| cost_satang | INTEGER | NOT NULL, CHECK >= 0 |
| unit | TEXT | NOT NULL |
| stock_limit_qty | INTEGER | NULL ได้; ค่าเริ่มต้น/ความหมาย 0 หรือ NULL รอ O-020 |
| stock_qty | INTEGER | NOT NULL DEFAULT 0; cache ที่ stock_log trigger ดูแล |
| is_active | INTEGER | NOT NULL DEFAULT 1, CHECK IN (0,1) |
| created_at_utc | TEXT | NOT NULL |
| updated_at_utc | TEXT | NOT NULL |

ห้ามลบสินค้าเชิงกายภาพเมื่อมีประวัติ; ใช้ is_active=0 เมื่อเลิกขาย การแก้ราคาเพิ่ม price_history ทุกครั้ง ห้ามแก้ stock_qty จาก UI/repository โดยตรง

### 2.2 barcode_registry

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| barcode | TEXT | PK, NOT NULL; เก็บค่าเป็น TEXT ตามที่สแกน/สร้าง รวมเลขศูนย์นำหน้า |
| display_name | TEXT | NOT NULL |
| price_satang | INTEGER | NOT NULL, CHECK >= 0 |
| source_type | TEXT | NOT NULL, CHECK IN ('SYSTEM','MANUFACTURER') |
| created_at_utc | TEXT | NOT NULL |

เก็บถาวรและห้ามนำเลขเดิมกลับมาใช้ สินค้าทุกชิ้นอ้างอิง barcode ที่ลงทะเบียนแล้ว หากรับสินค้าที่มีบาร์โค้ดโรงงาน ให้ลงทะเบียนเลขเดิมพร้อม source_type='MANUFACTURER'; หากไม่มีบาร์โค้ดโรงงาน ให้ใช้ตัวสร้างเลขภายในพร้อม source_type='SYSTEM' ตาม D-033 ห้ามแปลง barcode เป็นตัวเลขหรือทิ้งเลขศูนย์นำหน้า

### 2.3 barcode_counter

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | INTEGER | PK, CHECK id=1 |
| next_value | INTEGER | NOT NULL, CHECK >= 2000001 |

ต้องมี singleton row id=1, next_value=2000001 โดย next_value คือเลขผู้สมัครถัดไปของระบบ ห้ามให้ UI กำหนดเลขเอง การจองเลขต้องทำภายใน transaction เดียวตามลำดับ:
1. อ่าน next_value แล้วแปลงเป็นข้อความ barcode
2. ตรวจว่ามี barcode ตรงกันใน barcode_registry หรือไม่
3. ถ้ามีอยู่แล้ว ให้ข้ามเลขนั้น เลื่อน next_value แล้วตรวจเลขถัดไป ทำซ้ำจนพบเลขว่าง
4. insert barcode_registry ด้วย source_type='SYSTEM' และเลื่อน next_value ไปเลขถัดไป
5. หาก insert หรือการปรับ counter ล้มเหลว ให้ rollback ทั้ง transaction

การตรวจและ insert ต้องอยู่ใน transaction เดียวเพื่อให้การเรียกพร้อมกันไม่สามารถจองเลขเดียวกันได้ เลขโรงงานที่ตรงกับเลขผู้สมัครจะถูกข้ามเช่นเดียวกับเลขที่ระบบเคยสร้างแล้ว

### 2.4 customer

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| name | TEXT | NOT NULL |
| name_key | TEXT | NOT NULL, UNIQUE |
| created_at_utc | TEXT | NOT NULL |
| updated_at_utc | TEXT | NOT NULL |

name_key ใช้ตรวจชื่อซ้ำ แต่กฎ normalize ช่องว่าง/Unicode/ตัวพิมพ์และกรณีบุคคลชื่อซ้ำยังรอ O-018 ต้องยืนยันก่อนสร้าง unique index ใน migration จริง

### 2.5 sale

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| idem_key | TEXT | NOT NULL, UNIQUE |
| type | TEXT | NOT NULL, CHECK IN ('CASH','CREDIT') |
| payment_status | TEXT | NOT NULL, CHECK IN ('PAID','UNPAID') |
| lifecycle_status | TEXT | NOT NULL, CHECK IN ('COMPLETED','CANCELLED') |
| customer_id | TEXT | NULL สำหรับเงินสด; CREDIT ต้องมีค่า; FK customer.id RESTRICT |
| subtotal_satang | INTEGER | NOT NULL, CHECK >= 0 |
| fee_total_satang | INTEGER | NOT NULL, CHECK >= 0 |
| total_satang | INTEGER | NOT NULL, CHECK total = subtotal + fee_total |
| cash_received_satang | INTEGER | NULL ได้; CHECK >= 0 เมื่อมีค่า |
| change_satang | INTEGER | NULL ได้; CHECK >= 0 เมื่อมีค่า |
| content_hash | TEXT | NOT NULL; index ไม่ unique |
| created_at_utc | TEXT | NOT NULL |
| paid_at_utc | TEXT | NULL จนกว่าจะชำระ; CASH กำหนดใน transaction ขาย |

กฎ: CASH เริ่ม PAID และไม่มี fee; CREDIT ต้องมี customer และเริ่ม UNPAID; การชำระหนี้เต็มบิลเปลี่ยน payment_status เป็น PAID พร้อม paid_at_utc และ debt_payment ใน transaction เดียวกัน การยกเลิกเปลี่ยน lifecycle_status เป็น CANCELLED โดยไม่เขียนทับ payment_status

สถานะคืนสินค้าไม่ใช้แทน payment_status/lifecycle_status; คำนวณจากยอด/จำนวนใน sale_return_line หรือ view แยก การกำหนดสถานะคืนครบทั้งบิลรอ O-007

### 2.6 sale_line

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sale_id | TEXT | NOT NULL, FK sale.id RESTRICT |
| line_no | INTEGER | NOT NULL, CHECK > 0 |
| sku | TEXT | NOT NULL, FK product.sku RESTRICT |
| name_snap | TEXT | NOT NULL |
| price_snap_satang | INTEGER | NOT NULL, CHECK >= 0 |
| cost_snap_satang | INTEGER | NOT NULL, CHECK >= 0 |
| qty | INTEGER | NOT NULL, CHECK > 0; precision รอ O-019 |
| line_total_satang | INTEGER | NOT NULL, CHECK >= 0 |

UNIQUE(sale_id, line_no) เก็บ snapshot ชื่อ/ราคา/ต้นทุน ณ เวลาขาย ห้ามแก้ snapshot หลัง commit

ค่าบริการ 5% คำนวณจาก subtotal_satang ของทั้งบิลและปัดเศษครั้งเดียวแบบ Half Up ตาม D-034/D-035; เก็บผลไว้ใน sale.fee_total_satang เท่านั้น ไม่เก็บ fee ใน sale_line ใช้ integer arithmetic เท่านั้น โดย 5% เท่ากับหาร 20: quotient = subtotal_satang / 20, remainder = subtotal_satang % 20, แล้วบวก 1 สตางค์เมื่อ remainder >= 10

### 2.7 debt_payment

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sale_id | TEXT | NOT NULL, UNIQUE, FK sale.id RESTRICT |
| amount_satang | INTEGER | NOT NULL, CHECK > 0 |
| paid_at_utc | TEXT | NOT NULL |

หนึ่งบิลรับชำระเต็มจำนวนได้ครั้งเดียว ไม่รองรับ partial payment; ต้องตรวจ amount เท่ากับยอดค้างจริงใน service transaction เงินชำระหนี้ไม่เพิ่มยอดขาย การชำระหลายบิลพร้อมกัน atomic แบบใดรอ O-013

### 2.8 sale_return และ sale_return_line

sale_return:

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sale_id | TEXT | NOT NULL, FK sale.id RESTRICT |
| reason | TEXT | NOT NULL; เงื่อนไขบังคับเมื่อไม่ restock รอ O-029 |
| refund_satang | INTEGER | NOT NULL, CHECK >= 0 |
| created_at_utc | TEXT | NOT NULL |

sale_return_line:

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| return_id | TEXT | NOT NULL, FK sale_return.id RESTRICT |
| sale_line_id | TEXT | NOT NULL, FK sale_line.id RESTRICT |
| qty | INTEGER | NOT NULL, CHECK > 0; precision รอ O-019 |
| value_satang | INTEGER | NOT NULL, CHECK >= 0 |
| fee_satang | INTEGER | NOT NULL, CHECK >= 0 |
| restock | INTEGER | NOT NULL, CHECK IN (0,1) |

ห้ามคืนเกินจำนวนที่ขายลบจำนวนที่คืนก่อนหน้า ต้องตรวจใน transaction และมี trigger/guard ระดับ DB เพิ่มเติมเมื่อ finalize schema การคืนจากบิลเชื่อค้างลดหนี้ด้วยมูลค่าสินค้าบวก fee; บิลที่ชำระแล้วคืนเงินสดตามกฎที่ยืนยัน ค่าบริการระดับบิลคำนวณจาก subtotal_satang และปัดครั้งเดียวแบบ Half Up ตาม D-034/D-035; วิธีจัดสรร/คืน fee เมื่อคืนหลายครั้ง, ยกเลิกหลังคืนบางส่วน และวิธีคืนเงินรอ O-005/O-006/O-017/O-030

### 2.9 stock_count และ stock_count_line

เพิ่มเป็นเอกสารประกอบการตรวจนับ เพื่อค้นย้อนหลังและเชื่อมกับ stock_log

- stock_count: id TEXT PK, created_at_utc TEXT NOT NULL, note TEXT NULL
- stock_count_line: id TEXT PK, stock_count_id TEXT FK RESTRICT, sku TEXT FK RESTRICT, system_qty INTEGER NOT NULL, counted_qty INTEGER NOT NULL, diff_qty INTEGER NOT NULL, UNIQUE(stock_count_id, sku)

การแทนที่ยอดคงเหลือต้องสร้าง stock_log ต่อ SKU ใน transaction เดียวกับบันทึกเอกสารตรวจนับ กติกา quantity precision รอ O-019

### 2.10 stock_log

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sku | TEXT | NOT NULL, FK product.sku RESTRICT |
| before_qty | INTEGER | NOT NULL |
| after_qty | INTEGER | NOT NULL |
| diff_qty | INTEGER | NOT NULL, CHECK after_qty - before_qty = diff_qty |
| reason | TEXT | NOT NULL, CHECK IN ('RECEIVE','SALE','RETURN','CANCEL','COUNT_REPLACE') |
| ref_id | TEXT | NULL ได้; อ้างอิง sale/return/count operation |
| created_at_utc | TEXT | NOT NULL |

Append-only; index (sku, created_at_utc) และ (ref_id) ทุกการเปลี่ยน stock ต้องมีหนึ่งแถวใน stock_log Trigger เมื่อ insert ต้องตรวจ before_qty เท่ากับ product.stock_qty แล้วปรับ cache stock_qty เป็น after_qty ห้าม update/delete stock_log

การบล็อกจำนวนขายมากกว่าสต๊อกคงเหลือเมื่อ stock ยังมากกว่า 0 รอ O-008; ห้าม finalize CHECK/trigger ที่เลือกนโยบายแทนผู้ใช้

### 2.11 price_history

- id TEXT PK UUID
- sku TEXT NOT NULL FK product.sku RESTRICT
- old_price_satang INTEGER NOT NULL CHECK >= 0
- new_price_satang INTEGER NOT NULL CHECK >= 0
- changed_at_utc TEXT NOT NULL

Append-only; index (sku, changed_at_utc); update/delete ไม่อนุญาต

### 2.12 daily_summary

- business_date TEXT PRIMARY KEY (รูปแบบ YYYY-MM-DD)
- cash_sales_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- credit_sales_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- returns_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- fees_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- updated_at_utc TEXT NOT NULL

เก็บ 15 เดือนตาม SPEC; สูตรยอดสุทธิและผลของการคืน/ยกเลิก/fee ต่อแต่ละตัวเลขรอ O-015 และ timezone/เวลาตัดวันรอ O-010

### 2.13 product_daily

- business_date TEXT NOT NULL
- sku TEXT NOT NULL FK product.sku RESTRICT
- qty INTEGER NOT NULL
- revenue_satang INTEGER NOT NULL CHECK >= 0
- PRIMARY KEY (business_date, sku)

เก็บ 15 เดือน; นิยามสินค้าขายน้อยสุด 20 อันดับรอ O-016

### 2.14 op_log

- id TEXT PK UUID
- action TEXT NOT NULL
- entity_type TEXT NOT NULL
- entity_id TEXT NULL
- detail_json TEXT NULL
- created_at_utc TEXT NOT NULL

เก็บ 3 เดือนตาม SPEC; append-only; index created_at_utc และ (entity_type, entity_id)

### 2.15 backup_journal

- seq INTEGER PRIMARY KEY AUTOINCREMENT
- operation_id TEXT NOT NULL UNIQUE
- op TEXT NOT NULL
- payload TEXT NOT NULL
- created_at_utc TEXT NOT NULL
- uploaded INTEGER NOT NULL DEFAULT 0 CHECK IN (0,1)

insert ภายใน transaction เดียวกับ business write; index (uploaded, seq) เพื่อส่งตามลำดับ ห้าม backup failure rollback ธุรกรรมที่ commit แล้ว รูปแบบ payload, checksum, encryption, retention, recovery ordering และ checkpoint รอ O-021; backup deadline รอ O-022

## 3. Index ที่ต้องมี

- product(barcode) UNIQUE, product(name), product(is_active, stock_qty)
- barcode_registry(barcode) PRIMARY KEY
- customer(name_key) UNIQUE
- sale(idem_key) UNIQUE, sale(created_at_utc), sale(payment_status, created_at_utc), sale(customer_id, payment_status), sale(content_hash, created_at_utc)
- sale_line(sale_id, line_no) UNIQUE, sale_line(sku)
- debt_payment(sale_id) UNIQUE, debt_payment(paid_at_utc)
- sale_return(sale_id, created_at_utc), sale_return_line(sale_line_id)
- stock_log(sku, created_at_utc), stock_log(ref_id)
- price_history(sku, changed_at_utc)
- product_daily(business_date, sku), op_log(created_at_utc)
- backup_journal(uploaded, seq)

## 4. Constraints, triggers และ delete behavior

### บังคับในฐานข้อมูล

- UNIQUE: barcode, name_key, idem_key, debt_payment.sale_id, barcode_counter.id singleton, line numbers
- CHECK: เงินไม่ติดลบ, boolean เป็น 0/1, enum อยู่ในค่าที่กำหนด, line number และ qty เป็นบวก (qty precision ยังรอ O-019)
- FK: เปิด foreign_keys ทุก connection; ความสัมพันธ์ประวัติใช้ RESTRICT
- Trigger: ห้าม DELETE sale ที่ payment_status='UNPAID'; ห้าม UPDATE/DELETE ledger/audit/registry; stock_log insert เป็นทางเดียวที่เปลี่ยน cached stock_qty
- ห้ามใช้ ON DELETE CASCADE กับ sale, sale_line, debt_payment, sale_return, stock_log, price_history หรือ audit evidence

### นโยบายลบ

| ข้อมูล | นโยบาย |
|---|---|
| sale ที่ UNPAID | ห้ามลบทุกกรณี |
| sale ที่ PAID | retention 30 วันหลัง paid_at ตาม SPEC; purge ได้เมื่อ snapshot/summary/audit guard ผ่านเท่านั้น |
| daily_summary, product_daily | เก็บ 15 เดือน |
| op_log | เก็บ 3 เดือน |
| product, barcode_registry | เก็บถาวร |
| sale_return, debt_payment, stock_log, price_history และหลักฐานอื่น | เก็บไว้ก่อนจนกว่าผู้ใช้ยืนยัน retention scope (O-011) |
| sale archive หลัง purge | ต้องกำหนดหลักฐาน/ข้อมูลขั้นต่ำก่อนลบ (O-027); ห้ามเปิด purge จริงจนกว่าจะยืนยัน |

การ purge ต้องอยู่ใน transaction ควบคุม, มี snapshot ก่อนลบ, ตรวจ summary completeness, ไม่ลบบิลค้าง และต้องมี RetentionGuardTest/SummaryAfterPurgeTest

## 5. สถานะการขาย การชำระ และการคืน

ห้ามรวมสถานะการชำระและการคืนเป็น enum เดียว:

- payment_status: PAID / UNPAID
- lifecycle_status: COMPLETED / CANCELLED
- สถานะคืนสินค้า: derive จากผลรวม sale_return_line เทียบ sale_line; สถานะคืนครบทั้งบิลยังรอ O-007

การชำระหนี้เปลี่ยน payment_status เท่านั้น; การยกเลิกเปลี่ยน lifecycle_status เท่านั้น; การคืนเพิ่ม reversal records โดยไม่แก้ยอดต้นฉบับ

## 6. Transaction matrix

| Operation | Writes ใน transaction เดียว |
|---|---|
| สร้าง barcode | barcode_counter + barcode_registry |
| รับสินค้า/ปรับสต๊อก | stock_count/เอกสารรับถ้ามี + stock_log + product cache + op_log + backup_journal |
| ขาย | sale + sale_line + stock_log + product cache + daily_summary + product_daily + op_log + backup_journal |
| ชำระหนี้ | sale.payment_status/paid_at + debt_payment + op_log + backup_journal |
| แก้ราคาขาย | product.price + price_history + op_log + backup_journal |
| คืน/ยกเลิก | sale_return + sale_return_line + stock_log เมื่อ restock + summary + op_log + backup_journal |
| purge | snapshot proof + summary verification + archive proof + controlled delete; รายละเอียด archive รอ O-027 |

## 7. PRAGMA และ Room

ทุก connection ต้องเปิด/ตรวจ:
- PRAGMA foreign_keys = ON;
- PRAGMA busy_timeout = 5000;
- PRAGMA journal_mode = WAL; ตั้งตอน initialize และตรวจค่าที่คืน
- PRAGMA synchronous = FULL; สำหรับข้อมูล POS ที่ต้องการ durability

Room ต้อง export schema JSON ทุก version และใช้ explicit migrations เท่านั้น ห้าม fallback เป็น destructive migration

## 8. ประเด็นที่บล็อกการ finalize schema

| Decision | สิ่งที่ต้องยืนยันก่อน finalize |
|---|---|
| O-005 | การจัดสรร/คืน fee ระดับบิลเมื่อคืนสินค้า |
| O-005/O-006/O-017/O-029/O-030 | สูตรคืน/ยกเลิก/คืนเงิน/เหตุผลไม่ restock/ยกเลิกหลังคืนบางส่วน |
| O-007 | วิธีระบุคืนเต็มบิล |
| O-008 | บล็อกขายเมื่อจำนวนเกิน stock ที่เหลือหรือไม่ |
| O-009/O-010/O-012 | เกินหนึ่งเดือน, timezone/เวลาตัดวัน, จุดเริ่ม retention |
| O-011/O-027 | retention ของประวัติอื่นและหลักฐานหลัง purge |
| O-013 | ชำระหลายบิล atomic แบบใด |
| O-014 | normalization และ canonical fields ของ content_hash |
| O-015/O-016 | สูตรรายงานและนิยามขายน้อยสุด |
| O-018 | normalize ชื่อลูกค้าและกฎชื่อซ้ำ |
| O-019/O-020 | precision หน่วยสินค้าและค่า/ความหมาย stock limit |
| O-021/O-022 | backup journal/snapshot format และ deadline |
| O-028 | validation เงินสดรับไม่พอ/เกินยอด |

## 9. เกณฑ์ตรวจ schema ก่อนเฟส 3

- fresh database สร้างได้และ Room schema export ได้
- UNIQUE/FK/CHECK/trigger ทำงานตามที่ออกแบบ
- ไม่มี cascade delete ที่ลบหลักฐานธุรกิจ
- UNPAID sale ลบไม่ได้ทั้ง service และ SQL trigger
- sale, sale_line, stock_log, summary และ backup_journal commit/rollback พร้อมกัน
- sale.payment_status ไม่ปะปนกับ lifecycle/return state
- migrations ผ่านทั้ง fresh install และ upgrade path
- ทุก blocker ที่มีผลต่อ schema ได้รับคำตอบและบันทึกใน DECISIONS.md
