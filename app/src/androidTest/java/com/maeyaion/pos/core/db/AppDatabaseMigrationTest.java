package com.maeyaion.pos.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

@RunWith(AndroidJUnit4.class)
public final class AppDatabaseMigrationTest {
    private Context context;
    private AppDatabase database;
    private String databaseName;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "pos-test-" + System.nanoTime() + ".db";
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void migrationFromVersionOnePreservesExistingMetadataAndSeedsCounter() {
        File file = context.getDatabasePath(databaseName);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new AssertionError("Could not create database directory");
        }
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
        old.execSQL("CREATE TABLE app_meta (meta_key TEXT NOT NULL, value TEXT NOT NULL, updatedAtUtc TEXT NOT NULL, PRIMARY KEY(meta_key))");
        old.execSQL("INSERT INTO app_meta(meta_key, value, updatedAtUtc) VALUES('legacy_key', 'preserved_value', '2026-11-17T00:00:00Z')");
        old.setVersion(1);
        old.close();

        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .addMigrations(Migration1To2.INSTANCE, Migration2To3.INSTANCE)
                .allowMainThreadQueries()
                .build();

        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        assertEquals(3, sql.getVersion());
        assertEquals("preserved_value", database.appMetaDao().findValue("legacy_key"));
        assertNotNull(database.barcodeCounterDao().get());
        assertEquals(2_000_001L, database.barcodeCounterDao().get().nextValue);
        assertEquals(3, database.getOpenHelper().getWritableDatabase().getVersion());
        assertTableExists(sql, "barcode_registry");
        assertTableExists(sql, "product");
        assertTableExists(sql, "customer");
        assertTableExists(sql, "stock_log");
        assertTableExists(sql, "op_log");
        assertTableExists(sql, "backup_journal");
    }

    @Test public void freshDatabaseSeedsBarcodeCounter() {
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
        assertEquals(2_000_001L, database.barcodeCounterDao().get().nextValue);
    }

    @Test public void stockLedgerIsTheOnlyWayToChangeStockAndRejectsInvalidEntries() {
        openFreshDatabase();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        insertBarcodeAndProduct(sql);
        appendStock(sql, "stock-1", 0, 2_000, 2_000);

        assertEquals(2_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
        assertSqlFails(() -> appendStock(sql, "stock-wrong-before", 0, 3_000, 3_000));
        assertSqlFails(() -> appendStock(sql, "stock-negative", 2_000, -1, -2_001));
        assertSqlFails(() -> sql.execSQL("UPDATE stock_log SET after_qty_milli = 1000 WHERE id = 'stock-1'"));
        assertSqlFails(() -> sql.execSQL("DELETE FROM stock_log WHERE id = 'stock-1'"));
        assertEquals(2_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
    }

    @Test public void failedTransactionRollsBackLedgerAndStockCache() {
        openFreshDatabase();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        insertBarcodeAndProduct(sql);
        try {
            database.runInTransaction(() -> {
                appendStock(sql, "stock-rollback", 0, 5_000, 5_000);
                throw new IllegalStateException("simulated failure");
            });
            fail("Expected transaction failure");
        } catch (IllegalStateException expected) {
            assertEquals("simulated failure", expected.getMessage());
        }
        assertEquals(0L, database.productDao().findBySku("SKU-1").stockQtyMilli);
        try (Cursor cursor = sql.query("SELECT COUNT(*) FROM stock_log")) {
            assertNotNull(cursor);
            cursor.moveToFirst();
            assertEquals(0, cursor.getInt(0));
        }
    }

    @Test public void barcodeRegistryCannotBeUpdatedOrDeletedAndForeignKeysAreEnforced() {
        openFreshDatabase();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        insertBarcodeAndProduct(sql);
        assertSqlFails(() -> sql.execSQL("UPDATE barcode_registry SET display_name = 'changed' WHERE barcode = '000123'"));
        assertSqlFails(() -> sql.execSQL("DELETE FROM barcode_registry WHERE barcode = '000123'"));
        assertSqlFails(() -> sql.execSQL("INSERT INTO product(sku, barcode, name, price_satang, cost_satang, unit, stock_limit_qty_milli, stock_qty_milli, is_active, created_at_utc, updated_at_utc) VALUES('SKU-2', 'missing', 'Bad FK', 1, 1, 'ชิ้น', NULL, 0, 1, 'now', 'now')"));
        assertNotNull(database.productDao().findBySku("SKU-1"));
        assertNull(database.productDao().findBySku("SKU-2"));
    }


    @Test public void backupJournalIsOrderedAndOnlyUploadFlagCanChange() {
        openFreshDatabase();
        BackupJournalEntity first = new BackupJournalEntity(0, "op-1", "PRODUCT_CREATE",
                "{\"sku\":\"SKU-1\"}", "sha256-1", 3,
                "2026-11-17T00:00:00Z", 0);
        long seq = database.backupJournalDao().append(first);
        assertEquals(1L, database.backupJournalDao().pendingCount());
        assertEquals(1, database.backupJournalDao().pendingBatch(10).size());
        assertEquals(1, database.backupJournalDao().markUploaded(seq));
        assertEquals(0L, database.backupJournalDao().pendingCount());

        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        assertSqlFails(() -> sql.execSQL("UPDATE backup_journal SET payload = '{}' WHERE seq = " + seq));
        assertSqlFails(() -> sql.execSQL("DELETE FROM backup_journal WHERE seq = " + seq));
        assertEquals(0, database.backupJournalDao().markUploaded(seq));
    }

    private void openFreshDatabase() {
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
    }

    private static void insertBarcodeAndProduct(SupportSQLiteDatabase sql) {
        sql.execSQL("INSERT INTO barcode_registry(barcode, display_name, price_satang, source_type, created_at_utc) VALUES('000123', 'Test item', 100, 'MANUFACTURER', '2026-11-17T00:00:00Z')");
        sql.execSQL("INSERT INTO product(sku, barcode, name, price_satang, cost_satang, unit, stock_limit_qty_milli, stock_qty_milli, is_active, created_at_utc, updated_at_utc) VALUES('SKU-1', '000123', 'Test item', 100, 50, 'ชิ้น', NULL, 0, 1, '2026-11-17T00:00:00Z', '2026-11-17T00:00:00Z')");
    }

    private static void appendStock(SupportSQLiteDatabase sql, String id, long before, long after, long diff) {
        sql.execSQL("INSERT INTO stock_log(id, sku, before_qty_milli, after_qty_milli, diff_qty_milli, reason, ref_id, created_at_utc) VALUES(?, 'SKU-1', ?, ?, ?, 'RECEIVE', NULL, '2026-11-17T00:00:00Z')",
                new Object[]{id, before, after, diff});
    }

    private static void assertTableExists(SupportSQLiteDatabase db, String table) {
        try (Cursor cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", new Object[]{table})) {
            assertNotNull(cursor);
            assertEquals(true, cursor.moveToFirst());
        }
    }

    private static void assertSqlFails(Runnable action) {
        try {
            action.run();
            fail("Expected SQLite constraint/trigger failure");
        } catch (android.database.sqlite.SQLiteException expected) {
            // Expected database-level guard.
        }
    }
}
