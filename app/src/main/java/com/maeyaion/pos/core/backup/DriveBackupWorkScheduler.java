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
import java.util.UUID;

/** Queues an already-encrypted backup for upload when network connectivity is available. */
public final class DriveBackupWorkScheduler {
    private DriveBackupWorkScheduler() {}

    public static void enqueueUpload(@NonNull Context context, @NonNull File encryptedBackup,
                                     @NonNull String googleAccountName) {
        enqueueUpload(context, encryptedBackup, googleAccountName, new long[0]);
    }

    /** Journal sequence acknowledgements are sent only as bounded numeric IDs, never payloads or secrets. */
    public static void enqueueUpload(@NonNull Context context, @NonNull File encryptedBackup,
                                     @NonNull String googleAccountName, long[] journalSequences) {
        if (encryptedBackup == null || !encryptedBackup.getName().endsWith(".myobak")
                || googleAccountName.trim().isEmpty()) {
            throw new IllegalArgumentException("Encrypted backup and Google account are required");
        }
        StringBuilder sequenceCsv = new StringBuilder();
        if (journalSequences != null) {
            if (journalSequences.length > 100) {
                throw new IllegalArgumentException("A delta upload may acknowledge at most 100 rows");
            }
            long previous = 0;
            for (long sequence : journalSequences) {
                if (sequence <= previous) {
                    throw new IllegalArgumentException("Journal sequences must be positive and strictly increasing");
                }
                if (sequenceCsv.length() > 0) sequenceCsv.append(',');
                sequenceCsv.append(sequence);
                previous = sequence;
            }
        }
        Data input = new Data.Builder()
                .putString(GoogleDriveBackupUploadWorker.INPUT_ACCOUNT_NAME, googleAccountName)
                .putString(GoogleDriveBackupUploadWorker.INPUT_ENCRYPTED_BACKUP_PATH,
                        encryptedBackup.getAbsolutePath())
                .putString(GoogleDriveBackupUploadWorker.INPUT_JOURNAL_SEQUENCES,
                        sequenceCsv.toString())
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
    public static UUID enqueueDownload(@NonNull Context context, @NonNull String googleAccountName,
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
        return request.getId();
    }

    /** Lists remote encrypted backup metadata for the selected Google account. */
    public static UUID enqueueList(@NonNull Context context, @NonNull String googleAccountName) {
        if (googleAccountName.trim().isEmpty()) {
            throw new IllegalArgumentException("Google account is required");
        }
        Data input = new Data.Builder()
                .putString(GoogleDriveBackupListWorker.INPUT_ACCOUNT_NAME, googleAccountName)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                GoogleDriveBackupListWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork("drive-backup-list", ExistingWorkPolicy.REPLACE, request);
        return request.getId();
    }

}
