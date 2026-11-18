package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Refreshes database invariants for installations that already run schema version 3. */
public final class Migration3To4 {
    private Migration3To4() {}

    public static final Migration INSTANCE = new Migration(3, 4) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            // Preserve legacy stock balances that may have been inserted before ledger guards existed.
            db.execSQL("DROP TRIGGER IF EXISTS stock_log_validate");
            db.execSQL("DROP TRIGGER IF EXISTS stock_log_apply");
            db.execSQL("INSERT INTO stock_log(id, sku, before_qty_milli, after_qty_milli, diff_qty_milli, reason, ref_id, created_at_utc) "
                    + "SELECT 'MIGRATION_V3_BASELINE_' || lower(hex(randomblob(16))), p.sku, "
                    + "COALESCE((SELECT sl.after_qty_milli FROM stock_log sl WHERE sl.sku = p.sku ORDER BY sl.rowid DESC LIMIT 1), 0), "
                    + "p.stock_qty_milli, "
                    + "p.stock_qty_milli - COALESCE((SELECT sl.after_qty_milli FROM stock_log sl WHERE sl.sku = p.sku ORDER BY sl.rowid DESC LIMIT 1), 0), "
                    + "'COUNT_REPLACE', 'MIGRATION_V3_BASELINE', p.updated_at_utc "
                    + "FROM product p WHERE p.stock_qty_milli != "
                    + "COALESCE((SELECT sl.after_qty_milli FROM stock_log sl WHERE sl.sku = p.sku ORDER BY sl.rowid DESC LIMIT 1), 0)");
            DatabaseGuards.installCore(db);
            DatabaseGuards.installBackupJournal(db);
        }
    };
}
