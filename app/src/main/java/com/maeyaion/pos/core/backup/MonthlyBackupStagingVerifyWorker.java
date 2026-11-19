package com.maeyaion.pos.core.backup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.maeyaion.pos.core.db.AppDatabase;

import java.io.File;
import java.io.IOException;

/**
 * Decrypts the most recently confirmed Drive archive into an isolated temporary SQLite file.
 * It checks digest/GCM during decryption and SQLite integrity without opening or replacing live DB.
 */
public final class MonthlyBackupStagingVerifyWorker extends Worker {
    public MonthlyBackupStagingVerifyWorker(@NonNull Context context,
            @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        String downloadedPath = getInputData().getString(
                GoogleDriveBackupDownloadWorker.OUTPUT_BACKUP_PATH);
        if (downloadedPath == null) return Result.failure();
        File downloaded = new File(downloadedPath);
        File staging = new File(context.getNoBackupFilesDir(),
                "backup-verification/" + getId() + ".sqlite");
        try {
            File parent = staging.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("Unable to create isolated verification directory");
            }
            try (AndroidKeystoreCredentialVault.Credentials credentials =
                         new AndroidKeystoreCredentialVault(context).load()) {
                // BackupCrypto's recovery API accepts String for the Recovery Key, but this
                // verification path uses only the Recovery Password wrapper.
                BackupCrypto.decryptFileTo(downloaded, staging, credentials.recoveryPassword, null);
            }
            int expectedVersion = AppDatabase.getInstance(context)
                    .getOpenHelper().getReadableDatabase().getVersion();
            verifyStagingDatabase(staging, expectedVersion);
            context.getSharedPreferences(UnattendedBackupScheduler.PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(UnattendedBackupScheduler.KEY_LAST_VERIFIED,
                            System.currentTimeMillis()).apply();
            BackupNotifications.showVerificationSuccess(context);
            return Result.success();
        } catch (Exception verificationFailure) {
            BackupNotifications.showFailure(context,
                    "การตรวจสอบไฟล์สำรองรายเดือนไม่ผ่าน ต้องตรวจสอบและสำรองใหม่");
            return Result.failure();
        } finally {
            deleteQuietly(staging);
            deleteQuietly(downloaded);
        }
    }

    private static void verifyStagingDatabase(File file, int expectedVersion) throws IOException {
        SQLiteDatabase database = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor integrity = database.rawQuery("PRAGMA integrity_check", null)) {
                if (!integrity.moveToFirst() || !"ok".equalsIgnoreCase(integrity.getString(0))) {
                    throw new IOException("Staging SQLite integrity_check failed");
                }
            }
            try (Cursor foreignKeys = database.rawQuery("PRAGMA foreign_key_check", null)) {
                if (foreignKeys.moveToFirst()) throw new IOException("Staging database has foreign-key violations");
            }
            try (Cursor version = database.rawQuery("PRAGMA user_version", null)) {
                if (!version.moveToFirst() || version.getInt(0) != expectedVersion) {
                    throw new IOException("Staging database schema version mismatch");
                }
            }
        } finally {
            database.close();
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
