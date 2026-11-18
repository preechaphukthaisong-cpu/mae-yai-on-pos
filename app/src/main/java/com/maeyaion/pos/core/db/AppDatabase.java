package com.maeyaion.pos.core.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {
        AppMetaEntity.class,
        BarcodeRegistryEntity.class,
        BarcodeCounterEntity.class,
        ProductEntity.class,
        CustomerEntity.class,
        StockLogEntity.class,
        OpLogEntity.class,
        BackupJournalEntity.class
}, version = 4, exportSchema = true)
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
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .addMigrations(Migration1To2.INSTANCE, Migration2To3.INSTANCE, Migration3To4.INSTANCE)
                            .addCallback(new Callback() {
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
                                    db.execSQL("PRAGMA busy_timeout=5000");
                                    db.execSQL("PRAGMA synchronous=FULL");
                                }
                            })
                            .build();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }
}