package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Installs database-level invariants for both fresh installs and upgraded databases. */
public final class DatabaseGuards {
    private DatabaseGuards() {}

    public static void installCore(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS barcode_registry_insert_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS barcode_registry_insert_guard BEFORE INSERT ON barcode_registry WHEN length(NEW.barcode) = 0 OR length(NEW.barcode) > 128 OR NEW.barcode != trim(NEW.barcode) OR NEW.price_satang < 0 OR NEW.source_type NOT IN ('SYSTEM','MANUFACTURER') OR length(trim(NEW.display_name)) = 0 OR length(trim(NEW.created_at_utc)) = 0 BEGIN SELECT RAISE(ABORT, 'barcode_registry_invalid'); END");
        db.execSQL("DROP TRIGGER IF EXISTS barcode_registry_no_update");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS barcode_registry_no_update BEFORE UPDATE ON barcode_registry BEGIN SELECT RAISE(ABORT, 'barcode_registry_immutable'); END");
        db.execSQL("DROP TRIGGER IF EXISTS barcode_registry_no_delete");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS barcode_registry_no_delete BEFORE DELETE ON barcode_registry BEGIN SELECT RAISE(ABORT, 'barcode_registry_immutable'); END");

        db.execSQL("DROP TRIGGER IF EXISTS barcode_counter_insert_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS barcode_counter_insert_guard BEFORE INSERT ON barcode_counter WHEN NEW.id != 1 OR NEW.next_value < 2000001 BEGIN SELECT RAISE(ABORT, 'barcode_counter_invalid'); END");
        db.execSQL("DROP TRIGGER IF EXISTS barcode_counter_update_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS barcode_counter_update_guard BEFORE UPDATE ON barcode_counter WHEN NEW.id != OLD.id OR NEW.id != 1 OR NEW.next_value < 2000001 OR NEW.next_value < OLD.next_value BEGIN SELECT RAISE(ABORT, 'barcode_counter_invalid'); END");
        db.execSQL("DROP TRIGGER IF EXISTS barcode_counter_no_delete");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS barcode_counter_no_delete BEFORE DELETE ON barcode_counter BEGIN SELECT RAISE(ABORT, 'barcode_counter_immutable'); END");

        db.execSQL("DROP TRIGGER IF EXISTS product_insert_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS product_insert_guard BEFORE INSERT ON product WHEN NEW.stock_qty_milli != 0 OR NEW.price_satang < 0 OR NEW.cost_satang < 0 OR (NEW.stock_limit_qty_milli IS NOT NULL AND NEW.stock_limit_qty_milli < 0) OR NEW.is_active NOT IN (0,1) OR length(trim(NEW.sku)) = 0 OR NEW.sku != trim(NEW.sku) OR length(trim(NEW.name)) = 0 OR length(trim(NEW.unit)) = 0 OR length(trim(NEW.created_at_utc)) = 0 OR length(trim(NEW.updated_at_utc)) = 0 BEGIN SELECT RAISE(ABORT, 'product_invalid_initial_state'); END");
        db.execSQL("DROP TRIGGER IF EXISTS product_update_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS product_update_guard BEFORE UPDATE ON product WHEN NEW.price_satang < 0 OR NEW.cost_satang < 0 OR (NEW.stock_limit_qty_milli IS NOT NULL AND NEW.stock_limit_qty_milli < 0) OR NEW.is_active NOT IN (0,1) OR length(trim(NEW.name)) = 0 OR length(trim(NEW.unit)) = 0 OR length(trim(NEW.updated_at_utc)) = 0 OR (NEW.stock_qty_milli != OLD.stock_qty_milli AND NOT EXISTS (SELECT 1 FROM stock_log WHERE rowid = (SELECT MAX(rowid) FROM stock_log WHERE sku = OLD.sku) AND sku = OLD.sku AND before_qty_milli = OLD.stock_qty_milli AND after_qty_milli = NEW.stock_qty_milli)) BEGIN SELECT RAISE(ABORT, 'product_invalid_update'); END");
        db.execSQL("DROP TRIGGER IF EXISTS product_identity_immutable");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS product_identity_immutable BEFORE UPDATE ON product WHEN NEW.sku != OLD.sku OR NEW.barcode != OLD.barcode BEGIN SELECT RAISE(ABORT, 'product_identity_immutable'); END");
        db.execSQL("DROP TRIGGER IF EXISTS product_no_delete");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS product_no_delete BEFORE DELETE ON product BEGIN SELECT RAISE(ABORT, 'product_retained'); END");

        db.execSQL("DROP TRIGGER IF EXISTS stock_log_validate");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS stock_log_validate BEFORE INSERT ON stock_log BEGIN SELECT CASE WHEN length(trim(NEW.id)) = 0 OR length(trim(NEW.sku)) = 0 OR length(trim(NEW.created_at_utc)) = 0 OR NEW.before_qty_milli < 0 OR NEW.after_qty_milli < 0 THEN RAISE(ABORT, 'stock_negative') END; SELECT CASE WHEN NEW.before_qty_milli != COALESCE((SELECT stock_qty_milli FROM product WHERE sku = NEW.sku), -1) THEN RAISE(ABORT, 'stock_before_mismatch') END; SELECT CASE WHEN NEW.diff_qty_milli != NEW.after_qty_milli - NEW.before_qty_milli THEN RAISE(ABORT, 'stock_diff_mismatch') END; SELECT CASE WHEN NEW.reason NOT IN ('RECEIVE','SALE','RETURN','CANCEL','COUNT_REPLACE') THEN RAISE(ABORT, 'stock_reason_invalid') END; END");
        db.execSQL("DROP TRIGGER IF EXISTS stock_log_apply");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS stock_log_apply AFTER INSERT ON stock_log BEGIN UPDATE product SET stock_qty_milli = NEW.after_qty_milli WHERE sku = NEW.sku; END");
        db.execSQL("DROP TRIGGER IF EXISTS stock_log_no_update");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS stock_log_no_update BEFORE UPDATE ON stock_log BEGIN SELECT RAISE(ABORT, 'stock_log_append_only'); END");
        db.execSQL("DROP TRIGGER IF EXISTS stock_log_no_delete");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS stock_log_no_delete BEFORE DELETE ON stock_log BEGIN SELECT RAISE(ABORT, 'stock_log_append_only'); END");

        db.execSQL("DROP TRIGGER IF EXISTS op_log_insert_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS op_log_insert_guard BEFORE INSERT ON op_log WHEN length(trim(NEW.id)) = 0 OR length(trim(NEW.action)) = 0 OR length(trim(NEW.entity_type)) = 0 OR length(trim(NEW.created_at_utc)) = 0 BEGIN SELECT RAISE(ABORT, 'op_log_invalid'); END");
        db.execSQL("DROP TRIGGER IF EXISTS op_log_no_update");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS op_log_no_update BEFORE UPDATE ON op_log BEGIN SELECT RAISE(ABORT, 'op_log_append_only'); END");
        db.execSQL("DROP TRIGGER IF EXISTS op_log_no_delete");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS op_log_no_delete BEFORE DELETE ON op_log BEGIN SELECT RAISE(ABORT, 'op_log_append_only'); END");
    }

