-- Idempotent system roles and permission catalogue.
-- Run after migrations 001-003. Replace role IDs only through a new migration.

INSERT OR IGNORE INTO roles(id, role_key, role_name, description, is_system, created_at, updated_at)
VALUES
('role_owner','owner','เจ้าของร้าน','สิทธิ์เต็มของระบบ',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
('role_manager','manager','ผู้จัดการ','จัดการร้านและรายงาน',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),
('role_cashier','cashier','พนักงานขาย','ขายสินค้าและรับชำระเงิน',1,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z');

INSERT OR IGNORE INTO permissions(id, permission_key, description) VALUES
('perm_sales_read','sales.read','ดูรายการขาย'),
('perm_sales_create','sales.create','สร้างรายการขาย'),
('perm_sales_void','sales.void','ยกเลิกรายการขาย'),
('perm_discount_apply','discount.apply','ให้ส่วนลด'),
('perm_refund_create','refund.create','คืนเงิน'),
('perm_catalog_read','catalog.read','ดูสินค้า'),
('perm_catalog_write','catalog.write','จัดการสินค้า'),
('perm_inventory_read','inventory.read','ดูสต็อก'),
('perm_inventory_adjust','inventory.adjust','ปรับสต็อก'),
('perm_purchase_manage','purchase.manage','จัดซื้อและรับสินค้า'),
('perm_customer_manage','customer.manage','จัดการลูกค้า'),
('perm_staff_manage','staff.manage','จัดการพนักงานและสิทธิ์'),
('perm_reports_read','reports.read','ดูรายงาน'),
('perm_cash_open_close','cash.open_close','เปิดและปิดกะเงินสด'),
('perm_cash_adjust','cash.adjust','บันทึกเงินเข้าออกและปรับยอด'),
('perm_settings_manage','settings.manage','จัดการการตั้งค่าร้าน');

INSERT OR IGNORE INTO role_permissions(role_id, permission_id)
SELECT 'role_owner', id FROM permissions;

INSERT OR IGNORE INTO role_permissions(role_id, permission_id)
SELECT 'role_manager', id FROM permissions
WHERE permission_key NOT IN ('staff.manage','settings.manage');

INSERT OR IGNORE INTO role_permissions(role_id, permission_id)
SELECT 'role_cashier', id FROM permissions
WHERE permission_key IN ('sales.read','sales.create','catalog.read','inventory.read','cash.open_close');
