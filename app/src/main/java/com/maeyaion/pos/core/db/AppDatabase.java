package com.maeyaion.pos.core.db;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {
        AppMetaEntity.class,
        BarcodeRegistryEntity.class,
        BarcodeCounterEntity.class,
        ProductEntity.class,
        CustomerEntity.class,
        StockLogEntity.class,
        OpLogEntity.class,
        BackupJournalEntity.class,
        SaleEntity.class,
        SaleLineEntity.class,
        DebtPaymentEntity.class,
        PriceHistoryEntity.class,
        SaleReturnEntity.class,
        SaleReturnLineEntity.class,
        SaleCancelEntity.class,
        SaleCancelLineEntity.class,
        DailySummaryEntity.class,
        ProductDailyEntity.class,
        SaleReconciliationEntity.class,
        StockCountEntity.class,
        StockCountLineEntity.class
}, version = 5, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract AppMetaDao appMetaDao();
    public abstract BarcodeRegistryDao barcodeRegistryDao();
    public abstract BarcodeCounterDao barcodeCounterDao();
    public abstract ProductDao productDao();
    public abstract CustomerDao customerDao();
    public abstract StockLogDao stockLogDao();
    public abstract OpLogDao opLogDao();
    public abstract BackupJournalDao backupJournalDao();
    public abstract SaleDao saleDao();
    public abstract SaleLineDao saleLineDao();
    public abstract DebtPaymentDao debtPaymentDao();
    public abstract PriceHistoryDao priceHistoryDao();
    public abstract DailySummaryDao dailySummaryDao();
    public abstract ProductDailyDao productDailyDao();
    public abstract SaleBalanceDao saleBalanceDao();

    public static RoomDatabase.Callback databaseCallback() {
        return new RoomDatabase.Callback() {
            @Override
            public void onCreate(@NonNull SupportSQLiteDatabase db) {
                super.onCreate(db);
                db.execSQL("INSERT OR IGNORE INTO barcode_counter (id, next_value) VALUES (1, 2000001)");
                DatabaseGuards.installCore(db);
                DatabaseGuards.installBackupJournal(db);
            }

            @Override
            public void onOpen(@NonNull SupportSQLiteDatabase db) {
                super.onOpen(db);
                db.setForeignKeyConstraintsEnabled(true);
                applyDurabilityPragmas(db);
            }
        };
    }

    /**
     * The database is deliberately opened by Room without WAL connection pooling,
     * then switched to SQLite WAL mode below. Android's public per-connection SQL
     * API cannot execute PRAGMAs that return rows (busy_timeout does), and WAL
     * pooling has no public connection-created callback. Keeping one SQLite
     * connection makes the D-064 settings deterministic on API 26+ while retaining
     * WAL journaling and FULL synchronous durability.
     */
    private static void applyDurabilityPragmas(@NonNull SupportSQLiteDatabase db) {
        executePragma(db, "PRAGMA journal_mode=WAL");
        executePragma(db, "PRAGMA busy_timeout=5000");
        executePragma(db, "PRAGMA synchronous=FULL");
    }

    /**
     * PRAGMA statements can return rows. SupportSQLiteDatabase.execSQL is for
     * statements that do not return a result set; use query and always drain
     * and close the cursor for assignment PRAGMAs.
     */
    private static void executePragma(@NonNull SupportSQLiteDatabase db, @NonNull String sql) {
        try (Cursor cursor = db.query(sql)) {
            while (cursor.moveToNext()) {
                // Drain any result row produced by SQLite.
            }
        }
    }

    public static AppDatabase getInstance(Context context) {
        AppDatabase local = INSTANCE;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = INSTANCE;
                if (local == null) {
                    local = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "maeyaion_pos.db")
                            .setJournalMode(JournalMode.TRUNCATE)
                            .addMigrations(Migration1To2.INSTANCE, Migration2To3.INSTANCE, Migration3To4.INSTANCE, Migration4To5.INSTANCE)
                            .addCallback(databaseCallback())
                            .build();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    /**
     * Closes and forgets the process singleton before a validated database file
     * is atomically replaced during restore. Callers must hold the maintenance
     * gate and synchronize on AppDatabase.class for the full replacement.
     */
    public static void closeInstanceForRestore() {
        synchronized (AppDatabase.class) {
            AppDatabase current = INSTANCE;
            INSTANCE = null;
            if (current != null && current.isOpen()) {
                current.close();
            }
        }
    }
}