package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Queues an already-encrypted backup for upload when network connectivity is available. */
public final class DriveBackupWorkScheduler {
    private DriveBackupWorkScheduler() {}

    public static void enqueueUpload(@NonNull Context context, @NonNull File encryptedBackup,
                                     @NonNull String googleAccountName) {
        if (!encryptedBackup.getName().endsWith(".myobak")) {
            throw new IllegalArgumentException("Only encrypted .myobak backups can be queued");
        }
        Data input = new Data.Builder()
                .putString(GoogleDriveBackupUploadWorker.INPUT_ACCOUNT_NAME, googleAccountName)
                .putString(GoogleDriveBackupUploadWorker.INPUT_ENCRYPTED_BACKUP_PATH,
                        encryptedBackup.getAbsolutePath())
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(GoogleDriveBackupUploadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS)
                .build();
        String uniqueName = "drive-backup-" + encryptedBackup.getName();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request);
    }

    /** Queues an encrypted Drive object for download into private staging storage. */
    public static void enqueueDownload(@NonNull Context context, @NonNull String googleAccountName,
                                       @NonNull String driveFileId) {
        if (googleAccountName.trim().isEmpty()
                || !GoogleDriveBackupDownloadWorker.isValidDriveFileId(driveFileId)) {
            throw new IllegalArgumentException("Google account and valid Drive file ID are required");
        }
        Data input = new Data.Builder()
                .putString(GoogleDriveBackupDownloadWorker.INPUT_ACCOUNT_NAME, googleAccountName)
                .putString(GoogleDriveBackupDownloadWorker.INPUT_DRIVE_FILE_ID, driveFileId)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                GoogleDriveBackupDownloadWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork("drive-backup-download-" + driveFileId,
                        ExistingWorkPolicy.KEEP, request);
    }
}
