# แบบฐานข้อมูล POS ร้านแม่ใหญ่อ่อน (SCHEMA)

- รุ่นเอกสาร: 1.0.5-draft
- เฟส: 2 — ออกแบบโครงสร้าง
- สถานะ: ออกแบบตามคำตัดสินใจ D-001 ถึง D-057 แล้ว; ยังไม่ใช่ Room entities หรือ migration ที่รันได้
- Database: SQLite ไฟล์เดียวผ่าน Room
- เงิน: INTEGER หน่วยสตางค์; Java ใช้ long
- อ้างอิง: docs/SPEC.md, docs/DECISIONS.md, docs/ARCHITECTURE.md

## 1. กติกากลาง

1. ชื่อตาราง snake_case; primary key ของเอกสารใช้ UUID เก็บเป็น TEXT เว้นแต่ระบุเป็นอย่างอื่น
2. เงินทุกคอลัมน์เป็น SQLite INTEGER หน่วยสตางค์; จำนวนเงินของธุรกรรมต้องไม่ติดลบ แต่คอลัมน์ยอดสุทธิ/ผลต่างในตารางสรุปอาจติดลบได้; ห้าม REAL/FLOAT/DOUBLE
3. เวลาเก็บเป็น ISO-8601 UTC; business_date คำนวณตาม Asia/Bangkok และเปลี่ยนวันเวลา 00:00 น. ตามเวลาไทย (D-041)
4. Foreign key เปิดทุก connection; ใช้ ON DELETE RESTRICT เป็นค่าเริ่มต้น ห้าม CASCADE ลบประวัติ
5. ตาราง ledger/audit เป็น append-only; แก้ข้อผิดพลาดด้วย reversal record
6. UI ส่ง identifier และ input เท่านั้น; service ต้องอ่านข้อมูลจริงจาก DB และคำนวณยอดใหม่
7. quantity เก็บเป็น INTEGER หน่วย milli-unit โดย 1 หน่วย = 1,000; ห้ามใช้ REAL/FLOAT/DOUBLE (D-050)
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
| stock_limit_qty_milli | INTEGER | NULL ได้; NULL = ปิดแจ้งเตือน, 0 เป็นค่าที่ตั้งได้; แจ้งเมื่อ stock_qty_milli <= limit |
| stock_qty_milli | INTEGER | NOT NULL DEFAULT 0; cache ที่ stock_log trigger ดูแล; 1 หน่วย = 1,000 |
| is_active | INTEGER | NOT NULL DEFAULT 1, CHECK IN (0,1) |
| created_at_utc | TEXT | NOT NULL |
| updated_at_utc | TEXT | NOT NULL |

ห้ามลบสินค้าเชิงกายภาพเมื่อมีประวัติ; ใช้ is_active=0 เมื่อเลิกขาย การแก้ราคาเพิ่ม price_history ทุกครั้ง ห้ามแก้ stock_qty_milli จาก UI/repository โดยตรง

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
| name_key | TEXT | NOT NULL; Unicode NFC + trim + collapse whitespace + case-fold |
| identity_key | TEXT | NOT NULL DEFAULT ''; ตัวระบุเพิ่มเมื่อต้องแยกคนชื่อซ้ำ |
| created_at_utc | TEXT | NOT NULL |
| updated_at_utc | TEXT | NOT NULL |

UNIQUE(name_key, identity_key); คนชื่อซ้ำคนละคนต้องมี identity_key ต่างกัน เช่น เบอร์โทรหรือข้อมูลแยกบุคคลอื่น หากไม่มีตัวระบุที่แตกต่างให้ปฏิเสธการสร้างซ้ำ (D-049)

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
| cash_received_satang | INTEGER | CASH ต้อง NOT NULL และ >= total_satang; CREDIT ต้อง NULL |
| change_satang | INTEGER | CASH = cash_received_satang - total_satang; CREDIT ต้อง NULL |
| content_hash | TEXT | NOT NULL; index ไม่ unique |
| created_at_utc | TEXT | NOT NULL |
| paid_at_utc | TEXT | NULL จนกว่าจะชำระ; CASH กำหนดเท่ากับ created_at_utc |
| settled_by_reversal_at_utc | TEXT | NULL; เวลาที่ credit balance เป็นศูนย์จาก return/cancel โดยไม่ได้รับเงิน |

