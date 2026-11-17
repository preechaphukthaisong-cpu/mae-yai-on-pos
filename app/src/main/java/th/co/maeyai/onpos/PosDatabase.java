package th.co.maeyai.onpos;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.TimeZone;
import java.util.UUID;

public final class PosDatabase extends SQLiteOpenHelper {
    private static volatile PosDatabase instance;

    public static PosDatabase get(Context context) {
        if (instance == null) synchronized (PosDatabase.class) {
            if (instance == null) instance = new PosDatabase(context.getApplicationContext());
        }
        return instance;
    }

    static synchronized void closeForRestore() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private PosDatabase(Context context) {
        super(context, "mae_yai_on_pos.db", null, 2);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.execSQL("PRAGMA busy_timeout=5000");
        db.execSQL("PRAGMA synchronous=FULL");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createCoreSchema(db);
        db.execSQL("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
        db.execSQL("INSERT INTO schema_migrations VALUES(1,'initial_core_schema','" + now() + "')");
        migrateTo2(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1) throw new IllegalStateException("Unsupported database version: " + oldVersion);
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
            db.execSQL("INSERT OR IGNORE INTO schema_migrations(version,name,applied_at) VALUES(1,'legacy_v1_baseline','" + now() + "')");
            migrateTo2(db);
        }
        if (newVersion > 2) throw new IllegalStateException("Missing migration for database version " + newVersion);
    }

    private static void migrateTo2(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS backup_log(id TEXT PRIMARY KEY, operation TEXT NOT NULL CHECK(operation IN('export','restore')), result TEXT NOT NULL CHECK(result IN('success','failure')), created_at TEXT NOT NULL, detail TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_backup_log_created ON backup_log(created_at DESC)");
        db.execSQL("INSERT OR IGNORE INTO schema_migrations(version,name,applied_at) VALUES(2,'backup_and_migration_tracking','" + now() + "')");
    }

    private static void createCoreSchema(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE products(id TEXT PRIMARY KEY, barcode TEXT UNIQUE, name TEXT NOT NULL, unit TEXT NOT NULL DEFAULT 'ชิ้น', price_sat INTEGER NOT NULL CHECK(price_sat>=0), cost_sat INTEGER NOT NULL DEFAULT 0 CHECK(cost_sat>=0), stock INTEGER NOT NULL DEFAULT 0 CHECK(stock>=0), track_stock INTEGER NOT NULL DEFAULT 1 CHECK(track_stock IN(0,1)), low_limit INTEGER NOT NULL DEFAULT 0 CHECK(low_limit>=0), active INTEGER NOT NULL DEFAULT 1, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE customers(id TEXT PRIMARY KEY,name TEXT NOT NULL,phone TEXT,created_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE sales(id TEXT PRIMARY KEY,receipt_no TEXT NOT NULL UNIQUE,customer_id TEXT REFERENCES customers(id) ON DELETE RESTRICT,status TEXT NOT NULL CHECK(status IN('confirmed','voided')),kind TEXT NOT NULL CHECK(kind IN('cash','credit')),subtotal_sat INTEGER NOT NULL CHECK(subtotal_sat>=0),paid_sat INTEGER NOT NULL CHECK(paid_sat>=0),created_at TEXT NOT NULL,idempotency_key TEXT NOT NULL UNIQUE)");
        d.execSQL("CREATE TABLE sale_items(id TEXT PRIMARY KEY,sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,name_snapshot TEXT NOT NULL,barcode_snapshot TEXT,qty INTEGER NOT NULL CHECK(qty>0),unit_price_sat INTEGER NOT NULL CHECK(unit_price_sat>=0),unit_cost_sat INTEGER NOT NULL CHECK(unit_cost_sat>=0),line_total_sat INTEGER NOT NULL CHECK(line_total_sat>=0))");
        d.execSQL("CREATE TABLE payments(id TEXT PRIMARY KEY,sale_id TEXT NOT NULL REFERENCES sales(id) ON DELETE RESTRICT,amount_sat INTEGER NOT NULL CHECK(amount_sat>0),method TEXT NOT NULL,created_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE debts(id TEXT PRIMARY KEY,sale_id TEXT NOT NULL UNIQUE REFERENCES sales(id) ON DELETE RESTRICT,customer_id TEXT NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,original_sat INTEGER NOT NULL CHECK(original_sat>0),paid_sat INTEGER NOT NULL DEFAULT 0 CHECK(paid_sat>=0 AND paid_sat<=original_sat),status TEXT NOT NULL CHECK(status IN('unpaid','partial','paid')),created_at TEXT NOT NULL,paid_at TEXT)");
        d.execSQL("CREATE TABLE debt_payments(id TEXT PRIMARY KEY,debt_id TEXT NOT NULL REFERENCES debts(id) ON DELETE RESTRICT,amount_sat INTEGER NOT NULL CHECK(amount_sat>0),received_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE inventory_ledger(id TEXT PRIMARY KEY,product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,delta INTEGER NOT NULL CHECK(delta<>0),before_qty INTEGER NOT NULL,after_qty INTEGER NOT NULL CHECK(after_qty>=0),reason TEXT NOT NULL,reference_id TEXT,created_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE price_history(id TEXT PRIMARY KEY,product_id TEXT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,old_price_sat INTEGER NOT NULL,new_price_sat INTEGER NOT NULL,changed_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE audit_log(id TEXT PRIMARY KEY,entity TEXT NOT NULL,entity_id TEXT NOT NULL,action TEXT NOT NULL,detail TEXT,created_at TEXT NOT NULL)");
        d.execSQL("CREATE TABLE barcode_counter(id INTEGER PRIMARY KEY CHECK(id=1),next_value INTEGER NOT NULL CHECK(next_value>=2000001))");
        d.execSQL("INSERT INTO barcode_counter VALUES(1,2000001)");
        d.execSQL("CREATE TABLE daily_summary(day TEXT PRIMARY KEY,sales_count INTEGER NOT NULL,sales_sat INTEGER NOT NULL,cost_sat INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX idx_products_name ON products(name COLLATE NOCASE)");
        d.execSQL("CREATE INDEX idx_sales_date ON sales(created_at DESC)");
        d.execSQL("CREATE INDEX idx_debts_status ON debts(status,customer_id)");
        d.execSQL("CREATE TRIGGER ledger_no_update BEFORE UPDATE ON inventory_ledger BEGIN SELECT RAISE(ABORT,'append-only ledger'); END");
        d.execSQL("CREATE TRIGGER ledger_no_delete BEFORE DELETE ON inventory_ledger BEGIN SELECT RAISE(ABORT,'append-only ledger'); END");
        d.execSQL("CREATE TRIGGER payment_no_update BEFORE UPDATE ON payments BEGIN SELECT RAISE(ABORT,'immutable payment'); END");
        d.execSQL("CREATE TRIGGER payment_no_delete BEFORE DELETE ON payments BEGIN SELECT RAISE(ABORT,'immutable payment'); END");
    }

    public static String id() { return UUID.randomUUID().toString(); }

    public static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
