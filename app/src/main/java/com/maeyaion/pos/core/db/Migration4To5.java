package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds the minimum immutable cash-sale ledger required by the Phase 1 acceptance path. */
public final class Migration4To5 {
    private Migration4To5() {}

    public static final Migration INSTANCE = new Migration(4, 5) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sale (id TEXT NOT NULL, idem_key TEXT NOT NULL, type TEXT NOT NULL, payment_status TEXT NOT NULL, lifecycle_status TEXT NOT NULL, customer_id TEXT, subtotal_satang INTEGER NOT NULL, fee_total_satang INTEGER NOT NULL, total_satang INTEGER NOT NULL, cash_received_satang INTEGER, change_satang INTEGER, content_hash TEXT NOT NULL, created_at_utc TEXT NOT NULL, paid_at_utc TEXT, settled_by_reversal_at_utc TEXT, PRIMARY KEY(id), FOREIGN KEY(customer_id) REFERENCES customer(id) ON UPDATE RESTRICT ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sale_idem_key ON sale(idem_key)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_created_at_utc ON sale(created_at_utc)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_content_hash ON sale(content_hash)");
            db.execSQL("CREATE TABLE IF NOT EXISTS sale_line (id TEXT NOT NULL, sale_id TEXT NOT NULL, line_no INTEGER NOT NULL, sku TEXT NOT NULL, name_snap TEXT NOT NULL, price_snap_satang INTEGER NOT NULL, cost_snap_satang INTEGER NOT NULL, qty_milli INTEGER NOT NULL, line_total_satang INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sale_id) REFERENCES sale(id) ON UPDATE RESTRICT ON DELETE RESTRICT, FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE RESTRICT ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sale_line_sale_id_line_no ON sale_line(sale_id, line_no)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_line_sku ON sale_line(sku)");
            DatabaseGuards.installSales(db);
        }
    };
}