กฎ: CASH เริ่ม PAID ไม่มี fee และ paid_at_utc = created_at_utc; CREDIT ต้องมี customer และเริ่ม UNPAID; ชำระเต็มยอดค้างแล้วเปลี่ยนเป็น PAID พร้อม paid_at_utc และ debt_payment ใน transaction เดียวกัน การยกเลิกเปลี่ยน lifecycle_status เป็น CANCELLED โดยไม่เขียนทับ payment_status; ถ้ายอดค้างเป็นศูนย์จาก reversal ให้บันทึก settled_by_reversal_at_utc แยกจาก paid_at_utc

return_status เป็นค่าคำนวณ NONE/PARTIAL/FULL จากจำนวนที่คืนจริงเท่านั้น; การยกเลิกไม่ถือเป็นการคืน (D-038)

ค่าบริการ 5% ของบิล CREDIT คำนวณจาก subtotal_satang (ยอดรวมค่าสินค้าทั้งบิลก่อน fee) และปัดเศษครั้งเดียวแบบ Half Up ตาม D-034/D-035; เก็บผลไว้ใน sale.fee_total_satang เท่านั้น ไม่เก็บ fee ใน sale_line ใช้ integer arithmetic เท่านั้น โดย 5% เท่ากับหาร 20: quotient = subtotal_satang / 20, remainder = subtotal_satang % 20, แล้วบวก 1 สตางค์เมื่อ remainder >= 10; CASH มี fee_total_satang = 0

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
| qty_milli | INTEGER | NOT NULL, CHECK > 0; 1 หน่วย = 1,000; scale สูงสุด 3 ตำแหน่ง |
| line_total_satang | INTEGER | NOT NULL, CHECK >= 0 |

UNIQUE(sale_id, line_no) เก็บ snapshot ชื่อ/ราคา/ต้นทุน ณ เวลาขาย ห้ามแก้ snapshot หลัง commit; line_total_satang = Half Up(price_snap_satang × qty_milli ÷ 1,000) ด้วย integer arithmetic ต่อ sale_line (D-050)



### 2.7 debt_payment

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sale_id | TEXT | NOT NULL, UNIQUE, FK sale.id RESTRICT |
| amount_satang | INTEGER | NOT NULL, CHECK > 0 |
| paid_at_utc | TEXT | NOT NULL |

หนึ่งบิลรับชำระเต็มจำนวนของยอดค้างปัจจุบันได้ครั้งเดียว ไม่รองรับ partial payment; ต้องตรวจ amount เท่ากับ outstanding balance จริงใน service transaction เงินชำระหนี้ไม่เพิ่มยอดขาย. การชำระหลายบิลที่เลือกในครั้งเดียวต้องอยู่ใน transaction เดียว; หากบิลใดผิด validation ให้ rollback ทั้งชุด (D-044). outstanding balance คำนวณจาก total ลบ return/cancel reversals; ถ้าเป็นศูนย์จาก reversal ให้ตั้ง settled_by_reversal_at_utc โดยไม่สร้าง debt_payment

### 2.8 sale_return และ sale_return_line

sale_return:

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sale_id | TEXT | NOT NULL, FK sale.id RESTRICT |
| reason | TEXT | NOT NULL |
| cash_refund_satang | INTEGER | NOT NULL DEFAULT 0; > 0 เฉพาะบิลที่ชำระแล้ว |
| debt_reduction_satang | INTEGER | NOT NULL DEFAULT 0; ใช้กับ CREDIT ที่ยังมียอดค้าง |
| fee_reversal_satang | INTEGER | NOT NULL DEFAULT 0; ส่วนของ fee ที่กลับรายการในครั้งนี้ |
| recipient_name | TEXT | ต้องมีเมื่อ cash_refund_satang > 0 |
| operator_label | TEXT | NOT NULL; ผู้ดำเนินการคืนเงิน/ยกเลิก |
| created_at_utc | TEXT | NOT NULL |

