package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Creates authenticated encrypted database snapshots. Only the encrypted
 * envelope is retained; plaintext snapshots are deleted after encryption.
 */
public final class FullBackupCoordinator {
    private final RoomDatabaseSnapshotter snapshotter;
    private final EncryptedBackupFileStore fileStore;
    private final AndroidKeystoreKeyProtector keyProtector;

    public FullBackupCoordinator() {
        this(new RoomDatabaseSnapshotter(), new EncryptedBackupFileStore(),
                new AndroidKeystoreKeyProtector());
    }

    FullBackupCoordinator(RoomDatabaseSnapshotter snapshotter,
                          EncryptedBackupFileStore fileStore,
                          AndroidKeystoreKeyProtector keyProtector) {
        if (snapshotter == null || fileStore == null || keyProtector == null) {
            throw new IllegalArgumentException("Backup dependencies are required");
        }
        this.snapshotter = snapshotter;
        this.fileStore = fileStore;
        this.keyProtector = keyProtector;
    }

    /** Creates a local encrypted backup without requiring or contacting Google Drive. */
    @NonNull
    public File createLocalBackup(@NonNull Context context, @NonNull RoomDatabase database,
            @NonNull char[] recoveryPassword, @NonNull String recoveryKey)
            throws IOException, GeneralSecurityException {
        if (recoveryPassword.length < 12 || recoveryKey.trim().isEmpty()) {
            throw new GeneralSecurityException("Recovery Password and Recovery Key are required");
        }
        Context app = context.getApplicationContext();
        File root = app.getFilesDir().getCanonicalFile();
        File backupDirectory = new File(root, "backups");
        File snapshotDirectory = new File(backupDirectory, "snapshots");
        ensurePrivateDirectory(root, backupDirectory);
        ensurePrivateDirectory(root, snapshotDirectory);

        char[] passwordCopy = recoveryPassword.clone();
        RoomDatabaseSnapshotter.Snapshot snapshot = null;
        Throwable primaryFailure = null;
        try {
            snapshot = snapshotter.create(app, database, snapshotDirectory);
            return fileStore.writeBackupWithLocalKey(backupDirectory, snapshot.file,
                    passwordCopy, recoveryKey, keyProtector);
        } catch (IOException | GeneralSecurityException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Arrays.fill(passwordCopy, '\\0');
            cleanupSnapshot(snapshot == null ? null : snapshot.file, primaryFailure);
        }
    }

    /** Queues the encrypted local file for a one-time Drive upload. */
    @NonNull
    public File createAndQueue(@NonNull Context context, @NonNull RoomDatabase database,
            @NonNull String googleAccountName, @NonNull char[] recoveryPassword,
            @NonNull String recoveryKey) throws IOException, GeneralSecurityException {
        if (googleAccountName.trim().isEmpty()) {
            throw new IllegalArgumentException("Google account is required");
        }
        File backup = createLocalBackup(context, database, recoveryPassword, recoveryKey);
        DriveBackupWorkScheduler.enqueueUpload(context.getApplicationContext(),
                backup, googleAccountName);
        return backup;
    }

    static void cleanupSnapshot(File snapshot, Throwable primaryFailure) throws IOException {
        if (snapshot != null && snapshot.exists() && !snapshot.delete()) {
            IOException cleanupFailure = new IOException(
                    "Encrypted backup created, but plaintext snapshot cleanup failed");
            if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
            else throw cleanupFailure;
        }
    }

    private static void ensurePrivateDirectory(File root, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private backup directory");
        }
        File canonical = directory.getCanonicalFile();
        String rootPath = root.getPath() + File.separator;
        if (!canonical.getPath().startsWith(rootPath) || !canonical.canWrite()) {
            throw new IOException("Backup directory must remain inside app-private storage");
        }
    }
}
