package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Explicit, non-destructive upgrade from the original foundation database. */
public final class Migration1To2 {
    private Migration1To2() {}

    public static final Migration INSTANCE = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS barcode_registry (barcode TEXT NOT NULL, display_name TEXT NOT NULL, price_satang INTEGER NOT NULL CHECK(price_satang >= 0), source_type TEXT NOT NULL CHECK(source_type IN ('SYSTEM','MANUFACTURER')), created_at_utc TEXT NOT NULL, PRIMARY KEY(barcode))");
            db.execSQL("CREATE TABLE IF NOT EXISTS barcode_counter (id INTEGER NOT NULL CHECK(id = 1), next_value INTEGER NOT NULL CHECK(next_value >= 2000001), PRIMARY KEY(id))");
            db.execSQL("INSERT OR IGNORE INTO barcode_counter (id, next_value) VALUES (1, 2000001)");
            db.execSQL("CREATE TABLE IF NOT EXISTS product (sku TEXT NOT NULL, barcode TEXT NOT NULL, name TEXT NOT NULL, price_satang INTEGER NOT NULL CHECK(price_satang >= 0), cost_satang INTEGER NOT NULL CHECK(cost_satang >= 0), unit TEXT NOT NULL, stock_limit_qty_milli INTEGER, stock_qty_milli INTEGER NOT NULL DEFAULT 0 CHECK(stock_qty_milli >= 0), is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)), created_at_utc TEXT NOT NULL, updated_at_utc TEXT NOT NULL, PRIMARY KEY(sku), FOREIGN KEY(barcode) REFERENCES barcode_registry(barcode) ON UPDATE RESTRICT ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_barcode ON product(barcode)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_name ON product(name)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_is_active_stock_qty_milli ON product(is_active, stock_qty_milli)");
            db.execSQL("CREATE TABLE IF NOT EXISTS customer (id TEXT NOT NULL, name TEXT NOT NULL, name_key TEXT NOT NULL, identity_key TEXT NOT NULL, created_at_utc TEXT NOT NULL, updated_at_utc TEXT NOT NULL, PRIMARY KEY(id))");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_customer_name_key_identity_key ON customer(name_key, identity_key)");
            db.execSQL("CREATE TABLE IF NOT EXISTS stock_log (id TEXT NOT NULL, sku TEXT NOT NULL, before_qty_milli INTEGER NOT NULL, after_qty_milli INTEGER NOT NULL, diff_qty_milli INTEGER NOT NULL CHECK(after_qty_milli - before_qty_milli = diff_qty_milli), reason TEXT NOT NULL CHECK(reason IN ('RECEIVE','SALE','RETURN','CANCEL','COUNT_REPLACE')), ref_id TEXT, created_at_utc TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE RESTRICT ON DELETE RESTRICT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_log_sku_created_at_utc ON stock_log(sku, created_at_utc)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_log_ref_id ON stock_log(ref_id)");
            db.execSQL("CREATE TABLE IF NOT EXISTS op_log (id TEXT NOT NULL, action TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT, detail_json TEXT, created_at_utc TEXT NOT NULL, PRIMARY KEY(id))");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_op_log_created_at_utc ON op_log(created_at_utc)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_op_log_entity_type_entity_id ON op_log(entity_type, entity_id)");
            db.execSQL("CREATE TRIGGER stock_log_validate BEFORE INSERT ON stock_log BEGIN SELECT CASE WHEN NEW.before_qty_milli != (SELECT stock_qty_milli FROM product WHERE sku = NEW.sku) THEN RAISE(ABORT, 'stock_before_mismatch') END; SELECT CASE WHEN NEW.after_qty_milli < 0 THEN RAISE(ABORT, 'stock_negative') END; SELECT CASE WHEN NEW.diff_qty_milli != NEW.after_qty_milli - NEW.before_qty_milli THEN RAISE(ABORT, 'stock_diff_mismatch') END; END");
            db.execSQL("CREATE TRIGGER stock_log_apply AFTER INSERT ON stock_log BEGIN UPDATE product SET stock_qty_milli = NEW.after_qty_milli WHERE sku = NEW.sku; END");
            db.execSQL("CREATE TRIGGER stock_log_no_update BEFORE UPDATE ON stock_log BEGIN SELECT RAISE(ABORT, 'stock_log_append_only'); END");
            db.execSQL("CREATE TRIGGER stock_log_no_delete BEFORE DELETE ON stock_log BEGIN SELECT RAISE(ABORT, 'stock_log_append_only'); END");
            db.execSQL("CREATE TRIGGER op_log_no_update BEFORE UPDATE ON op_log BEGIN SELECT RAISE(ABORT, 'op_log_append_only'); END");
            db.execSQL("CREATE TRIGGER op_log_no_delete BEFORE DELETE ON op_log BEGIN SELECT RAISE(ABORT, 'op_log_append_only'); END");
            db.execSQL("CREATE TRIGGER barcode_registry_no_update BEFORE UPDATE ON barcode_registry BEGIN SELECT RAISE(ABORT, 'barcode_registry_immutable'); END");
            db.execSQL("CREATE TRIGGER barcode_registry_no_delete BEFORE DELETE ON barcode_registry BEGIN SELECT RAISE(ABORT, 'barcode_registry_immutable'); END");
        }
    };
}