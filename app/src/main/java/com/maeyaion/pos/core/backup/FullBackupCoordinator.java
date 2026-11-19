package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Creates a consistent SQLite snapshot, encrypts it with portable recovery
 * credentials plus a device-bound Keystore wrapper, and queues only the
 * authenticated encrypted envelope for Drive appDataFolder upload.
 *
 * The plaintext snapshot is temporary and is deleted after encryption whether
 * encryption or scheduling succeeds. The encrypted backup is retained locally
 * when upload is deferred or fails so WorkManager can retry it.
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

    /**
     * Returns the durable local encrypted backup. Drive upload is asynchronous;
     * a returned file does not claim that Google Drive has accepted it.
     */
    @NonNull
    public File createAndQueue(@NonNull Context context, @NonNull RoomDatabase database,
            @NonNull String googleAccountName, @NonNull char[] recoveryPassword,
            @NonNull String recoveryKey) throws IOException, GeneralSecurityException {
        if (googleAccountName.trim().isEmpty()) {
            throw new IllegalArgumentException("Google account is required");
        }
        if (recoveryPassword.length < 12 || recoveryKey.trim().isEmpty()) {
            throw new GeneralSecurityException("Recovery Password and Recovery Key are required");
        }

        Context app = context.getApplicationContext();
        File filesRoot = app.getFilesDir().getCanonicalFile();
        File backupDirectory = new File(filesRoot, "backups");
        File snapshotDirectory = new File(backupDirectory, "snapshots");
        ensurePrivateDirectory(filesRoot, backupDirectory);
        ensurePrivateDirectory(filesRoot, snapshotDirectory);

        char[] passwordCopy = recoveryPassword.clone();
        RoomDatabaseSnapshotter.Snapshot snapshot = null;
        byte[] snapshotBytes = null;
        Throwable primaryFailure = null;
        try {
            snapshot = snapshotter.create(app, database, snapshotDirectory);
            snapshotBytes = readSnapshot(snapshot.file);
            File encryptedBackup = fileStore.writeBackupWithLocalKey(
                    backupDirectory, snapshotBytes, passwordCopy, recoveryKey, keyProtector);

            // The worker independently checks app-private location, envelope
            // magic/version, KDF parameters, and exact envelope length.
            DriveBackupWorkScheduler.enqueueUpload(app, encryptedBackup, googleAccountName);
            return encryptedBackup;
        } catch (IOException | GeneralSecurityException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            Arrays.fill(passwordCopy, '\0');
            if (snapshotBytes != null) Arrays.fill(snapshotBytes, (byte) 0);
            cleanupSnapshot(snapshot == null ? null : snapshot.file, primaryFailure);
        }
    }

    /**
     * Deletes the temporary plaintext snapshot without masking the operation's
     * original failure. If the main operation succeeded, cleanup failure is fatal.
     */
    static void cleanupSnapshot(File snapshot, Throwable primaryFailure) throws IOException {
        if (snapshot != null && snapshot.exists() && !snapshot.delete()) {
            IOException cleanupFailure = new IOException(
                    "Encrypted backup created, but plaintext snapshot cleanup failed");
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private static byte[] readSnapshot(File snapshot) throws IOException {
        if (snapshot == null || !snapshot.isFile() || snapshot.length() <= 0
                || snapshot.length() > 512L * 1024L * 1024L) {
            throw new IOException("Snapshot is missing or exceeds the supported size");
        }
        return java.nio.file.Files.readAllBytes(snapshot.toPath());
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
