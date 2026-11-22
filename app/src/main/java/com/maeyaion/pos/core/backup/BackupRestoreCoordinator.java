package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.tx.DatabaseMaintenanceGate;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Coordinates portable backup restore: verify private input, take a safety
 * snapshot, decrypt into staging, validate SQLite/Room invariants, then activate
 * with rollback. All application writes remain excluded for the whole operation.
 */
public final class BackupRestoreCoordinator {
    private final EncryptedBackupFileStore fileStore;
    private final RoomDatabaseSnapshotter snapshotter;
    private final RoomDatabaseRestorer restorer;

    public BackupRestoreCoordinator() {
        this(new EncryptedBackupFileStore(), new RoomDatabaseSnapshotter(),
                new RoomDatabaseRestorer());
    }

    BackupRestoreCoordinator(EncryptedBackupFileStore fileStore,
            RoomDatabaseSnapshotter snapshotter, RoomDatabaseRestorer restorer) {
        if (fileStore == null || snapshotter == null || restorer == null) {
            throw new IllegalArgumentException("Restore dependencies are required");
        }
        this.fileStore = fileStore;
        this.snapshotter = snapshotter;
        this.restorer = restorer;
    }

    /**
     * Exactly one portable recovery credential must be supplied. The password
     * array is copied and wiped here; the caller retains responsibility for its
     * own input buffer. The downloaded encrypted file is retained after restore.
     */
    public void restoreDownloadedBackup(@NonNull Context context,
            @NonNull RoomDatabase database, @NonNull File encryptedBackup,
            char[] recoveryPassword, String recoveryKey)
            throws IOException, GeneralSecurityException {
        restoreDownloadedBackupInternal(context, database, encryptedBackup,
                recoveryPassword, recoveryKey, null);
    }

    /** Seeds the authoritative event cursor inside the staged SQLite file before atomic activation. */
    public void restoreDownloadedSyncBaseline(@NonNull Context context,
            @NonNull RoomDatabase database, @NonNull File encryptedBackup,
            char[] recoveryPassword, String recoveryKey, @NonNull String storeId,
            long throughSequence, @NonNull String updatedAtUtc)
            throws IOException, GeneralSecurityException {
        if (storeId == null || !storeId.matches("[A-Za-z0-9_-]{1,128}")
                || throughSequence < 0 || updatedAtUtc == null || updatedAtUtc.trim().isEmpty()) {
            throw new IllegalArgumentException("Sync baseline cursor metadata is invalid");
        }
        restoreDownloadedBackupInternal(context, database, encryptedBackup,
                recoveryPassword, recoveryKey,
                new SyncCursorSeed(storeId, throughSequence, updatedAtUtc));
    }