    public static void installBackupJournal(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS backup_journal_insert_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS backup_journal_insert_guard BEFORE INSERT ON backup_journal WHEN length(trim(NEW.operation_id)) = 0 OR length(trim(NEW.op)) = 0 OR length(NEW.payload) = 0 OR length(NEW.payload_sha256) != 64 OR NEW.payload_sha256 GLOB '*[^0-9a-fA-F]*' OR NEW.schema_version < 1 OR length(trim(NEW.created_at_utc)) = 0 OR NEW.uploaded NOT IN (0,1) BEGIN SELECT RAISE(ABORT, 'backup_journal_invalid'); END");
        db.execSQL("DROP TRIGGER IF EXISTS backup_journal_update_guard");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS backup_journal_update_guard BEFORE UPDATE ON backup_journal WHEN OLD.seq != NEW.seq OR OLD.operation_id != NEW.operation_id OR OLD.op != NEW.op OR OLD.payload != NEW.payload OR OLD.payload_sha256 != NEW.payload_sha256 OR OLD.schema_version != NEW.schema_version OR OLD.created_at_utc != NEW.created_at_utc OR OLD.uploaded != 0 OR NEW.uploaded != 1 BEGIN SELECT RAISE(ABORT, 'backup_journal_immutable'); END");
        db.execSQL("DROP TRIGGER IF EXISTS backup_journal_no_delete");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS backup_journal_no_delete BEFORE DELETE ON backup_journal BEGIN SELECT RAISE(ABORT, 'backup_journal_immutable'); END");
    }

