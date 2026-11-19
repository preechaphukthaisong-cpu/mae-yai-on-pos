package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maeyaion.pos.core.db.AppDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Creates a daily encrypted full snapshot locally. Upload is a separate network-constrained
 * one-time request; no password, recovery key, DEK or OAuth token enters WorkManager Data.
 */
public final class UnattendedFullBackupWorker extends Worker {
    public UnattendedFullBackupWorker(@NonNull Context context,
            @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (!UnattendedBackupScheduler.isEnabled(context)) return Result.success();
        String account = context.getSharedPreferences(UnattendedBackupScheduler.PREFS,
                Context.MODE_PRIVATE).getString(UnattendedBackupScheduler.KEY_ACCOUNT, null);
        if (account == null || account.trim().isEmpty()) {
            BackupNotifications.showFailure(context, "ยังไม่ได้เลือกบัญชี Google Drive");
            return Result.failure();
        }

        File snapshot = null;
        File encrypted = null;
        byte[] localWrappedKey = null;
        boolean pendingPublished = false;
        try {
            AndroidKeystoreCredentialVault vault = new AndroidKeystoreCredentialVault(context);
            if (!vault.exists()) {
                BackupNotifications.showFailure(context, "ไม่พบข้อมูลกู้คืน กรุณาเปิดแอปและตั้งค่าการสำรองใหม่");
                return Result.failure();
            }
            File backupRoot = new File(context.getFilesDir(), "backups");
            File snapshotDirectory = new File(backupRoot, "snapshots");
            File fullDirectory = new File(backupRoot, "full");
            ensurePrivateDirectory(context.getFilesDir(), snapshotDirectory);
            ensurePrivateDirectory(context.getFilesDir(), fullDirectory);

            String stamp = new SimpleDateFormat("yyyyMMdd", Locale.ROOT) {{
                setTimeZone(TimeZone.getTimeZone("UTC"));
            }}.format(new Date());
            encrypted = new File(fullDirectory, "full-" + stamp + ".myobak");
            File pendingMarker = new File(encrypted.getAbsolutePath() + ".pending");
            if (encrypted.isFile() && encrypted.length() > 0 && pendingMarker.isFile()) {
                UnattendedBackupScheduler.enqueueUpload(context, account, encrypted.getAbsolutePath());
                return Result.success();
            }
            if (encrypted.exists() || pendingMarker.exists()) {
                throw new IOException("Today's backup path is occupied by an incomplete or ambiguous file");
            }

            try (AndroidKeystoreCredentialVault.Credentials credentials = vault.load()) {
                RoomDatabaseSnapshotter.Snapshot captured = new RoomDatabaseSnapshotter().create(
                        context, AppDatabase.getInstance(context), snapshotDirectory);
                snapshot = captured.file;
                // The existing portable API accepts String for Recovery Key. This is a short-lived
                // JVM String; mutable char[] and byte[] buffers are wiped by their owners.
                String recoveryKey = new String(credentials.recoveryKey);
                localWrappedKey = BackupCrypto.encryptFile(snapshot, encrypted,
                        credentials.recoveryPassword, recoveryKey, new AndroidKeystoreKeyProtector());
                writeAndSync(new File(encrypted.getAbsolutePath() + ".localkey"), localWrappedKey);
                writeAndSync(pendingMarker, ("created=" + System.currentTimeMillis()
                        + "\nsha256=" + captured.sha256 + "\n").getBytes(StandardCharsets.US_ASCII));
                UnattendedBackupScheduler.enqueueUpload(context, account, encrypted.getAbsolutePath());
            }
            BackupLocalRetention.prune(fullDirectory, System.currentTimeMillis());
            return Result.success();
        } catch (java.security.GeneralSecurityException invalidVaultOrEncryption) {
            BackupNotifications.showFailure(context,
                    "ข้อมูลกู้คืนหรือกุญแจของอุปกรณ์ใช้ไม่ได้ ต้องป้อนข้อมูลกู้คืนใหม่");
            return Result.failure();
        } catch (IOException storageFailure) {
            BackupNotifications.showFailure(context, "พื้นที่จัดเก็บหรือการสร้างไฟล์สำรองมีปัญหา ระบบจะลองใหม่");
            return Result.retry();
        } catch (RuntimeException databaseOrWorkerFailure) {
            return Result.retry();
        } finally {
            if (!pendingPublished && encrypted != null) {
                // Never leave an untracked partial envelope that could block the next daily run.
                deleteQuietly(encrypted);
                deleteQuietly(new File(encrypted.getAbsolutePath() + ".localkey"));
                deleteQuietly(new File(encrypted.getAbsolutePath() + ".pending"));
            }
            if (snapshot != null && snapshot.exists()) {
                //noinspection ResultOfMethodCallIgnored
                snapshot.delete();
            }
            if (localWrappedKey != null) java.util.Arrays.fill(localWrappedKey, (byte) 0);
        }
    }

    private static void ensurePrivateDirectory(File root, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create backup directory");
        }
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        if (!directory.getCanonicalPath().startsWith(canonicalRoot) || !directory.canWrite()) {
            throw new IOException("Backup directory is outside app-private storage");
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void writeAndSync(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }
}
