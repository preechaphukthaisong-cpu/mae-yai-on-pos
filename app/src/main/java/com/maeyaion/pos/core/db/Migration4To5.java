package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds normalized transactional, reversal, price and reporting ledgers without dropping v4 data. */
public final class Migration4To5 {
    private Migration4To5() {}
    public static final Migration INSTANCE = new Migration(4, 5) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE sale (id TEXT NOT NULL PRIMARY KEY, idem_key TEXT NOT NULL, type TEXT NOT NULL CHECK(type IN ('CASH','CREDIT')), payment_status TEXT NOT NULL CHECK(payment_status IN ('PAID','UNPAID')), lifecycle_status TEXT NOT NULL CHECK(lifecycle_status IN ('COMPLETED','CANCELLED')), customer_id TEXT, subtotal_satang INTEGER NOT NULL CHECK(subtotal_satang>=0), fee_total_satang INTEGER NOT NULL CHECK(fee_total_satang>=0), total_satang INTEGER NOT NULL CHECK(total_satang=subtotal_satang+fee_total_satang), cash_received_satang INTEGER, change_satang INTEGER, content_hash TEXT NOT NULL, created_at_utc TEXT NOT NULL, paid_at_utc TEXT, settled_by_reversal_at_utc TEXT, FOREIGN KEY(customer_id) REFERENCES customer(id) ON UPDATE NO ACTION ON DELETE RESTRICT, CHECK((type='CASH' AND payment_status='PAID' AND customer_id IS NULL AND fee_total_satang=0 AND cash_received_satang>=total_satang AND change_satang=cash_received_satang-total_satang AND paid_at_utc=created_at_utc) OR (type='CREDIT' AND payment_status IN ('UNPAID','PAID') AND customer_id IS NOT NULL AND cash_received_satang IS NULL AND change_satang IS NULL)))");
            db.execSQL("CREATE UNIQUE INDEX index_sale_idem_key ON sale(idem_key)");
            db.execSQL("CREATE INDEX index_sale_created_at_utc ON sale(created_at_utc)");
            db.execSQL("CREATE INDEX index_sale_payment_status_created_at_utc ON sale(payment_status,created_at_utc)");
            db.execSQL("CREATE INDEX index_sale_customer_id_payment_status ON sale(customer_id,payment_status)");
            db.execSQL("CREATE INDEX index_sale_content_hash_created_at_utc ON sale(content_hash,created_at_utc)");
            db.execSQL("CREATE TABLE sale_line (id TEXT NOT NULL PRIMARY KEY, sale_id TEXT NOT NULL, line_no INTEGER NOT NULL CHECK(line_no>0), sku TEXT NOT NULL, name_snap TEXT NOT NULL, price_snap_satang INTEGER NOT NULL CHECK(price_snap_satang>=0), cost_snap_satang INTEGER NOT NULL CHECK(cost_snap_satang>=0), qty_milli INTEGER NOT NULL CHECK(qty_milli>0), line_total_satang INTEGER NOT NULL CHECK(line_total_satang>=0), FOREIGN KEY(sale_id) REFERENCES sale(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX index_sale_line_sale_id_line_no ON sale_line(sale_id,line_no)");
            db.execSQL("CREATE INDEX index_sale_line_sku ON sale_line(sku)");
            db.execSQL("CREATE TABLE debt_payment (id TEXT NOT NULL PRIMARY KEY, sale_id TEXT NOT NULL, amount_satang INTEGER NOT NULL CHECK(amount_satang>0), paid_at_utc TEXT NOT NULL, FOREIGN KEY(sale_id) REFERENCES sale(id) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX index_debt_payment_sale_id ON debt_payment(sale_id)");
            db.execSQL("CREATE INDEX index_debt_payment_paid_at_utc ON debt_payment(paid_at_utc)");
            db.execSQL("CREATE TABLE sale_return (id TEXT NOT NULL PRIMARY KEY, sale_id TEXT NOT NULL, reason TEXT NOT NULL, cash_refund_satang INTEGER NOT NULL DEFAULT 0 CHECK(cash_refund_satang>=0), debt_reduction_satang INTEGER NOT NULL DEFAULT 0 CHECK(debt_reduction_satang>=0), fee_reversal_satang INTEGER NOT NULL DEFAULT 0 CHECK(fee_reversal_satang>=0), recipient_name TEXT, operator_label TEXT NOT NULL, created_at_utc TEXT NOT NULL, FOREIGN KEY(sale_id) REFERENCES sale(id) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE INDEX index_sale_return_sale_id_created_at_utc ON sale_return(sale_id,created_at_utc)");
            db.execSQL("CREATE TABLE sale_return_line (id TEXT NOT NULL PRIMARY KEY, return_id TEXT NOT NULL, sale_line_id TEXT NOT NULL, qty_milli INTEGER NOT NULL CHECK(qty_milli>0), value_satang INTEGER NOT NULL CHECK(value_satang>=0), fee_satang INTEGER NOT NULL CHECK(fee_satang>=0), restock INTEGER NOT NULL CHECK(restock IN (0,1)), non_restock_reason TEXT, evidence_local_ref TEXT, evidence_content_hash TEXT, FOREIGN KEY(return_id) REFERENCES sale_return(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(sale_line_id) REFERENCES sale_line(id) ON UPDATE NO ACTION ON DELETE RESTRICT, CHECK((restock=1 AND non_restock_reason IS NULL) OR (restock=0 AND length(trim(non_restock_reason))>0)))");
            db.execSQL("CREATE INDEX index_sale_return_line_sale_line_id ON sale_return_line(sale_line_id)");
            db.execSQL("CREATE TABLE sale_cancel (id TEXT NOT NULL PRIMARY KEY, sale_id TEXT NOT NULL, reason TEXT NOT NULL, cash_refund_satang INTEGER NOT NULL DEFAULT 0 CHECK(cash_refund_satang>=0), debt_reduction_satang INTEGER NOT NULL DEFAULT 0 CHECK(debt_reduction_satang>=0), fee_reversal_satang INTEGER NOT NULL DEFAULT 0 CHECK(fee_reversal_satang>=0), recipient_name TEXT, operator_label TEXT NOT NULL, created_at_utc TEXT NOT NULL, FOREIGN KEY(sale_id) REFERENCES sale(id) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX index_sale_cancel_sale_id ON sale_cancel(sale_id)");
            db.execSQL("CREATE TABLE sale_cancel_line (id TEXT NOT NULL PRIMARY KEY, cancel_id TEXT NOT NULL, sale_line_id TEXT NOT NULL, qty_milli INTEGER NOT NULL CHECK(qty_milli>0), value_satang INTEGER NOT NULL CHECK(value_satang>=0), fee_satang INTEGER NOT NULL CHECK(fee_satang>=0), restock INTEGER NOT NULL CHECK(restock IN (0,1)), non_restock_reason TEXT, evidence_local_ref TEXT, evidence_content_hash TEXT, FOREIGN KEY(cancel_id) REFERENCES sale_cancel(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(sale_line_id) REFERENCES sale_line(id) ON UPDATE NO ACTION ON DELETE RESTRICT, CHECK((restock=1 AND non_restock_reason IS NULL) OR (restock=0 AND length(trim(non_restock_reason))>0)))");
            db.execSQL("CREATE INDEX index_sale_cancel_line_sale_line_id ON sale_cancel_line(sale_line_id)");
            db.execSQL("CREATE TABLE stock_count (id TEXT NOT NULL PRIMARY KEY, created_at_utc TEXT NOT NULL, note TEXT)");
            db.execSQL("CREATE TABLE stock_count_line (id TEXT NOT NULL PRIMARY KEY, stock_count_id TEXT NOT NULL, sku TEXT NOT NULL, system_qty_milli INTEGER NOT NULL, counted_qty_milli INTEGER NOT NULL CHECK(counted_qty_milli>=0), diff_qty_milli INTEGER NOT NULL, FOREIGN KEY(stock_count_id) REFERENCES stock_count(id) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE UNIQUE INDEX index_stock_count_line_stock_count_id_sku ON stock_count_line(stock_count_id,sku)");
            db.execSQL("CREATE INDEX index_stock_count_line_sku ON stock_count_line(sku)");
            db.execSQL("CREATE TABLE price_history (id TEXT NOT NULL PRIMARY KEY, sku TEXT NOT NULL, old_price_satang INTEGER NOT NULL CHECK(old_price_satang>=0), new_price_satang INTEGER NOT NULL CHECK(new_price_satang>=0), changed_at_utc TEXT NOT NULL, FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE INDEX index_price_history_sku_changed_at_utc ON price_history(sku,changed_at_utc)");
            db.execSQL("CREATE TABLE daily_summary (business_date TEXT NOT NULL PRIMARY KEY, cash_sales_satang INTEGER NOT NULL DEFAULT 0, credit_sales_satang INTEGER NOT NULL DEFAULT 0, fees_satang INTEGER NOT NULL DEFAULT 0, cash_returned_goods_satang INTEGER NOT NULL DEFAULT 0, credit_returned_goods_satang INTEGER NOT NULL DEFAULT 0, cash_returned_fee_satang INTEGER NOT NULL DEFAULT 0, credit_returned_fee_satang INTEGER NOT NULL DEFAULT 0, cash_cancelled_goods_satang INTEGER NOT NULL DEFAULT 0, credit_cancelled_goods_satang INTEGER NOT NULL DEFAULT 0, cash_cancelled_fee_satang INTEGER NOT NULL DEFAULT 0, credit_cancelled_fee_satang INTEGER NOT NULL DEFAULT 0, net_sales_satang INTEGER NOT NULL, updated_at_utc TEXT NOT NULL)");
            db.execSQL("CREATE TABLE product_daily (business_date TEXT NOT NULL, sku TEXT NOT NULL, sold_qty_milli INTEGER NOT NULL DEFAULT 0, returned_qty_milli INTEGER NOT NULL DEFAULT 0, cancelled_qty_milli INTEGER NOT NULL DEFAULT 0, net_qty_milli INTEGER NOT NULL, sold_goods_satang INTEGER NOT NULL DEFAULT 0, returned_goods_satang INTEGER NOT NULL DEFAULT 0, cancelled_goods_satang INTEGER NOT NULL DEFAULT 0, net_goods_satang INTEGER NOT NULL, PRIMARY KEY(business_date,sku), FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE NO ACTION ON DELETE RESTRICT)");
            db.execSQL("CREATE INDEX index_product_daily_business_date_sku ON product_daily(business_date,sku)");
            db.execSQL("CREATE TABLE sale_reconciliation (sale_ref_hash TEXT NOT NULL PRIMARY KEY, business_date TEXT NOT NULL, sale_type TEXT NOT NULL CHECK(sale_type IN ('CASH','CREDIT')), subtotal_satang INTEGER NOT NULL, fee_charged_satang INTEGER NOT NULL, returned_goods_satang INTEGER NOT NULL, returned_fee_satang INTEGER NOT NULL, cancelled_goods_satang INTEGER NOT NULL, cancelled_fee_satang INTEGER NOT NULL, net_sales_satang INTEGER NOT NULL, proof_sha256 TEXT NOT NULL, created_at_utc TEXT NOT NULL)");
            db.execSQL("CREATE INDEX index_sale_reconciliation_business_date ON sale_reconciliation(business_date)");
            db.execSQL("CREATE INDEX index_sale_reconciliation_sale_type ON sale_reconciliation(sale_type)");
            db.execSQL("CREATE TRIGGER sale_line_no_update BEFORE UPDATE ON sale_line BEGIN SELECT RAISE(ABORT,'sale_line_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_line_no_delete BEFORE DELETE ON sale_line BEGIN SELECT RAISE(ABORT,'sale_line_append_only'); END");
            db.execSQL("CREATE TRIGGER debt_payment_no_update BEFORE UPDATE ON debt_payment BEGIN SELECT RAISE(ABORT,'debt_payment_append_only'); END");
            db.execSQL("CREATE TRIGGER debt_payment_no_delete BEFORE DELETE ON debt_payment BEGIN SELECT RAISE(ABORT,'debt_payment_append_only'); END");
            db.execSQL("CREATE TRIGGER price_history_no_update BEFORE UPDATE ON price_history BEGIN SELECT RAISE(ABORT,'price_history_append_only'); END");
            db.execSQL("CREATE TRIGGER price_history_no_delete BEFORE DELETE ON price_history BEGIN SELECT RAISE(ABORT,'price_history_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_return_no_update BEFORE UPDATE ON sale_return BEGIN SELECT RAISE(ABORT,'sale_return_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_return_no_delete BEFORE DELETE ON sale_return BEGIN SELECT RAISE(ABORT,'sale_return_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_return_line_no_update BEFORE UPDATE ON sale_return_line BEGIN SELECT RAISE(ABORT,'sale_return_line_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_return_line_no_delete BEFORE DELETE ON sale_return_line BEGIN SELECT RAISE(ABORT,'sale_return_line_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_cancel_no_update BEFORE UPDATE ON sale_cancel BEGIN SELECT RAISE(ABORT,'sale_cancel_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_cancel_no_delete BEFORE DELETE ON sale_cancel BEGIN SELECT RAISE(ABORT,'sale_cancel_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_cancel_line_no_update BEFORE UPDATE ON sale_cancel_line BEGIN SELECT RAISE(ABORT,'sale_cancel_line_append_only'); END");
            db.execSQL("CREATE TRIGGER sale_cancel_line_no_delete BEFORE DELETE ON sale_cancel_line BEGIN SELECT RAISE(ABORT,'sale_cancel_line_append_only'); END");
            db.execSQL("CREATE TRIGGER stock_count_line_no_update BEFORE UPDATE ON stock_count_line BEGIN SELECT RAISE(ABORT,'stock_count_line_append_only'); END");
            db.execSQL("CREATE TRIGGER stock_count_line_no_delete BEFORE DELETE ON stock_count_line BEGIN SELECT RAISE(ABORT,'stock_count_line_append_only'); END");
            DatabaseGuards.installCore(db);
            DatabaseGuards.installBackupJournal(db);
        }
    };
}