sale_return_line:

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| return_id | TEXT | NOT NULL, FK sale_return.id RESTRICT |
| sale_line_id | TEXT | NOT NULL, FK sale_line.id RESTRICT |
| qty_milli | INTEGER | NOT NULL, CHECK > 0 |
| value_satang | INTEGER | NOT NULL, CHECK >= 0; มูลค่าสินค้าที่กลับรายการครั้งนี้ |
| fee_satang | INTEGER | NOT NULL, CHECK >= 0; ค่าบริการที่จัดสรรให้การคืนครั้งนี้ |
| restock | INTEGER | NOT NULL, CHECK IN (0,1) |
| non_restock_reason | TEXT | NOT NULL เมื่อ restock=0; NULL เมื่อ restock=1 |
| evidence_local_ref | TEXT | NULL ได้; ตำแหน่งไฟล์ใน app-private storage |
| evidence_content_hash | TEXT | NULL ได้; SHA-256 ของรูปถ่ายหลักฐานเสริม |

ห้ามคืนเกินจำนวนขายลบจำนวนคืนก่อนหน้า; ตรวจใน transaction และมี DB guard/trigger เพิ่มเติมเมื่อ finalize schema. มูลค่าสินค้าต่อ sale_line ใช้เป้าหมายสะสมแบบ Half Up จาก line_total × qty_returned_cumulative ÷ qty_sold; fee ใช้เป้าหมายสะสมแบบ Half Up จาก fee_total × returned_goods_value_cumulative ÷ subtotal; หาก subtotal=0 fee_total ต้องเป็น 0 และข้ามการหาร. ค่าของครั้งปัจจุบันเป็นส่วนต่างจากยอดสะสมเดิม เพื่อไม่ให้เกิดเศษซ้ำหรือคืนเกินต้นฉบับ (D-036). หากบิลยังค้างให้ลดหนี้; หากชำระแล้วให้คืนเงินสดหน้าร้านและบันทึกผู้รับตาม D-048.

### 2.8.1 sale_cancel และ sale_cancel_line

sale_cancel:

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sale_id | TEXT | NOT NULL, UNIQUE, FK sale.id RESTRICT |
| reason | TEXT | NOT NULL |
| cash_refund_satang | INTEGER | NOT NULL DEFAULT 0 |
| debt_reduction_satang | INTEGER | NOT NULL DEFAULT 0 |
| fee_reversal_satang | INTEGER | NOT NULL DEFAULT 0 |
| recipient_name | TEXT | ต้องมีเมื่อ cash_refund_satang > 0 |
| operator_label | TEXT | NOT NULL; ผู้ดำเนินการคืนเงิน/ยกเลิก |
| created_at_utc | TEXT | NOT NULL |

sale_cancel_line:

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| cancel_id | TEXT | NOT NULL, FK sale_cancel.id RESTRICT |
| sale_line_id | TEXT | NOT NULL, FK sale_line.id RESTRICT |
| qty_milli | INTEGER | NOT NULL, CHECK > 0; ต้องเท่ากับจำนวนคงเหลือที่ยังไม่คืน |
| value_satang | INTEGER | NOT NULL, CHECK >= 0 |
| fee_satang | INTEGER | NOT NULL, CHECK >= 0; fee ที่ยังไม่เคยกลับรายการ |
| restock | INTEGER | NOT NULL, CHECK IN (0,1) |
| non_restock_reason | TEXT | NOT NULL เมื่อ restock=0 |
| evidence_local_ref | TEXT | NULL ได้; ตำแหน่งไฟล์ใน app-private storage |
| evidence_content_hash | TEXT | NULL ได้; SHA-256 ของหลักฐานเสริม |

