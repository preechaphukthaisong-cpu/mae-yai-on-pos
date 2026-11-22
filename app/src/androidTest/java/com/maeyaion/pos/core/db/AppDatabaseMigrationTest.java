package com.maeyaion.pos.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(Migration1To2.INSTANCE, Migration2To3.INSTANCE, Migration3To4.INSTANCE, Migration4To5.INSTANCE, Migration5To6.INSTANCE, Migration6To7.INSTANCE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();

        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        assertEquals(7, sql.getVersion());
        assertEquals("preserved_value", database.appMetaDao().findValue("legacy_key"));
        assertNotNull(database.barcodeCounterDao().get());
        assertEquals(2_000_001L, database.barcodeCounterDao().get().nextValue);
        assertEquals(6, database.getOpenHelper().getWritableDatabase().getVersion());
        assertTableExists(sql, "barcode_registry");
        assertTableExists(sql, "product");
        assertTableExists(sql, "customer");
        assertTableExists(sql, "stock_log");
        assertTableExists(sql, "op_log");
        assertTableExists(sql, "backup_journal");
        assertTableExists(sql, "sync_outbox");
        assertTableExists(sql, "sync_inbox");
        assertTableExists(sql, "sync_cursor");
        assertTableExists(sql, "sync_entity_version");
        assertTableExists(sql, "sync_replay_conflict");
        assertCoreGuardsExist(sql);
        assertTriggerExists(sql, "backup_journal_insert_guard");
        assertTriggerExists(sql, "backup_journal_update_guard");
        assertTriggerExists(sql, "backup_journal_no_delete");
    }

    @Test public void migrationFromVersionThreeRefreshesGuardsAndPreservesData() {
        File file = context.getDatabasePath(databaseName);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new AssertionError("Could not create database directory");
        }
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
        old.execSQL("CREATE TABLE app_meta (meta_key TEXT NOT NULL, value TEXT NOT NULL, updatedAtUtc TEXT NOT NULL, PRIMARY KEY(meta_key))");
        old.execSQL("INSERT INTO app_meta(meta_key, value, updatedAtUtc) VALUES('legacy_key', 'v3_value', '2026-11-17T00:00:00Z')");
        old.execSQL("CREATE TABLE barcode_registry (barcode TEXT NOT NULL, display_name TEXT NOT NULL, price_satang INTEGER NOT NULL, source_type TEXT NOT NULL, created_at_utc TEXT NOT NULL, PRIMARY KEY(barcode))");
        old.execSQL("CREATE TABLE barcode_counter (id INTEGER NOT NULL PRIMARY KEY, next_value INTEGER NOT NULL)");
        old.execSQL("INSERT INTO barcode_counter(id, next_value) VALUES(1, 2000001)");
        old.execSQL("CREATE TABLE product (sku TEXT NOT NULL PRIMARY KEY, barcode TEXT NOT NULL, name TEXT NOT NULL, price_satang INTEGER NOT NULL, cost_satang INTEGER NOT NULL, unit TEXT NOT NULL, stock_limit_qty_milli INTEGER, stock_qty_milli INTEGER NOT NULL DEFAULT 0, is_active INTEGER NOT NULL DEFAULT 1, created_at_utc TEXT NOT NULL, updated_at_utc TEXT NOT NULL, FOREIGN KEY(barcode) REFERENCES barcode_registry(barcode) ON UPDATE RESTRICT ON DELETE RESTRICT)");
        old.execSQL("CREATE UNIQUE INDEX index_product_barcode ON product(barcode)");
        old.execSQL("CREATE INDEX index_product_name ON product(name)");
        old.execSQL("CREATE INDEX index_product_is_active_stock_qty_milli ON product(is_active, stock_qty_milli)");
        old.execSQL("CREATE TABLE customer (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, name_key TEXT NOT NULL, identity_key TEXT NOT NULL, created_at_utc TEXT NOT NULL, updated_at_utc TEXT NOT NULL)");
        old.execSQL("CREATE UNIQUE INDEX index_customer_name_key_identity_key ON customer(name_key, identity_key)");
        old.execSQL("CREATE TABLE stock_log (id TEXT NOT NULL PRIMARY KEY, sku TEXT NOT NULL, before_qty_milli INTEGER NOT NULL, after_qty_milli INTEGER NOT NULL, diff_qty_milli INTEGER NOT NULL, reason TEXT NOT NULL, ref_id TEXT, created_at_utc TEXT NOT NULL, FOREIGN KEY(sku) REFERENCES product(sku) ON UPDATE RESTRICT ON DELETE RESTRICT)");
        old.execSQL("CREATE INDEX index_stock_log_sku_created_at_utc ON stock_log(sku, created_at_utc)");
        old.execSQL("CREATE INDEX index_stock_log_ref_id ON stock_log(ref_id)");
        old.execSQL("CREATE TABLE op_log (id TEXT NOT NULL PRIMARY KEY, action TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT, detail_json TEXT, created_at_utc TEXT NOT NULL)");
        old.execSQL("CREATE INDEX index_op_log_created_at_utc ON op_log(created_at_utc)");
        old.execSQL("CREATE INDEX index_op_log_entity_type_entity_id ON op_log(entity_type, entity_id)");
        old.execSQL("CREATE TABLE backup_journal (seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, operation_id TEXT NOT NULL, op TEXT NOT NULL, payload TEXT NOT NULL, payload_sha256 TEXT NOT NULL, schema_version INTEGER NOT NULL, created_at_utc TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)");
        old.execSQL("CREATE UNIQUE INDEX index_backup_journal_operation_id ON backup_journal(operation_id)");
        old.execSQL("CREATE INDEX index_backup_journal_uploaded_seq ON backup_journal(uploaded, seq)");
        old.execSQL("INSERT INTO barcode_registry(barcode, display_name, price_satang, source_type, created_at_utc) VALUES('009999', 'Legacy item', 100, 'MANUFACTURER', '2026-11-17T00:00:00Z')");
        old.execSQL("INSERT INTO product(sku, barcode, name, price_satang, cost_satang, unit, stock_limit_qty_milli, stock_qty_milli, is_active, created_at_utc, updated_at_utc) VALUES('SKU-LEGACY', '009999', 'Legacy item', 100, 50, 'ชิ้น', NULL, 2500, 1, '2026-11-17T00:00:00Z', '2026-11-17T00:00:00Z')");
        old.execSQL("CREATE TRIGGER stock_log_validate BEFORE INSERT ON stock_log BEGIN SELECT RAISE(ABORT, 'obsolete_v3_guard'); END");
        old.setVersion(3);
        old.close();

        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(Migration3To4.INSTANCE, Migration4To5.INSTANCE, Migration5To6.INSTANCE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        assertEquals(6, sql.getVersion());
        assertEquals("v3_value", database.appMetaDao().findValue("legacy_key"));
        assertTableExists(sql, "sync_outbox");
        assertTableExists(sql, "sync_inbox");
        assertTableExists(sql, "sync_cursor");
        assertTableExists(sql, "sync_entity_version");
        assertCoreGuardsExist(sql);
        assertEquals(2500L, database.productDao().findBySku("SKU-LEGACY").stockQtyMilli);
        try (Cursor cursor = sql.query("SELECT before_qty_milli, after_qty_milli, diff_qty_milli FROM stock_log WHERE sku = 'SKU-LEGACY' AND ref_id = 'MIGRATION_V3_BASELINE'")) {
            assertNotNull(cursor);
            assertEquals(true, cursor.moveToFirst());
            assertEquals(0L, cursor.getLong(0));
            assertEquals(2500L, cursor.getLong(1));
            assertEquals(2500L, cursor.getLong(2));
        }
        insertBarcodeAndProduct(sql);
        appendStock(sql, "stock-after-v3", 0, 1_000, 1_000);
        assertEquals(1_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
    }

    @Test public void freshDatabaseSeedsBarcodeCounter() {
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
        assertEquals(2_000_001L, database.barcodeCounterDao().get().nextValue);
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        assertCoreGuardsExist(sql);
        assertTriggerExists(sql, "backup_journal_insert_guard");
        assertTriggerExists(sql, "backup_journal_update_guard");
        assertTriggerExists(sql, "backup_journal_no_delete");
    }

    @Test public void stockLedgerIsTheOnlyWayToChangeStockAndRejectsInvalidEntries() {
        openFreshDatabase();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        insertBarcodeAndProduct(sql);
        sql.execSQL("INSERT INTO barcode_registry(barcode, display_name, price_satang, source_type, created_at_utc) VALUES('000124', 'Invalid opening stock', 100, 'MANUFACTURER', '2026-11-17T00:00:00Z')");
        assertSqlFails(() -> sql.execSQL("INSERT INTO product(sku, barcode, name, price_satang, cost_satang, unit, stock_limit_qty_milli, stock_qty_milli, is_active, created_at_utc, updated_at_utc) VALUES('SKU-OPENING', '000124', 'Invalid opening stock', 100, 50, 'ชิ้น', NULL, 1, 1, 'now', 'now')"));
        assertSqlFails(() -> sql.execSQL("UPDATE product SET stock_qty_milli = 500 WHERE sku = 'SKU-1'"));
        assertSqlFails(() -> sql.execSQL("UPDATE product SET price_satang = -1 WHERE sku = 'SKU-1'"));
        assertSqlFails(() -> sql.execSQL("DELETE FROM product WHERE sku = 'SKU-1'"));
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
        assertSqlFails(() -> sql.execSQL("DELETE FROM barcode_counter WHERE id = 1"));
        sql.execSQL("INSERT INTO op_log(id, action, entity_type, entity_id, detail_json, created_at_utc) VALUES('op-1', 'TEST', 'PRODUCT', 'SKU-1', NULL, '2026-11-17T00:00:00Z')");
        assertSqlFails(() -> sql.execSQL("UPDATE op_log SET action = 'CHANGED' WHERE id = 'op-1'"));
        assertSqlFails(() -> sql.execSQL("DELETE FROM op_log WHERE id = 'op-1'"));
    }


    @Test public void backupJournalIsOrderedAndOnlyUploadFlagCanChange() {
        openFreshDatabase();
        BackupJournalEntity first = new BackupJournalEntity(0, "op-1", "PRODUCT_CREATE",
                "{\"sku\":\"SKU-1\"}", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 4,
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

    @Test public void databaseConnectionPragmasAreAppliedAndVerified() {
        openFreshDatabase();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        assertPragmaLong(sql, "PRAGMA foreign_keys", 1L);
        long busyTimeoutMs = readPragmaLong(sql, "PRAGMA busy_timeout");
        assertEquals("SQLite busy timeout must be exactly five seconds", 5000L, busyTimeoutMs);
        assertPragmaLong(sql, "PRAGMA synchronous", 2L);
        try (Cursor cursor = sql.query("PRAGMA journal_mode")) {
            assertNotNull(cursor);
            assertEquals(true, cursor.moveToFirst());
            assertEquals("wal", cursor.getString(0).toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test public void durabilityPragmasRemainConfiguredUnderConcurrentRoomAccess() throws Exception {
        openFreshDatabase();
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        final int callers = 8;
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<long[]>> checks = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                checks.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Concurrent durability check did not start");
                    }
                    long busyTimeout = readPragmaLong(sql, "PRAGMA busy_timeout");
                    long synchronous = readPragmaLong(sql, "PRAGMA synchronous");
                    return new long[]{busyTimeout, synchronous};
                }));
            }
            start.countDown();
            for (Future<long[]> check : checks) {
                long[] values = check.get(15, TimeUnit.SECONDS);
                assertEquals("busy_timeout must remain exactly five seconds", 5000L, values[0]);
                assertEquals("synchronous must remain FULL", 2L, values[1]);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test public void busyTimeoutWaitsForExternalWriterAndDurabilityIsReappliedAfterReopen()
            throws Exception {
        openFreshDatabase();
        File databaseFile = context.getDatabasePath(databaseName);
        SQLiteDatabase blocker = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(),
                null, SQLiteDatabase.OPEN_READWRITE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            blocker.beginTransaction();
            blocker.execSQL("INSERT INTO app_meta(meta_key, value, updatedAtUtc) "
                    + "VALUES('external_lock', 'held', '2026-11-19T00:00:00Z')");
            CountDownLatch attemptingWrite = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicLong completedAt = new java.util.concurrent.atomic.AtomicLong();
            long startedAt = System.nanoTime();
            Future<?> pendingWrite = executor.submit(() -> {
                attemptingWrite.countDown();
                database.appMetaDao().put(new AppMetaEntity(
                        "after_external_lock", "committed", "2026-11-19T00:00:01Z"));
                completedAt.set(System.nanoTime());
            });
            assertTrue("Room write did not start", attemptingWrite.await(5, TimeUnit.SECONDS));
            Thread.sleep(3_000L);
            blocker.setTransactionSuccessful();
            blocker.endTransaction();
            pendingWrite.get(8, TimeUnit.SECONDS);
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(completedAt.get() - startedAt);
            assertTrue("Room writer should have waited for the external SQLite lock",
                    waitedMs >= 2_500L);
            assertEquals("committed", database.appMetaDao().findValue("after_external_lock"));
        } finally {
            if (blocker.inTransaction()) blocker.endTransaction();
            blocker.close();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        database.close();
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        SupportSQLiteDatabase reopened = database.getOpenHelper().getWritableDatabase();
        assertPragmaLong(reopened, "PRAGMA busy_timeout", 5_000L);
        assertPragmaLong(reopened, "PRAGMA synchronous", 2L);
        try (Cursor cursor = reopened.query("PRAGMA journal_mode")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("wal", cursor.getString(0).toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static void assertPragmaLong(SupportSQLiteDatabase db, String pragma, long expected) {
        assertEquals(expected, readPragmaLong(db, pragma));
    }

    private static long readPragmaLong(SupportSQLiteDatabase db, String pragma) {
        try (Cursor cursor = db.query(pragma)) {
            assertNotNull(cursor);
            assertEquals(true, cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private void openFreshDatabase() {
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addCallback(AppDatabase.databaseCallback())
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

    private static void assertCoreGuardsExist(SupportSQLiteDatabase db) {
        String[] names = {"barcode_registry_insert_guard", "barcode_registry_no_update",
                "barcode_registry_no_delete", "barcode_counter_insert_guard",
                "barcode_counter_update_guard", "barcode_counter_no_delete",
                "product_insert_guard", "product_update_guard",
                "product_identity_immutable", "product_no_delete",
                "stock_log_validate", "stock_log_apply",
                "stock_log_no_update", "stock_log_no_delete",
                "op_log_insert_guard", "op_log_no_update", "op_log_no_delete"};
        for (String name : names) assertTriggerExists(db, name);
    }

    private static void assertTriggerExists(SupportSQLiteDatabase db, String trigger) {
        try (Cursor cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='trigger' AND name=?",
                new Object[]{trigger})) {
            assertNotNull(cursor);
            assertEquals("Missing trigger: " + trigger, true, cursor.moveToFirst());
        }
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
