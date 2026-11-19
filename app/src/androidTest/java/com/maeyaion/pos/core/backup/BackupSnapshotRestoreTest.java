package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.AppMetaEntity;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.barcode.BarcodeAllocator;
import com.maeyaion.pos.core.barcode.ProductRegistrationService;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class BackupSnapshotRestoreTest {
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();
    private Context context;
    private AppDatabase database;
    private String databaseName;
    private File backupDirectory;
    private File stagingDirectory;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "backup-test-" + UUID.randomUUID() + ".db";
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
        backupDirectory = new File(context.getFilesDir(), "backup-test-" + UUID.randomUUID());
        stagingDirectory = new File(context.getFilesDir(), "restore-test-" + UUID.randomUUID());
        assertTrue(backupDirectory.mkdirs());
        assertTrue(stagingDirectory.mkdirs());
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
        deleteRecursively(backupDirectory);
        deleteRecursively(stagingDirectory);
    }

    @Test public void snapshotEncryptAndStagedRestorePreserveCommittedRows() throws Exception {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        transactions.runInTransaction(() -> {
            database.appMetaDao().put(new AppMetaEntity(
                    "snapshot_probe", "committed-before-snapshot", "2026-11-18T10:00:00Z"));
            return null;
        });

        RoomDatabaseSnapshotter.Snapshot snapshot = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        assertTrue(snapshot.file.isFile());
        assertTrue(snapshot.sizeBytes > 0);
        assertEquals(4, snapshot.schemaVersion);
        assertEquals(64, snapshot.sha256.length());

        byte[] rawSnapshot = Files.readAllBytes(snapshot.file.toPath());
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        EncryptedBackupFileStore store = new EncryptedBackupFileStore();
        File encrypted = store.writeBackup(backupDirectory, rawSnapshot, PASSWORD, recoveryKey);
        assertTrue(encrypted.isFile());
        assertTrue(encrypted.length() > 0);

        File restored = store.restoreToStaging(stagingDirectory, encrypted, PASSWORD, null,
                new SQLiteStagedRestoreValidator(4));
        assertTrue(restored.isFile());
        SQLiteDatabase restoredDb = SQLiteDatabase.openDatabase(
                restored.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = restoredDb.rawQuery(
                "SELECT value FROM app_meta WHERE meta_key='snapshot_probe'", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("committed-before-snapshot", cursor.getString(0));
        } finally {
            restoredDb.close();
        }

        File restoredByRecoveryKey = store.restoreToStaging(stagingDirectory, encrypted, null,
                recoveryKey, new SQLiteStagedRestoreValidator(4));
        assertTrue(restoredByRecoveryKey.isFile());

        AndroidKeystoreKeyProtector localProtector = new AndroidKeystoreKeyProtector();
        File localEncrypted = store.writeBackupWithLocalKey(backupDirectory, rawSnapshot,
                PASSWORD, recoveryKey, localProtector);
        File restoredLocally = store.restoreWithLocalKeyToStaging(stagingDirectory,
                localEncrypted, localProtector, new SQLiteStagedRestoreValidator(4));
        assertTrue(restoredLocally.isFile());
        File restoredPortablePassword = store.restoreToStaging(stagingDirectory,
                localEncrypted, PASSWORD, null, new SQLiteStagedRestoreValidator(4));
        assertTrue(restoredPortablePassword.isFile());
        File restoredPortableRecovery = store.restoreToStaging(stagingDirectory,
                localEncrypted, null, recoveryKey, new SQLiteStagedRestoreValidator(4));
        assertTrue(restoredPortableRecovery.isFile());
    }

    @Test public void wrongPasswordAndInvalidSqlitePayloadNeverPublishStagedDatabase()
            throws Exception {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        transactions.runInTransaction(() -> {
            database.appMetaDao().put(new AppMetaEntity(
                    "snapshot_probe", "value", "2026-11-18T10:00:00Z"));
            return null;
        });
        RoomDatabaseSnapshotter.Snapshot snapshot = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        EncryptedBackupFileStore store = new EncryptedBackupFileStore();
        File encrypted = store.writeBackup(backupDirectory,
                Files.readAllBytes(snapshot.file.toPath()), PASSWORD, recoveryKey);

        try {
            store.restoreToStaging(stagingDirectory, encrypted, "wrong password".toCharArray(),
                    null, new SQLiteStagedRestoreValidator(4));
            fail("Wrong password must not restore");
        } catch (GeneralSecurityException expected) {
            // Authenticated decryption must fail before staging publication.
        }

        byte[] invalidSqlite = "not a sqlite database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File invalidEncrypted = store.writeBackup(backupDirectory, invalidSqlite, PASSWORD, recoveryKey);
        try {
            store.restoreToStaging(stagingDirectory, invalidEncrypted, PASSWORD, null,
                    new SQLiteStagedRestoreValidator(4));
            fail("Invalid SQLite content must not be staged");
        } catch (java.io.IOException expected) {
            // The validator rejects non-database plaintext before publication.
        }
    }



    @Test public void deltaReplayAppliesToStagingAndIsIdempotent() throws Exception {
        RoomDatabaseSnapshotter.Snapshot baseline = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-staging.sqlite");
        Files.copy(baseline.file.toPath(), staging.toPath());

        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        BarcodeAllocator allocator = new BarcodeAllocator(database.barcodeRegistryDao(),
                database.barcodeCounterDao(), transactions);
        ProductRegistrationService products = new ProductRegistrationService(allocator,
                database.productDao(), database.opLogDao(), database.backupJournalDao(), transactions);
        String operationId = UUID.randomUUID().toString();
        products.createWithSystemBarcode(operationId, "DELTA-SKU-1", "Delta product",
                1250L, 700L, "ชิ้น", null,
                "2026-11-19T00:10:00Z", "2026-11-19T00:10:00Z");

        java.util.List<BackupJournalEntity> rows = database.backupJournalDao().pendingBatch(10);
        byte[] delta = DeltaBackupCoordinator.buildPayload(rows, 4);
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] envelope = BackupCrypto.encrypt(delta, PASSWORD, recoveryKey);
        File encryptedDelta = new File(backupDirectory, "delta-1.myobak");
        Files.write(encryptedDelta.toPath(), envelope);
        Arrays.fill(delta, (byte) 0);
        Arrays.fill(envelope, (byte) 0);

        DeltaReplayCoordinator replay = new DeltaReplayCoordinator();
        DeltaReplayCoordinator.ApplyResult first = replay.applyToStaging(context, staging,
                encryptedDelta, PASSWORD, null);
        assertEquals(1, first.appliedCount);
        assertTrue(!first.alreadyApplied);
        assertEquals(1L, first.lastSequence);

        DeltaReplayCoordinator.ApplyResult duplicate = replay.applyToStaging(context, staging,
                encryptedDelta, PASSWORD, null);
        assertEquals(0, duplicate.appliedCount);
        assertTrue(duplicate.alreadyApplied);
        assertEquals(1L, duplicate.lastSequence);

        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = stagedDb.rawQuery(
                "SELECT barcode, price_satang FROM product WHERE sku='DELTA-SKU-1'", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("2000001", cursor.getString(0));
            assertEquals(1250L, cursor.getLong(1));
        } finally {
            stagedDb.close();
        }
        // Replay operates on the staging file only; it does not mutate the source Room database.
        assertTrue(database.productDao().findBySku("DELTA-SKU-1") != null);
    }


    @Test public void duplicateDeltaAcknowledgesPendingRowsAlreadyInFullSnapshot() throws Exception {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        BarcodeAllocator allocator = new BarcodeAllocator(database.barcodeRegistryDao(),
                database.barcodeCounterDao(), transactions);
        ProductRegistrationService products = new ProductRegistrationService(allocator,
                database.productDao(), database.opLogDao(), database.backupJournalDao(), transactions);
        products.createWithSystemBarcode(UUID.randomUUID().toString(), "SNAPSHOT-SKU",
                "Snapshot product", 500L, 250L, "ชิ้น", null,
                "2026-11-19T00:40:00Z", "2026-11-19T00:40:00Z");

        java.util.List<BackupJournalEntity> pending = database.backupJournalDao().pendingBatch(10);
        assertEquals(1, pending.size());
        assertEquals(0, pending.get(0).uploaded);
        RoomDatabaseSnapshotter.Snapshot snapshot = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-existing-snapshot.sqlite");
        Files.copy(snapshot.file.toPath(), staging.toPath());

        byte[] delta = DeltaBackupCoordinator.buildPayload(pending, 4);
        byte[] envelope = BackupCrypto.encrypt(delta, PASSWORD, BackupCrypto.generateRecoveryKey());
        File encryptedDelta = new File(backupDirectory, "delta-existing-snapshot.myobak");
        Files.write(encryptedDelta.toPath(), envelope);

        DeltaReplayCoordinator.ApplyResult result = new DeltaReplayCoordinator()
                .applyToStaging(context, staging, encryptedDelta, PASSWORD, null);
        assertEquals(0, result.appliedCount);
        assertTrue(result.alreadyApplied);

        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = stagedDb.rawQuery(
                "SELECT uploaded FROM backup_journal WHERE seq=1", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getInt(0));
        } finally {
            stagedDb.close();
        }
        // The source database is unchanged; only the restored staging copy is acknowledged.
        assertEquals(0, database.backupJournalDao().findBySequence(1L).uploaded);
        Arrays.fill(delta, (byte) 0);
        Arrays.fill(envelope, (byte) 0);
    }

    @Test public void deltaReplayRejectsSequenceGapWithoutApplyingRows() throws Exception {
        RoomDatabaseSnapshotter.Snapshot baseline = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-gap-staging.sqlite");
        Files.copy(baseline.file.toPath(), staging.toPath());

        BackupJournalEntity gap = createProductRow(2L, "GAP-SKU", "2000001",
                "2026-11-19T00:20:00Z");
        byte[] delta = DeltaBackupCoordinator.buildPayload(
                java.util.Collections.singletonList(gap), 4);
        byte[] envelope = BackupCrypto.encrypt(delta, PASSWORD, BackupCrypto.generateRecoveryKey());
        File encryptedDelta = new File(backupDirectory, "delta-gap.myobak");
        Files.write(encryptedDelta.toPath(), envelope);

        try {
            new DeltaReplayCoordinator().applyToStaging(context, staging, encryptedDelta,
                    PASSWORD, null);
            fail("A delta starting at sequence 2 must be rejected when the staging journal is empty");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("sequence gap"));
        }
        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = stagedDb.rawQuery(
                "SELECT COUNT(*) FROM product WHERE sku='GAP-SKU'", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
        } finally {
            stagedDb.close();
        }
        Arrays.fill(delta, (byte) 0);
        Arrays.fill(envelope, (byte) 0);
    }


    @Test public void deltaReplayRejectsUnsupportedOperationBeforeChangingStaging() throws Exception {
        RoomDatabaseSnapshotter.Snapshot baseline = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-unsupported-staging.sqlite");
        Files.copy(baseline.file.toPath(), staging.toPath());

        String createdAt = "2026-11-19T00:50:00Z";
        String payload = "{\"v\":1,\"op\":\"PRODUCT_DELETE\",\"created_at_utc\":\"" + createdAt + "\"}";
        BackupJournalEntity unsupported = new BackupJournalEntity(1L, UUID.randomUUID().toString(),
                "PRODUCT_DELETE", payload, hexSha256(payload), 4, createdAt, 0);
        byte[] delta = DeltaBackupCoordinator.buildPayload(
                java.util.Collections.singletonList(unsupported), 4);
        byte[] envelope = BackupCrypto.encrypt(delta, PASSWORD, BackupCrypto.generateRecoveryKey());
        File encrypted = new File(backupDirectory, "delta-unsupported.myobak");
        Files.write(encrypted.toPath(), envelope);

        try {
            new DeltaReplayCoordinator().applyToStaging(context, staging, encrypted, PASSWORD, null);
            fail("Unsupported operation must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("unsupported operation"));
        }
        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor journal = stagedDb.rawQuery("SELECT COUNT(*) FROM backup_journal", null)) {
            assertTrue(journal.moveToFirst());
            assertEquals(0, journal.getInt(0));
        } finally {
            stagedDb.close();
        }
        Arrays.fill(delta, (byte) 0);
        Arrays.fill(envelope, (byte) 0);
    }

    @Test public void deltaReplayRejectsPayloadHashMismatchBeforeOpeningStagingForWrite()
            throws Exception {
        RoomDatabaseSnapshotter.Snapshot baseline = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-hash-staging.sqlite");
        Files.copy(baseline.file.toPath(), staging.toPath());
        BackupJournalEntity row = createProductRow(1L, "HASH-SKU", "2000001",
                "2026-11-19T00:51:00Z");
        byte[] valid = DeltaBackupCoordinator.buildPayload(
                java.util.Collections.singletonList(row), 4);
        String json = new String(valid, java.nio.charset.StandardCharsets.UTF_8);
        json = json.replace(row.payload_sha256, "0000000000000000000000000000000000000000000000000000000000000000");
        byte[] tampered = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] envelope = BackupCrypto.encrypt(tampered, PASSWORD, BackupCrypto.generateRecoveryKey());
        File encrypted = new File(backupDirectory, "delta-hash-mismatch.myobak");
        Files.write(encrypted.toPath(), envelope);

        try {
            new DeltaReplayCoordinator().applyToStaging(context, staging, encrypted, PASSWORD, null);
            fail("A payload hash mismatch must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("integrity validation"));
        }
        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor journal = stagedDb.rawQuery("SELECT COUNT(*) FROM backup_journal", null)) {
            assertTrue(journal.moveToFirst());
            assertEquals(0, journal.getInt(0));
        } finally {
            stagedDb.close();
        }
        Arrays.fill(valid, (byte) 0);
        Arrays.fill(tampered, (byte) 0);
        Arrays.fill(envelope, (byte) 0);
    }

    @Test public void deltaReplayRejectsSchemaMismatchWithoutChangingStaging() throws Exception {
        RoomDatabaseSnapshotter.Snapshot baseline = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-schema-staging.sqlite");
        Files.copy(baseline.file.toPath(), staging.toPath());
        BackupJournalEntity row = createProductRow(1L, "SCHEMA-SKU", "2000001",
                "2026-11-19T00:52:00Z");
        byte[] valid = DeltaBackupCoordinator.buildPayload(
                java.util.Collections.singletonList(row), 4);
        String json = new String(valid, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"schema_version\":4", "\"schema_version\":99");
        byte[] tampered = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] envelope = BackupCrypto.encrypt(tampered, PASSWORD, BackupCrypto.generateRecoveryKey());
        File encrypted = new File(backupDirectory, "delta-schema-mismatch.myobak");
        Files.write(encrypted.toPath(), envelope);

        try {
            new DeltaReplayCoordinator().applyToStaging(context, staging, encrypted, PASSWORD, null);
            fail("An unsupported delta schema must be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("schema version"));
        }
        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor journal = stagedDb.rawQuery("SELECT COUNT(*) FROM backup_journal", null)) {
            assertTrue(journal.moveToFirst());
            assertEquals(0, journal.getInt(0));
        } finally {
            stagedDb.close();
        }
        Arrays.fill(valid, (byte) 0);
        Arrays.fill(tampered, (byte) 0);
        Arrays.fill(envelope, (byte) 0);
    }

    private static String hexSha256(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64);
        for (byte item : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return out.toString();
    }


    @Test public void deltaReplayRollsBackWholeBatchWhenLaterOperationFails() throws Exception {
        RoomDatabaseSnapshotter.Snapshot baseline = new RoomDatabaseSnapshotter()
                .create(context, database, backupDirectory);
        File staging = new File(backupDirectory, "delta-rollback-staging.sqlite");
        Files.copy(baseline.file.toPath(), staging.toPath());

        java.util.List<BackupJournalEntity> rows = Arrays.asList(
                createProductRow(1L, "ROLLBACK-SKU", "2000001", "2026-11-19T00:30:00Z"),
                createProductRow(2L, "ROLLBACK-SKU", "2000002", "2026-11-19T00:31:00Z"));
        byte[] delta = DeltaBackupCoordinator.buildPayload(rows, 4);
        byte[] envelope = BackupCrypto.encrypt(delta, PASSWORD, BackupCrypto.generateRecoveryKey());
        File encryptedDelta = new File(backupDirectory, "delta-rollback.myobak");
        Files.write(encryptedDelta.toPath(), envelope);

        try {
            new DeltaReplayCoordinator().applyToStaging(context, staging, encryptedDelta,
                    PASSWORD, null);
            fail("A conflicting second operation must abort the complete delta batch");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("already exists"));
        }

        SQLiteDatabase stagedDb = SQLiteDatabase.openDatabase(
                staging.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor products = stagedDb.rawQuery(
                    "SELECT COUNT(*) FROM product WHERE sku='ROLLBACK-SKU'", null);
             Cursor journal = stagedDb.rawQuery(
                    "SELECT COUNT(*) FROM backup_journal", null);
             Cursor counter = stagedDb.rawQuery(
                    "SELECT next_value FROM barcode_counter WHERE id=1", null)) {
            assertTrue(products.moveToFirst());
            assertEquals(0, products.getInt(0));
            assertTrue(journal.moveToFirst());
            assertEquals(0, journal.getInt(0));
            assertTrue(counter.moveToFirst());
            assertEquals(2000001L, counter.getLong(0));
        } finally {
            stagedDb.close();
        }
        Arrays.fill(delta, (byte) 0);
        Arrays.fill(envelope, (byte) 0);
    }

    private static BackupJournalEntity createProductRow(long sequence, String sku,
            String barcode, String createdAt) throws Exception {
        String payload = "{\"v\":1,\"op\":\"PRODUCT_CREATE_SYSTEM\","
                + "\"sku\":\"" + sku + "\",\"barcode\":\"" + barcode + "\","
                + "\"name\":\"Replay product\",\"price_satang\":100,"
                + "\"cost_satang\":50,\"unit\":\"ชิ้น\","
                + "\"stock_limit_qty_milli\":null,"
                + "\"created_at_utc\":\"" + createdAt + "\","
                + "\"updated_at_utc\":\"" + createdAt + "\"}";
        return new BackupJournalEntity(sequence, UUID.randomUUID().toString(),
                "PRODUCT_CREATE_SYSTEM", payload, hexSha256(payload), 4, createdAt, 0);
    }

    @Test public void snapshotWaitsUntilTransactionRunnerReleasesWriterGate() throws Exception {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> writer = null;
        Future<RoomDatabaseSnapshotter.Snapshot> snapshot = null;
        try {
            writer = executor.submit(() -> transactions.runInTransaction(() -> {
                database.appMetaDao().put(new AppMetaEntity(
                        "serialized_probe", "committed", "2026-11-19T00:00:00Z"));
                writerEntered.countDown();
                if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release writer");
                }
                return null;
            }));
            assertTrue("Writer did not enter transaction", writerEntered.await(5, TimeUnit.SECONDS));

            snapshot = executor.submit(() -> new RoomDatabaseSnapshotter()
                    .create(context, database, backupDirectory));
            Thread.sleep(150);
            assertTrue("Snapshot must wait while a writer owns the maintenance gate",
                    !snapshot.isDone());

            releaseWriter.countDown();
            writer.get(10, TimeUnit.SECONDS);
            RoomDatabaseSnapshotter.Snapshot completed = snapshot.get(10, TimeUnit.SECONDS);
            assertTrue(completed.file.isFile());
            assertEquals(4, completed.schemaVersion);
            assertTrue(completed.sizeBytes > 0);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