การยกเลิกหลังคืนบางส่วนบันทึกเฉพาะจำนวนคงเหลือ; ห้ามกลับรายการคืนเดิมซ้ำ. fee reversal ของการยกเลิกเท่ากับ fee ต้นฉบับลบ fee ที่คืน/ยกเลิกก่อนหน้า (D-057). หาก restock=0 ต้องมีเหตุผล; หลักฐานภาพเก็บใน app-private storage พร้อม content hash. การคืนเงิน/ลดหนี้, stock movement, summary, audit และ backup journal ต้อง atomic.

### 2.9 stock_count และ stock_count_line

เพิ่มเป็นเอกสารประกอบการตรวจนับ เพื่อค้นย้อนหลังและเชื่อมกับ stock_log

- stock_count: id TEXT PK, created_at_utc TEXT NOT NULL, note TEXT NULL
- stock_count_line: id TEXT PK, stock_count_id TEXT FK RESTRICT, sku TEXT FK RESTRICT, system_qty_milli INTEGER NOT NULL, counted_qty_milli INTEGER NOT NULL, diff_qty_milli INTEGER NOT NULL, UNIQUE(stock_count_id, sku)

การแทนที่ยอดคงเหลือต้องสร้าง stock_log ต่อ SKU ใน transaction เดียวกับบันทึกเอกสารตรวจนับ โดยใช้ milli-unit ตาม D-050

### 2.10 stock_log

| คอลัมน์ | ชนิด | ข้อกำหนด |
|---|---|---|
| id | TEXT | PK UUID |
| sku | TEXT | NOT NULL, FK product.sku RESTRICT |
| before_qty_milli | INTEGER | NOT NULL |
| after_qty_milli | INTEGER | NOT NULL |
| diff_qty_milli | INTEGER | NOT NULL, CHECK after_qty_milli - before_qty_milli = diff_qty_milli |
| reason | TEXT | NOT NULL, CHECK IN ('RECEIVE','SALE','RETURN','CANCEL','COUNT_REPLACE') |
| ref_id | TEXT | NULL ได้; อ้างอิง sale/return/count operation |
| created_at_utc | TEXT | NOT NULL |

Append-only; index (sku, created_at_utc) และ (ref_id) ทุกการเปลี่ยน stock ต้องมีหนึ่งแถวใน stock_log Trigger เมื่อ insert ต้องตรวจ before_qty_milli เท่ากับ product.stock_qty_milli แล้วปรับ cache stock_qty_milli เป็น after_qty_milli ห้าม update/delete stock_log

ก่อนขายต้องตรวจ qty_milli ที่ขอ <= stock_qty_milli คงเหลือภายใน transaction; หากไม่พอให้บล็อกและห้าม stock ติดลบ (D-039)

### 2.11 price_history

- id TEXT PK UUID
- sku TEXT NOT NULL FK product.sku RESTRICT
- old_price_satang INTEGER NOT NULL CHECK >= 0
- new_price_satang INTEGER NOT NULL CHECK >= 0
- changed_at_utc TEXT NOT NULL

Append-only; index (sku, changed_at_utc); update/delete ไม่อนุญาต

### 2.12 daily_summary

- business_date TEXT PRIMARY KEY (YYYY-MM-DD ตาม Asia/Bangkok)
- cash_sales_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0 (ยอดสินค้า ไม่รวม fee)
- credit_sales_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0 (ยอดสินค้า ไม่รวม fee)
- fees_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0 (fee ที่เรียกเก็บ)
- cash_returned_goods_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- credit_returned_goods_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- cash_returned_fee_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- credit_returned_fee_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- cash_cancelled_goods_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- credit_cancelled_goods_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- cash_cancelled_fee_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- credit_cancelled_fee_satang INTEGER NOT NULL DEFAULT 0 CHECK >= 0
- net_sales_satang INTEGER NOT NULL (อาจติดลบในวันที่มีรายการกลับด้านมากกว่ายอดขายใหม่)
- updated_at_utc TEXT NOT NULL

