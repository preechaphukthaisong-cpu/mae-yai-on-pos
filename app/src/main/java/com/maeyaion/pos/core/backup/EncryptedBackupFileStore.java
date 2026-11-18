package com.maeyaion.pos.core.backup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Writes portable encrypted backup envelopes atomically and restores only into
 * a separate staging directory. This class never replaces the live Room database.
 */
public final class EncryptedBackupFileStore {
    private static final String BACKUP_SUFFIX = ".myobak";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final long MAX_ENVELOPE_BYTES = 512L * 1024L * 1024L + 256L;

    public File writeBackup(File privateBackupDirectory, byte[] databaseSnapshot,
                            char[] recoveryPassword, String recoveryKey)
            throws IOException, GeneralSecurityException {
        requireDirectory(privateBackupDirectory);
        String id = UUID.randomUUID().toString();
        File temporary = new File(privateBackupDirectory, "." + id + TEMP_SUFFIX);
        File destination = new File(privateBackupDirectory, id + BACKUP_SUFFIX);
        byte[] encrypted = BackupCrypto.encrypt(databaseSnapshot, recoveryPassword, recoveryKey);
        try {
            writeAndSync(temporary, encrypted);
            if (!temporary.renameTo(destination)) {
                throw new IOException("Atomic backup publication failed");
            }
            return destination;
        } finally {
            wipe(encrypted);
            if (temporary.exists() && !temporary.delete()) {
                // Best effort cleanup; the incomplete temp file is never treated as a backup.
            }
        }
    }

    public File restoreToStaging(File privateStagingDirectory, File encryptedBackup,
                                 char[] recoveryPassword, String recoveryKey,
                                 StagedRestoreValidator validator)
            throws IOException, GeneralSecurityException {
        requireDirectory(privateStagingDirectory);
        if (encryptedBackup == null || !encryptedBackup.isFile()
                || !encryptedBackup.getName().endsWith(BACKUP_SUFFIX)) {
            throw new IOException("Backup file is missing or has an unsupported extension");
        }
        File temporary = new File(privateStagingDirectory,
                "." + UUID.randomUUID() + TEMP_SUFFIX);
        File staged = new File(privateStagingDirectory,
                UUID.randomUUID() + ".restore.db");
        if (encryptedBackup.length() <= 0 || encryptedBackup.length() > MAX_ENVELOPE_BYTES) {
            throw new IOException("Backup file size is outside the supported range");
        }
        byte[] envelope = java.nio.file.Files.readAllBytes(encryptedBackup.toPath());
        byte[] plaintext = null;
        try {
            plaintext = recoveryPassword != null
                    ? BackupCrypto.decryptWithPassword(envelope, recoveryPassword)
                    : BackupCrypto.decryptWithRecoveryKey(envelope, recoveryKey);
            writeAndSync(temporary, plaintext);
            if (validator == null || !validator.isValid(temporary)) {
                throw new IOException("Staged database failed integrity/schema validation");
            }
            if (!temporary.renameTo(staged)) {
                throw new IOException("Unable to publish staged restore file");
            }
            return staged;
        } finally {
            wipe(envelope);
            wipe(plaintext);
            if (temporary.exists() && !temporary.delete()) {
                // Best effort cleanup; no live database path is touched here.
            }
        }
    }

    private static void requireDirectory(File directory) throws IOException {
        if (directory == null || !directory.isDirectory() || !directory.canWrite()) {
            throw new IOException("A writable app-private directory is required");
        }
    }

    private static void writeAndSync(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) java.util.Arrays.fill(value, (byte) 0);
    }

    public interface StagedRestoreValidator {
        /** Must check SQLite integrity, expected schema version, and application invariants. */
        boolean isValid(File stagedDatabase) throws IOException;
    }
}
