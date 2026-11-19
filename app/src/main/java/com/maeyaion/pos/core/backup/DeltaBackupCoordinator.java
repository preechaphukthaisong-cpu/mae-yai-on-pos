package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;

import com.maeyaion.pos.core.db.BackupJournalEntity;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

/**
 * Packages an ordered, bounded journal batch into a portable encrypted envelope.
 * Journal rows are acknowledged only by the upload worker after Drive confirms
 * or reconciles the remote file ID.
 */
public final class DeltaBackupCoordinator {
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_ENTRY_PAYLOAD_CHARS = 64 * 1024;
    private static final int MAX_DELTA_BYTES = 4 * 1024 * 1024;
    private final EncryptedBackupFileStore fileStore;
    private final AndroidKeystoreKeyProtector keyProtector;

    public DeltaBackupCoordinator() {
        this(new EncryptedBackupFileStore(), new AndroidKeystoreKeyProtector());
    }

    DeltaBackupCoordinator(EncryptedBackupFileStore fileStore,
            AndroidKeystoreKeyProtector keyProtector) {
        if (fileStore == null || keyProtector == null) {
            throw new IllegalArgumentException("Delta backup dependencies are required");
        }
        this.fileStore = fileStore;
        this.keyProtector = keyProtector;
    }

    /**
     * Creates one encrypted delta from pending journal rows and queues its upload.
     * Recovery credentials are transient inputs; they are never put in WorkManager.
     */
    public File createAndQueue(@NonNull Context context, @NonNull RoomDatabase database,
            @NonNull String googleAccountName, @NonNull char[] recoveryPassword,
            @NonNull String recoveryKey) throws IOException, GeneralSecurityException {
        if (googleAccountName.trim().isEmpty()) {
            throw new IllegalArgumentException("Google account is required");
        }
        if (recoveryPassword.length < 12 || recoveryKey.trim().isEmpty()) {
            throw new GeneralSecurityException("Recovery Password and Recovery Key are required");
        }

        List<BackupJournalEntity> batch = database instanceof com.maeyaion.pos.core.db.AppDatabase
                ? ((com.maeyaion.pos.core.db.AppDatabase) database).backupJournalDao()
                    .pendingBatch(MAX_BATCH_SIZE)
                : null;
        if (batch == null) throw new IOException("A Maeyai-On POS database is required");
        if (batch.isEmpty()) return null;

        int schemaVersion = database.getOpenHelper().getWritableDatabase().getVersion();
        byte[] plaintext = buildPayload(batch, schemaVersion);
        char[] passwordCopy = recoveryPassword.clone();
        File encrypted = null;
        try {
            Context app = context.getApplicationContext();
            File root = app.getFilesDir().getCanonicalFile();
            File directory = new File(root, "backups/deltas");
            ensurePrivateDirectory(root, directory);
            encrypted = fileStore.writeBackupWithLocalKey(directory, plaintext,
                    passwordCopy, recoveryKey, keyProtector);
            long[] sequences = new long[batch.size()];
            for (int i = 0; i < batch.size(); i++) sequences[i] = batch.get(i).seq;
            DriveBackupWorkScheduler.enqueueUpload(app, encrypted, googleAccountName, sequences);
            return encrypted;
        } finally {
            Arrays.fill(passwordCopy, '\0');
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    static byte[] buildPayload(List<BackupJournalEntity> entries, int expectedSchemaVersion)
            throws GeneralSecurityException, IOException {
        if (entries == null || entries.isEmpty() || entries.size() > MAX_BATCH_SIZE
                || expectedSchemaVersion <= 0) {
            throw new IOException("Delta journal batch is empty or outside supported bounds");
        }
        if (entries.get(0) == null || entries.get(entries.size() - 1) == null) {
            throw new IOException("Backup journal batch contains a null row");
        }
        StringBuilder json = new StringBuilder(256 + entries.size() * 256);
        json.append("{\"format\":\"MAEYAI_DELTA_V1\",\"schema_version\":")
                .append(expectedSchemaVersion).append(",\"first_seq\":");
        long first = entries.get(0).seq;
        long previous = 0;
        json.append(first).append(",\"last_seq\":");
        long last = entries.get(entries.size() - 1).seq;
        json.append(last).append(",\"operations\":[");
        for (int i = 0; i < entries.size(); i++) {
            BackupJournalEntity entry = entries.get(i);
            if (entry == null || entry.seq <= previous || entry.seq <= 0
                    || entry.uploaded != 0 || entry.schema_version != expectedSchemaVersion
                    || isBlank(entry.operation_id) || isBlank(entry.op)
                    || entry.payload == null || entry.payload.length() > MAX_ENTRY_PAYLOAD_CHARS
                    || isBlank(entry.created_at_utc)
                    || !isValidSha256(entry.payload_sha256)
                    || !MessageDigest.isEqual(hexToBytes(entry.payload_sha256),
                            sha256(entry.payload.getBytes(StandardCharsets.UTF_8)))) {
                throw new IOException("Backup journal row failed sequence, schema, or payload integrity validation");
            }
            if (i > 0) json.append(',');
            json.append("{\"seq\":").append(entry.seq)
                    .append(",\"operation_id\":").append(quote(entry.operation_id))
                    .append(",\"op\":").append(quote(entry.op))
                    .append(",\"payload\":").append(quote(entry.payload))
                    .append(",\"payload_sha256\":").append(quote(entry.payload_sha256))
                    .append(",\"created_at_utc\":").append(quote(entry.created_at_utc))
                    .append('}');
            previous = entry.seq;
        }
        json.append("]}");
        byte[] encoded = json.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_DELTA_BYTES) {
            throw new IOException("Encrypted delta batch exceeds the 4 MiB payload limit");
        }
        return encoded;
    }

    private static String quote(String value) throws IOException {
        if (value == null) throw new IOException("Null journal field");
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isValidSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static byte[] sha256(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static void ensurePrivateDirectory(File root, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private delta backup directory");
        }
        if (!directory.getCanonicalPath().startsWith(root.getPath() + File.separator)
                || !directory.canWrite()) {
            throw new IOException("Delta backup directory must remain app-private");
        }
    }
}