สูตร: net_sales = cash_sales + credit_sales + fees - cash/credit returned_goods - cash/credit returned_fee - cash/credit cancelled_goods - cash/credit cancelled_fee. การคืน/ยกเลิกลง business_date ที่เกิดเหตุการณ์และจัดประเภทตาม sale.type ต้นทาง (D-046). เก็บ 15 เดือน.

### 2.13 product_daily

- business_date TEXT NOT NULL (Asia/Bangkok)
- sku TEXT NOT NULL FK product.sku RESTRICT
- sold_qty_milli INTEGER NOT NULL DEFAULT 0
- returned_qty_milli INTEGER NOT NULL DEFAULT 0
- cancelled_qty_milli INTEGER NOT NULL DEFAULT 0
- net_qty_milli INTEGER NOT NULL (อาจติดลบในวันที่มีรายการกลับด้านมากกว่ายอดขายใหม่)
- sold_goods_satang INTEGER NOT NULL DEFAULT 0
- returned_goods_satang INTEGER NOT NULL DEFAULT 0
- cancelled_goods_satang INTEGER NOT NULL DEFAULT 0
- net_goods_satang INTEGER NOT NULL
- PRIMARY KEY (business_date, sku)

เก็บ 15 เดือน; ขายดี/ขายน้อยคำนวณจากยอดจำนวนขายสุทธิรวมช่วงที่เลือก. สินค้าขายน้อยสุด 20 อันดับดูย้อนหลัง 30 วัน เฉพาะ net_qty_milli รวมมากกว่า 0 (D-047).

### 2.14 op_log

- id TEXT PK UUID
- action TEXT NOT NULL
- entity_type TEXT NOT NULL
- entity_id TEXT NULL
- detail_json TEXT NULL
- created_at_utc TEXT NOT NULL

เก็บ 90 วันตาม D-042; append-only; index created_at_utc และ (entity_type, entity_id)

### 2.15 backup_journal

- seq INTEGER PRIMARY KEY AUTOINCREMENT
- operation_id TEXT NOT NULL UNIQUE
- op TEXT NOT NULL
- payload TEXT NOT NULL (canonical JSON UTF-8)
- payload_sha256 TEXT NOT NULL
- schema_version INTEGER NOT NULL
- created_at_utc TEXT NOT NULL
- uploaded INTEGER NOT NULL DEFAULT 0 CHECK IN (0,1)

insert ภายใน transaction เดียวกับ business write; index (uploaded, seq) เพื่อส่งตามลำดับ. แต่ละ record มี operation_id และ SHA-256; snapshot เป็น SQLite consistent snapshot รองรับ WAL บรรจุ ZIP พร้อม manifest/checkpoint. Restore ตรวจ checksum/integrity แล้ว replay journal ตาม seq ต่อเนื่องแบบ idempotent. เก็บ snapshot ที่ตรวจผ่านหลายชุดและห้ามลบชุดล่าสุดที่กู้คืนได้ก่อนชุดใหม่ผ่าน. Encryption รอ O-025 (D-052); แจ้งเตือนคิวค้างเกิน 24 ชั่วโมง (D-053).

### 2.16 sale_reconciliation

- sale_ref_hash TEXT PRIMARY KEY; SHA-256 reference derived from sale ID with version prefix
- business_date TEXT NOT NULL
- sale_type TEXT NOT NULL CHECK IN ('CASH','CREDIT')
- subtotal_satang INTEGER NOT NULL
- fee_charged_satang INTEGER NOT NULL
- returned_goods_satang INTEGER NOT NULL
- returned_fee_satang INTEGER NOT NULL
- cancelled_goods_satang INTEGER NOT NULL
- cancelled_fee_satang INTEGER NOT NULL
- net_sales_satang INTEGER NOT NULL
- proof_sha256 TEXT NOT NULL
- created_at_utc TEXT NOT NULL

