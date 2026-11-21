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

    /**
     * Stores a portable encrypted backup plus a device-only Keystore-wrapped DEK sidecar.
     * Upload only the returned .myobak file; never upload the .localkey sidecar.
     */
    public File writeBackupWithLocalKey(File privateBackupDirectory, byte[] databaseSnapshot,
            char[] recoveryPassword, String recoveryKey, AndroidKeystoreKeyProtector protector)
            throws IOException, GeneralSecurityException {
        requireDirectory(privateBackupDirectory);
        BackupCrypto.EncryptedEnvelope encrypted = BackupCrypto.encryptWithLocalKeyProtector(
                databaseSnapshot, recoveryPassword, recoveryKey, protector);
        String id = UUID.randomUUID().toString();
        File backupTemp = new File(privateBackupDirectory, "." + id + TEMP_SUFFIX);
        File keyTemp = new File(privateBackupDirectory, "." + id + ".key" + TEMP_SUFFIX);
        File backup = new File(privateBackupDirectory, id + BACKUP_SUFFIX);
        File localKey = localKeyFile(backup);
        try {
            writeAndSync(backupTemp, encrypted.envelope);
            writeAndSync(keyTemp, encrypted.locallyProtectedDataKey);
            if (!keyTemp.renameTo(localKey)) throw new IOException("Unable to publish local Keystore key sidecar");
            if (!backupTemp.renameTo(backup)) {
                // The encrypted portable backup is the publication marker.
                // If publication fails, remove its otherwise orphaned sidecar.
                //noinspection ResultOfMethodCallIgnored
                localKey.delete();
                throw new IOException("Atomic backup publication failed");
            }
            return backup;
        } finally {
            wipe(encrypted.envelope);
            wipe(encrypted.locallyProtectedDataKey);
            if (backupTemp.exists()) backupTemp.delete();
            if (keyTemp.exists()) keyTemp.delete();
        }
    }

    /**
     * Streaming full-snapshot encryption. Only fixed-size buffers are held in memory,
     * so database size does not determine peak heap use.
     */
    public File writeBackupWithLocalKey(File privateBackupDirectory, File databaseSnapshot,
            char[] recoveryPassword, String recoveryKey, AndroidKeystoreKeyProtector protector)
            throws IOException, GeneralSecurityException {
        requireDirectory(privateBackupDirectory);
        if (databaseSnapshot == null || !databaseSnapshot.isFile()) {
            throw new IOException("Database snapshot is missing");
        }
        String id = UUID.randomUUID().toString();
        File backupTemp = new File(privateBackupDirectory, "." + id + TEMP_SUFFIX);
        File keyTemp = new File(privateBackupDirectory, "." + id + ".key" + TEMP_SUFFIX);
        File backup = new File(privateBackupDirectory, id + BACKUP_SUFFIX);
        File localKey = localKeyFile(backup);
        byte[] protectedKey = null;
        try {
            protectedKey = BackupCrypto.encryptFile(databaseSnapshot, backupTemp,
                    recoveryPassword, recoveryKey, protector);
            writeAndSync(keyTemp, protectedKey);
            if (!keyTemp.renameTo(localKey)) {
                throw new IOException("Unable to publish local Keystore key sidecar");
            }
            if (!backupTemp.renameTo(backup)) {
                // The portable envelope is the publication marker.
                //noinspection ResultOfMethodCallIgnored
                localKey.delete();
                throw new IOException("Atomic backup publication failed");
            }
            return backup;
        } finally {
            wipe(protectedKey);
            if (backupTemp.exists()) backupTemp.delete();
            if (keyTemp.exists()) keyTemp.delete();
        }
    }

    /** Restores on the originating installation using its Keystore-protected DEK. */
    public File restoreWithLocalKeyToStaging(File privateStagingDirectory, File encryptedBackup,
            AndroidKeystoreKeyProtector protector, StagedRestoreValidator validator)
            throws IOException, GeneralSecurityException {
        requireDirectory(privateStagingDirectory);
        requireBackupFile(encryptedBackup);
        File localKey = localKeyFile(encryptedBackup);
        if (!localKey.isFile() || localKey.length() != 60) {
            throw new IOException("Local Keystore key sidecar is missing or invalid; use Recovery Password or Recovery Key");
        }
        byte[] protectedKey = readBounded(localKey);
        File decrypted = new File(privateStagingDirectory, "." + UUID.randomUUID() + ".payload" + TEMP_SUFFIX);
        File temporary = new File(privateStagingDirectory, "." + UUID.randomUUID() + TEMP_SUFFIX);
        File staged = new File(privateStagingDirectory, UUID.randomUUID() + ".restore.db");
        try {
            BackupCrypto.decryptFileToLocalKey(encryptedBackup, decrypted, protectedKey, protector);
            BackupCompression.expandIfCompressed(decrypted, temporary);
            if (validator == null || !validator.isValid(temporary)) {
                throw new IOException("Staged database failed integrity/schema validation");
            }
            if (!temporary.renameTo(staged)) {
                throw new IOException("Unable to publish staged restore file");
            }
            return staged;
        } finally {
            wipe(protectedKey);
            if (temporary.exists()) temporary.delete();
            if (decrypted.exists()) decrypted.delete();
        }
    }

    public File restoreToStaging(File privateStagingDirectory, File encryptedBackup,
                                 char[] recoveryPassword, String recoveryKey,
                                 StagedRestoreValidator validator)
            throws IOException, GeneralSecurityException {
        requireDirectory(privateStagingDirectory);
        requireBackupFile(encryptedBackup);
        File decrypted = new File(privateStagingDirectory, "." + UUID.randomUUID() + ".payload" + TEMP_SUFFIX);
        File temporary = new File(privateStagingDirectory, "." + UUID.randomUUID() + TEMP_SUFFIX);
        File staged = new File(privateStagingDirectory, UUID.randomUUID() + ".restore.db");
        try {
            BackupCrypto.decryptFileTo(encryptedBackup, decrypted, recoveryPassword, recoveryKey);
            BackupCompression.expandIfCompressed(decrypted, temporary);
            if (validator == null || !validator.isValid(temporary)) {
                throw new IOException("Staged database failed integrity/schema validation");
            }
            if (!temporary.renameTo(staged)) {
                throw new IOException("Unable to publish staged restore file");
            }
            return staged;
        } finally {
            if (temporary.exists()) temporary.delete();
            if (decrypted.exists()) decrypted.delete();
        }
    }

    private static File publishStagedPlaintext(File directory, byte[] plaintext,
            StagedRestoreValidator validator) throws IOException {
        File temporary = new File(directory, "." + UUID.randomUUID() + TEMP_SUFFIX);
        File staged = new File(directory, UUID.randomUUID() + ".restore.db");
        try {
            writeAndSync(temporary, plaintext);
            if (validator == null || !validator.isValid(temporary)) {
                throw new IOException("Staged database failed integrity/schema validation");
            }
            if (!temporary.renameTo(staged)) throw new IOException("Unable to publish staged restore file");
            return staged;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static void requireBackupFile(File encryptedBackup) throws IOException {
        if (encryptedBackup == null || !encryptedBackup.isFile()
                || !encryptedBackup.getName().endsWith(BACKUP_SUFFIX)) {
            throw new IOException("Backup file is missing or has an unsupported extension");
        }
    }

    private static File localKeyFile(File backup) {
        return new File(backup.getParentFile(), backup.getName() + ".localkey");
    }

    private static byte[] readBounded(File file) throws IOException {
        if (file.length() <= 0 || file.length() > MAX_ENVELOPE_BYTES) {
            throw new IOException("Backup file size is outside the supported range");
        }
        return java.nio.file.Files.readAllBytes(file.toPath());
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