    private void restoreDownloadedBackupInternal(Context context, RoomDatabase database,
            File encryptedBackup, char[] recoveryPassword, String recoveryKey,
            SyncCursorSeed cursorSeed) throws IOException, GeneralSecurityException {
        boolean hasPassword = recoveryPassword != null && recoveryPassword.length > 0;
        boolean hasRecoveryKey = recoveryKey != null && !recoveryKey.trim().isEmpty();
        if (hasPassword == hasRecoveryKey) {
            throw new GeneralSecurityException(
                    "Supply exactly one Recovery Password or Recovery Key");
        }

        Context app = context.getApplicationContext();
        File root = app.getFilesDir().getCanonicalFile();
        File backup = encryptedBackup.getCanonicalFile();
        if (!backup.getPath().startsWith(root.getPath() + File.separator)
                || !backup.isFile() || !backup.getName().endsWith(".myobak")) {
            throw new IOException("Restore input must be an app-private encrypted .myobak file");
        }
        BackupCrypto.validateEnvelopeFile(backup);

        char[] passwordCopy = hasPassword ? recoveryPassword.clone() : null;
        File snapshotDirectory = new File(root, "backups/snapshots");
        File stagingDirectory = new File(root, "backups/restore-staging");
        ensurePrivateDirectory(root, snapshotDirectory);
        ensurePrivateDirectory(root, stagingDirectory);

        try {
            DatabaseMaintenanceGate.withExclusiveAccess(() -> {
                RoomDatabaseSnapshotter.Snapshot safety = null;
                File staged = null;
                Throwable primaryFailure = null;
                boolean activationAttempted = false;
                boolean activationSucceeded = false;
                try {
                    int expectedVersion = database.getOpenHelper()
                            .getWritableDatabase().getVersion();
                    safety = snapshotter.create(app, database, snapshotDirectory);
                    if (safety.schemaVersion != expectedVersion) {
                        throw new IOException("Safety snapshot schema version changed unexpectedly");
                    }
                    staged = fileStore.restoreToStaging(stagingDirectory, backup,
                            passwordCopy, hasPassword ? null : recoveryKey,
                            new SQLiteStagedRestoreValidator(expectedVersion));
                    if (cursorSeed != null) seedCursor(staged, cursorSeed, expectedVersion);
                    activationAttempted = true;
                    restorer.activate(app, staged, safety.file, expectedVersion);
                    activationSucceeded = true;
                    return null;
                } catch (IOException | GeneralSecurityException | RuntimeException | Error failure) {
                    primaryFailure = failure;
                    throw failure;
                } finally {
                    cleanupStagedFile(staged, primaryFailure);
                    // Keep the plaintext safety snapshot if activation/rollback failed.
                    // It is the last recovery path if the live DB and rollback copy are both damaged.
                    if (safety != null && (!activationAttempted || activationSucceeded)) {
                        cleanupStagedFile(safety.file, primaryFailure);
                    }
                }
            });
        } catch (IOException | GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Encrypted backup restore failed", e);
        } finally {
            if (passwordCopy != null) Arrays.fill(passwordCopy, '\0');
        }
    }

    private static void seedCursor(File staged, SyncCursorSeed seed, int expectedVersion)
            throws IOException {
        android.database.sqlite.SQLiteDatabase sqlite = null;
        try {
            sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
                    staged.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            if (sqlite.getVersion() != expectedVersion) {
                throw new IOException("Staged sync baseline schema changed before cursor seeding");
            }
            boolean tableExists;
            try (android.database.Cursor cursor = sqlite.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='sync_cursor'", null)) {
                tableExists = cursor.moveToFirst();
            }
            if (!tableExists) throw new IOException("Staged database has no sync cursor table");
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("store_id", seed.storeId);
            values.put("last_applied_sequence", seed.sequence);
            values.put("updated_at_utc", seed.updatedAtUtc);
            long row = sqlite.insertWithOnConflict("sync_cursor", null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
            if (row == -1L) throw new IOException("Unable to seed sync cursor in staged baseline");
            try (android.database.Cursor integrity = sqlite.rawQuery("PRAGMA integrity_check", null)) {
                if (!integrity.moveToFirst() || !"ok".equalsIgnoreCase(integrity.getString(0))) {
                    throw new IOException("Staged baseline failed integrity check after cursor seeding");
                }
            }
        } catch (android.database.sqlite.SQLiteException error) {
            throw new IOException("Unable to seed sync cursor in staged baseline", error);
        } finally {
            if (sqlite != null && sqlite.isOpen()) sqlite.close();
        }
    }

    private static final class SyncCursorSeed {
        final String storeId;
        final long sequence;
        final String updatedAtUtc;
        SyncCursorSeed(String storeId, long sequence, String updatedAtUtc) {
            this.storeId = storeId;
            this.sequence = sequence;
            this.updatedAtUtc = updatedAtUtc;
        }
    }

    private static void ensurePrivateDirectory(File root, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private restore directory");
        }
        String rootPath = root.getPath() + File.separator;
        if (!directory.getCanonicalPath().startsWith(rootPath) || !directory.canWrite()) {
            throw new IOException("Restore directory must remain inside private app storage");
        }
    }

    private static void cleanupStagedFile(File file, Throwable primaryFailure) throws IOException {
        if (file != null && file.exists() && !file.delete()) {
            IOException cleanupFailure = new IOException(
                    "Unable to remove temporary plaintext restore file: " + file.getName());
            if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
            else throw cleanupFailure;
        }
    }
}