Immutable reconciliation evidence with no customer name, phone, customer_id or other PII. Retain 15 months after source sale purge (D-054).

## 3. Index ที่ต้องมี

- product(barcode) UNIQUE, product(name), product(is_active, stock_qty_milli)
- barcode_registry(barcode) PRIMARY KEY
- customer(name_key, identity_key) UNIQUE
- sale(idem_key) UNIQUE, sale(created_at_utc), sale(payment_status, created_at_utc), sale(customer_id, payment_status), sale(content_hash, created_at_utc)
- sale_line(sale_id, line_no) UNIQUE, sale_line(sku)
- debt_payment(sale_id) UNIQUE, debt_payment(paid_at_utc)
- sale_return(sale_id, created_at_utc), sale_return_line(sale_line_id)
- sale_cancel(sale_id) UNIQUE, sale_cancel_line(sale_line_id)
- sale_reconciliation(business_date), sale_reconciliation(sale_type)
- stock_log(sku, created_at_utc), stock_log(ref_id)
- price_history(sku, changed_at_utc)
- product_daily(business_date, sku), op_log(created_at_utc)
- backup_journal(uploaded, seq)

## 4. Constraints, triggers และ delete behavior

### บังคับในฐานข้อมูล

- UNIQUE: barcode, (name_key, identity_key), idem_key, debt_payment.sale_id, sale_cancel.sale_id, barcode_counter.id singleton, line numbers
- CHECK: เงินไม่ติดลบ, boolean เป็น 0/1, enum อยู่ในค่าที่กำหนด, line number และ qty_milli เป็นบวก; CASH ต้องรับเงิน >= total และเงินทอนเท่ากับส่วนต่าง
- FK: เปิด foreign_keys ทุก connection; ความสัมพันธ์ประวัติใช้ RESTRICT
- Trigger: ห้าม DELETE บิลที่ยังมียอดค้างจริง; ห้าม UPDATE/DELETE ledger/audit/registry; stock_log insert เป็นทางเดียวที่เปลี่ยน cached stock_qty_milli
- ห้ามใช้ ON DELETE CASCADE กับ sale, sale_line, debt_payment, sale_return, stock_log, price_history หรือ audit evidence

### นโยบายลบ

| ข้อมูล | นโยบาย |
|---|---|
| sale ที่มี outstanding balance > 0 | ห้ามลบทุกกรณี ไม่ขึ้นกับ lifecycle_status |
| sale เงินสด PAID | เริ่มนับ 30 วันจาก created_at_utc (= paid_at_utc); purge เมื่อครบและ guard ผ่าน |
| sale เงินเชื่อ PAID | เริ่มนับ 30 วันจาก paid_at_utc เมื่อชำระเต็มยอด |
| sale เชื่อที่ยอดเป็นศูนย์จาก reversal โดยไม่รับเงิน | เริ่มนับ 30 วันจาก settled_by_reversal_at_utc; payment_status ยังคงแยกจากการชำระ |
| sale_return, sale_cancel, debt_payment, stock_log, price_history, op_log | เก็บ 90 วันจาก created_at_utc; FK child ที่ยังอยู่ทำให้เลื่อน purge parent ได้ |
| daily_summary, product_daily, sale_reconciliation | เก็บ 15 เดือน |
| product, barcode_registry | เก็บถาวร |
| backup_journal | เก็บจน journal ถูกส่งและถูกครอบคลุมด้วย snapshot ที่ตรวจสอบแล้ว; prune ได้เมื่อ recovery chain ยังสมบูรณ์ |