    /** Business-ledger invariants required on both fresh installs and upgraded databases. */
    public static void installBusiness(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS product_price_history_guard");
        db.execSQL("CREATE TRIGGER product_price_history_guard BEFORE UPDATE ON product WHEN NEW.price_satang != OLD.price_satang AND NOT EXISTS (SELECT 1 FROM price_history WHERE sku=OLD.sku AND old_price_satang=OLD.price_satang AND new_price_satang=NEW.price_satang AND changed_at_utc=NEW.updated_at_utc) BEGIN SELECT RAISE(ABORT,'price_history_required'); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_insert_guard BEFORE INSERT ON sale WHEN NEW.type NOT IN ('CASH','CREDIT') OR NEW.subtotal_satang<0 OR NEW.fee_total_satang<0 OR NEW.total_satang!=NEW.subtotal_satang+NEW.fee_total_satang OR length(trim(NEW.idem_key))=0 OR length(trim(NEW.content_hash))=0 OR length(trim(NEW.created_at_utc))=0 OR (NEW.type='CASH' AND (NEW.payment_status!='PAID' OR NEW.customer_id IS NOT NULL OR NEW.fee_total_satang!=0 OR NEW.cash_received_satang<NEW.total_satang OR NEW.change_satang!=NEW.cash_received_satang-NEW.total_satang OR NEW.paid_at_utc!=NEW.created_at_utc)) OR (NEW.type='CREDIT' AND (NEW.payment_status!='UNPAID' OR NEW.customer_id IS NULL OR NEW.cash_received_satang IS NOT NULL OR NEW.change_satang IS NOT NULL OR NEW.fee_total_satang!=CAST((NEW.subtotal_satang+10)/20 AS INTEGER))) BEGIN SELECT RAISE(ABORT,'sale_invalid'); END");
        db.execSQL("DROP TRIGGER IF EXISTS sale_update_guard");
        db.execSQL("CREATE TRIGGER sale_update_guard BEFORE UPDATE ON sale WHEN NEW.id!=OLD.id OR NEW.idem_key!=OLD.idem_key OR NEW.type!=OLD.type OR NEW.customer_id IS NOT OLD.customer_id OR NEW.subtotal_satang!=OLD.subtotal_satang OR NEW.fee_total_satang!=OLD.fee_total_satang OR NEW.total_satang!=OLD.total_satang OR NEW.cash_received_satang IS NOT OLD.cash_received_satang OR NEW.change_satang IS NOT OLD.change_satang OR NEW.content_hash!=OLD.content_hash OR NEW.created_at_utc!=OLD.created_at_utc OR (NEW.payment_status!=OLD.payment_status AND NOT (OLD.payment_status='UNPAID' AND NEW.payment_status='PAID' AND NEW.paid_at_utc IS NOT NULL)) OR (NEW.lifecycle_status!=OLD.lifecycle_status AND NOT (OLD.lifecycle_status='COMPLETED' AND NEW.lifecycle_status='CANCELLED')) BEGIN SELECT RAISE(ABORT,'sale_immutable_fields'); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_line_insert_guard BEFORE INSERT ON sale_line WHEN NEW.line_no<=0 OR NEW.qty_milli<=0 OR NEW.price_snap_satang<0 OR NEW.cost_snap_satang<0 OR NEW.line_total_satang!=CAST((NEW.price_snap_satang*NEW.qty_milli+500)/1000 AS INTEGER) BEGIN SELECT RAISE(ABORT,'sale_line_invalid'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_line_no_update BEFORE UPDATE ON sale_line BEGIN SELECT RAISE(ABORT,'sale_line_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_line_no_delete BEFORE DELETE ON sale_line BEGIN SELECT RAISE(ABORT,'sale_line_append_only'); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS debt_payment_insert_guard BEFORE INSERT ON debt_payment WHEN NEW.amount_satang<=0 OR NOT EXISTS (SELECT 1 FROM sale s WHERE s.id=NEW.sale_id AND s.type='CREDIT' AND s.payment_status='UNPAID' AND NEW.amount_satang=s.total_satang-COALESCE((SELECT SUM(debt_reduction_satang) FROM sale_return WHERE sale_id=s.id),0)-COALESCE((SELECT SUM(debt_reduction_satang) FROM sale_cancel WHERE sale_id=s.id),0)) BEGIN SELECT RAISE(ABORT,'debt_payment_amount_invalid'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS debt_payment_no_update BEFORE UPDATE ON debt_payment BEGIN SELECT RAISE(ABORT,'debt_payment_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS debt_payment_no_delete BEFORE DELETE ON debt_payment BEGIN SELECT RAISE(ABORT,'debt_payment_append_only'); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_return_line_insert_guard BEFORE INSERT ON sale_return_line WHEN NEW.qty_milli<=0 OR NEW.value_satang<0 OR NEW.fee_satang<0 OR NEW.restock NOT IN (0,1) OR (NEW.restock=0 AND length(trim(NEW.non_restock_reason))=0) OR (NEW.restock=1 AND NEW.non_restock_reason IS NOT NULL) BEGIN SELECT RAISE(ABORT,'sale_return_line_invalid'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_return_no_update BEFORE UPDATE ON sale_return BEGIN SELECT RAISE(ABORT,'sale_return_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_return_no_delete BEFORE DELETE ON sale_return BEGIN SELECT RAISE(ABORT,'sale_return_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_return_line_no_update BEFORE UPDATE ON sale_return_line BEGIN SELECT RAISE(ABORT,'sale_return_line_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_return_line_no_delete BEFORE DELETE ON sale_return_line BEGIN SELECT RAISE(ABORT,'sale_return_line_append_only'); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_cancel_line_insert_guard BEFORE INSERT ON sale_cancel_line WHEN NEW.qty_milli<=0 OR NEW.value_satang<0 OR NEW.fee_satang<0 OR NEW.restock NOT IN (0,1) OR (NEW.restock=0 AND length(trim(NEW.non_restock_reason))=0) OR (NEW.restock=1 AND NEW.non_restock_reason IS NOT NULL) BEGIN SELECT RAISE(ABORT,'sale_cancel_line_invalid'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_cancel_no_update BEFORE UPDATE ON sale_cancel BEGIN SELECT RAISE(ABORT,'sale_cancel_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_cancel_no_delete BEFORE DELETE ON sale_cancel BEGIN SELECT RAISE(ABORT,'sale_cancel_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_cancel_line_no_update BEFORE UPDATE ON sale_cancel_line BEGIN SELECT RAISE(ABORT,'sale_cancel_line_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_cancel_line_no_delete BEFORE DELETE ON sale_cancel_line BEGIN SELECT RAISE(ABORT,'sale_cancel_line_append_only'); END");

        db.execSQL("CREATE TRIGGER IF NOT EXISTS price_history_no_update BEFORE UPDATE ON price_history BEGIN SELECT RAISE(ABORT,'price_history_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS price_history_no_delete BEFORE DELETE ON price_history BEGIN SELECT RAISE(ABORT,'price_history_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_reconciliation_no_update BEFORE UPDATE ON sale_reconciliation BEGIN SELECT RAISE(ABORT,'sale_reconciliation_immutable'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS sale_reconciliation_no_delete BEFORE DELETE ON sale_reconciliation BEGIN SELECT RAISE(ABORT,'sale_reconciliation_immutable'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS stock_count_line_no_update BEFORE UPDATE ON stock_count_line BEGIN SELECT RAISE(ABORT,'stock_count_line_append_only'); END");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS stock_count_line_no_delete BEFORE DELETE ON stock_count_line BEGIN SELECT RAISE(ABORT,'stock_count_line_append_only'); END");
    }
}
