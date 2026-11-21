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
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.UUID;

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
        assertEquals(6, snapshot.schemaVersion);
        assertEquals(64, snapshot.sha256.length());

        byte[] rawSnapshot = Files.readAllBytes(snapshot.file.toPath());
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        EncryptedBackupFileStore store = new EncryptedBackupFileStore();
        File encrypted = store.writeBackup(backupDirectory, rawSnapshot, PASSWORD, recoveryKey);
        assertTrue(encrypted.isFile());
        assertTrue(encrypted.length() > 0);

        File restored = store.restoreToStaging(stagingDirectory, encrypted, PASSWORD, null,
                new SQLiteStagedRestoreValidator(6));
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
                recoveryKey, new SQLiteStagedRestoreValidator(6));
        assertTrue(restoredByRecoveryKey.isFile());

        AndroidKeystoreKeyProtector localProtector = new AndroidKeystoreKeyProtector();
        File localEncrypted = store.writeBackupWithLocalKey(backupDirectory, rawSnapshot,
                PASSWORD, recoveryKey, localProtector);
        File restoredLocally = store.restoreWithLocalKeyToStaging(stagingDirectory,
                localEncrypted, localProtector, new SQLiteStagedRestoreValidator(6));
        assertTrue(restoredLocally.isFile());
        File restoredPortablePassword = store.restoreToStaging(stagingDirectory,
                localEncrypted, PASSWORD, null, new SQLiteStagedRestoreValidator(6));
        assertTrue(restoredPortablePassword.isFile());
        File restoredPortableRecovery = store.restoreToStaging(stagingDirectory,
                localEncrypted, null, recoveryKey, new SQLiteStagedRestoreValidator(6));
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
                    null, new SQLiteStagedRestoreValidator(6));
            fail("Wrong password must not restore");
        } catch (GeneralSecurityException expected) {
            // Authenticated decryption must fail before staging publication.
        }

        byte[] invalidSqlite = "not a sqlite database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        File invalidEncrypted = store.writeBackup(backupDirectory, invalidSqlite, PASSWORD, recoveryKey);
        try {
            store.restoreToStaging(stagingDirectory, invalidEncrypted, PASSWORD, null,
                    new SQLiteStagedRestoreValidator(6));
            fail("Invalid SQLite content must not be staged");
        } catch (java.io.IOException expected) {
            // The validator rejects non-database plaintext before publication.
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