ก่อน purge ต้องสร้าง reconciliation proof แบบไม่มี PII เก็บ 15 เดือน โดยมี hash reference, business date, sale type, subtotal, fee, returns, cancellations และ net sales. purge ต้องมี snapshot ก่อนลบ, proof commit, summary กระทบยอดตรง, foreign-key/retention guard ผ่าน และต้องมี RetentionGuardTest/SummaryAfterPurgeTest (D-054)

## 5. สถานะการขาย การชำระ และการคืน

ห้ามรวมสถานะการชำระและการคืนเป็น enum เดียว:

- payment_status: PAID / UNPAID
- lifecycle_status: COMPLETED / CANCELLED
- return_status: derive NONE/PARTIAL/FULL จากจำนวนที่คืนจริงเทียบ sale_line; cancellation ไม่ถูกนับเป็น return

การชำระหนี้เปลี่ยน payment_status เท่านั้น; การยกเลิกเปลี่ยน lifecycle_status เท่านั้น; การคืนเพิ่ม reversal records โดยไม่แก้ยอดต้นฉบับ

## 6. Transaction matrix

| Operation | Writes ใน transaction เดียว |
|---|---|
| สร้าง barcode | barcode_counter + barcode_registry |
| รับสินค้า/ปรับสต๊อก | stock_count/เอกสารรับถ้ามี + stock_log + product cache + op_log + backup_journal |
| ขาย | sale + sale_line + stock_log + product cache + daily_summary + product_daily + op_log + backup_journal |
| ชำระหนี้หลายบิล | ตรวจและเปลี่ยนทุก sale.payment_status/paid_at + debt_payment แยกต่อบิล + op_log + backup_journal; all-or-nothing transaction |
| แก้ราคาขาย | product.price + price_history + op_log + backup_journal |
| คืนสินค้า | sale_return + sale_return_line + stock_log เมื่อ restock + summary + op_log + backup_journal |
| ยกเลิก | sale_cancel + sale_cancel_line + lifecycle_status + stock_log ตาม restock + summary + op_log + backup_journal |
| purge | snapshot + reconciliation proof + summary verification + controlled delete; purge เฉพาะเมื่อยอดค้างเป็นศูนย์และ retention/FK guards ผ่าน |

## 7. PRAGMA และ Room

ทุก connection ต้องเปิด/ตรวจ:
- PRAGMA foreign_keys = ON;
- PRAGMA busy_timeout = 5000;
- PRAGMA journal_mode = WAL; ตั้งตอน initialize และตรวจค่าที่คืน
- PRAGMA synchronous = FULL; สำหรับข้อมูล POS ที่ต้องการ durability

Room ต้อง export schema JSON ทุก version และใช้ explicit migrations เท่านั้น ห้าม fallback เป็น destructive migration

## 8. สถานะประเด็นที่มีผลต่อ schema

O-005 ถึง O-022 และ O-027 ถึง O-030 ได้รับการตัดสินใจและบันทึกใน docs/DECISIONS.md แล้ว. ประเด็น O-023 (Google Drive authentication), O-024 (TTS fallback), O-025 (password/database encryption) และ O-026 (ตำแหน่ง UI สำหรับ restore) ยังเป็นงานของเฟส 3/4/10 ตามลำดับ; ไม่ใช่ blocker ที่ยังเปิดของ schema ธุรกิจในเฟส 2.

## 9. เกณฑ์ตรวจ schema ก่อนเฟส 3

- fresh database สร้างได้และ Room schema export ได้
- UNIQUE/FK/CHECK/trigger ทำงานตามที่ออกแบบ
- ไม่มี cascade delete ที่ลบหลักฐานธุรกิจ
- UNPAID sale ลบไม่ได้ทั้ง service และ SQL trigger
- sale, sale_line, stock_log, summary และ backup_journal commit/rollback พร้อมกัน
- sale.payment_status ไม่ปะปนกับ lifecycle/return state
- migrations ผ่านทั้ง fresh install และ upgrade path
- ทุก blocker ที่มีผลต่อ schema ได้รับคำตอบและบันทึกใน DECISIONS.md
