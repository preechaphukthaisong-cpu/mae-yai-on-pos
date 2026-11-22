package com.maeyaion.pos.core.sync;

import android.content.Context;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.backup.AndroidKeystoreKeyProtector;
import com.maeyaion.pos.core.backup.BackupCrypto;
import com.maeyaion.pos.core.backup.BackupRestoreCoordinator;
import com.maeyaion.pos.core.backup.RoomDatabaseSnapshotter;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncCursorEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;

/** Full-state bootstrap over encrypted snapshots; never uploads an unencrypted SQLite file. */
public final class SyncBaselineCoordinator {
    private static final long MAX_UPLOAD_BYTES = 128L * 1024 * 1024;
    private final RoomDatabaseSnapshotter snapshotter;
    private final BackupRestoreCoordinator restorer;

    public SyncBaselineCoordinator() {
        this(new RoomDatabaseSnapshotter(), new BackupRestoreCoordinator());
    }

    SyncBaselineCoordinator(RoomDatabaseSnapshotter snapshotter, BackupRestoreCoordinator restorer) {
        if (snapshotter == null || restorer == null) throw new IllegalArgumentException("Baseline dependencies are required");
        this.snapshotter = snapshotter;
        this.restorer = restorer;
    }

    /** Creates a consistent encrypted baseline and atomically advances the local pull cursor. */
    @NonNull
    public BaselineResult exportAndUpload(@NonNull Context context, @NonNull AppDatabase database,
            @NonNull SyncApiClient api, @NonNull String storeId, char[] recoveryPassword,
            @NonNull String recoveryKey) throws SyncException {
        if (context == null || database == null || api == null || storeId == null
                || recoveryPassword == null || recoveryPassword.length == 0
                || recoveryKey == null || recoveryKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Baseline context, credentials and dependencies are required");
        }
        File snapshot = null;
        File encrypted = null;
        try {
            SyncQueueGuard.requireQuiescent(database, storeId);
            JSONObject remoteStore = api.getStore();
            if (!storeId.equals(remoteStore.getString("storeId"))) {
                throw new SyncException("Baseline store identity does not match the authenticated account");
            }
            long throughSequence = exactSequence(remoteStore.get("lastSequence"));
            File directory = new File(context.getApplicationContext().getFilesDir(), "sync-baselines");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Unable to create private baseline directory");
            }
            RoomDatabaseSnapshotter.Snapshot created = snapshotter.create(
                    context.getApplicationContext(), database, directory);
            snapshot = created.file;
            encrypted = new File(directory, created.file.getName().replace(".sqlite", ".myobak"));
            BackupCrypto.encryptFile(snapshot, encrypted, recoveryPassword.clone(),
                    recoveryKey, new AndroidKeystoreKeyProtector());
            BackupCrypto.validateEnvelopeFile(encrypted);
            if (encrypted.length() <= 0 || encrypted.length() > MAX_UPLOAD_BYTES) {
                throw new IOException("Encrypted baseline exceeds the 128 MiB sync upload limit");
            }
            String encryptedSha = sha256(encrypted);
            SyncBaselineManifest manifest = SyncBaselineManifest.create(storeId,
                    throughSequence, created.schemaVersion, created.sha256, encryptedSha,
                    Instant.now().toString());
            byte[] bytes = readBounded(encrypted, MAX_UPLOAD_BYTES);
            JSONObject uploaded = api.uploadEncryptedBaseline(bytes, throughSequence,
                    encryptedSha, manifest.toJson());
            database.runInTransaction(() -> database.syncQueueDao().saveCursor(
                    new SyncCursorEntity(storeId, throughSequence, Instant.now().toString())));
            return new BaselineResult(uploaded.optString("snapshotId", ""),
                    throughSequence, manifest);
        } catch (SyncException error) {
            throw error;
        } catch (JSONException | IOException | GeneralSecurityException error) {
            throw new SyncException("Unable to export encrypted sync baseline", error);
        } finally {
            deleteQuietly(snapshot);
            deleteQuietly(encrypted);
        }
    }

    /** Restores only a manifest-matched snapshot, then seeds its authoritative event cursor. */
    public void restoreDownloaded(@NonNull Context context, @NonNull AppDatabase database,
            @NonNull SyncApiClient api, @NonNull String storeId, @NonNull String snapshotId,
            @NonNull JSONObject manifestJson, char[] recoveryPassword, String recoveryKey)
            throws SyncException {
        File downloaded = null;
        try {
            SyncBaselineManifest manifest = SyncBaselineManifest.fromJson(manifestJson);
            if (!storeId.equals(manifest.storeId)) {
                throw new SyncException("Baseline belongs to a different store; restore refused");
            }
            JSONArray backups = api.listBackups().getJSONArray("backups");
            boolean listed = false;
            for (int i = 0; i < backups.length(); i++) {
                JSONObject item = backups.getJSONObject(i);
                if (snapshotId.equals(item.optString("id"))
                        && manifest.encryptedFileSha256.equalsIgnoreCase(item.optString("sha256"))
                        && manifest.throughSequence == exactSequence(item.get("through_seq"))) {
                    SyncBaselineManifest listedManifest = SyncBaselineManifest.fromJson(
                            item.getJSONObject("manifest"));
                    if (!listedManifest.encryptedFileSha256.equals(manifest.encryptedFileSha256)
                            || !listedManifest.snapshotSha256.equals(manifest.snapshotSha256)) {
                        throw new SyncException("Server baseline manifest changed; restore refused");
                    }
                    listed = true;
                    break;
                }
            }
            if (!listed) throw new SyncException("Requested baseline is not in the authenticated store backup list");
            byte[] bytes = api.downloadEncryptedBackup(snapshotId);
            if (!manifest.encryptedFileSha256.equalsIgnoreCase(sha256(bytes))) {
                throw new SyncException("Downloaded baseline checksum does not match its manifest");
            }
            File directory = new File(context.getApplicationContext().getFilesDir(), "sync-baselines");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Unable to create private baseline directory");
            downloaded = new File(directory, snapshotId + ".myobak");
            try (FileOutputStream output = new FileOutputStream(downloaded, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            BackupCrypto.validateEnvelopeFile(downloaded);
            restorer.restoreDownloadedBackup(context, database, downloaded,
                    recoveryPassword, recoveryKey);
            AppDatabase restored = AppDatabase.getInstance(context);
            SyncQueueGuard.requireQuiescent(restored, storeId);
            restored.runInTransaction(() -> restored.syncQueueDao().saveCursor(
                    new SyncCursorEntity(storeId, manifest.throughSequence, Instant.now().toString())));
        } catch (SyncException error) {
            throw error;
        } catch (JSONException | IOException | GeneralSecurityException error) {
            throw new SyncException("Unable to restore encrypted sync baseline", error);
        } finally {
            deleteQuietly(downloaded);
        }
    }

    private static long exactSequence(Object value) throws SyncException {
        try { return new java.math.BigDecimal(value.toString()).longValueExact(); }
        catch (RuntimeException error) { throw new SyncException("Server baseline sequence is invalid", error); }
    }

    private static String sha256(File file) throws IOException, GeneralSecurityException {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return hex(digest.digest());
        }
    }

    private static String sha256(byte[] bytes) throws GeneralSecurityException {
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(64);
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return value.toString();
    }

    private static byte[] readBounded(File file, long max) throws IOException {
        if (file.length() > max) throw new IOException("Encrypted baseline exceeds size limit");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IOException("Encrypted baseline file was truncated");
                offset += read;
            }
            if (input.read() != -1) throw new IOException("Encrypted baseline grew while reading");
        }
        return bytes;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) file.delete();
    }

    public static final class BaselineResult {
        public final String snapshotId;
        public final long throughSequence;
        public final SyncBaselineManifest manifest;
        BaselineResult(String id, long sequence, SyncBaselineManifest manifest) {
            this.snapshotId = id;
            this.throughSequence = sequence;
            this.manifest = manifest;
        }
    }

    private static final class SyncQueueGuard {
        static void requireQuiescent(AppDatabase db, String storeId) throws SyncException {
            if (db.syncQueueDao().pendingOutboxCount(storeId) != 0
                    || db.syncQueueDao().unappliedInboxCount(storeId) != 0) {
                throw new SyncException("Baseline requires all local outbox and inbox events to be fully synchronized");
            }
        }
    }
}
