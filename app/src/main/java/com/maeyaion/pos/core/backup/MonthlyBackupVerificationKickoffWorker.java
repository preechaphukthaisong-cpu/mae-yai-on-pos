package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Starts a monthly remote-download then isolated staging-restore verification chain. */
public final class MonthlyBackupVerificationKickoffWorker extends Worker {
    public static final String UNIQUE_CHAIN = "maeyaion-pos.monthly-backup-verification.run.v1";
    public static final String UNIQUE_PERIODIC = "maeyaion-pos.monthly-backup-verification.schedule.v1";
    public static final String VERIFY_TAG = "maeyaion-pos.monthly-backup-verification";

    public MonthlyBackupVerificationKickoffWorker(@NonNull Context context,
            @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (!UnattendedBackupScheduler.isEnabled(context)) return Result.success();
        String account = context.getSharedPreferences(UnattendedBackupScheduler.PREFS,
                Context.MODE_PRIVATE).getString(UnattendedBackupScheduler.KEY_ACCOUNT, null);
        String fileId = context.getSharedPreferences(UnattendedBackupScheduler.PREFS,
                Context.MODE_PRIVATE).getString(UnattendedBackupScheduler.KEY_LAST_DRIVE_FILE_ID, null);
        if (account == null || fileId == null) {
            BackupNotifications.showFailure(context,
                    "ยังไม่มีไฟล์สำรองบน Drive สำหรับตรวจสอบรายเดือน");
            return Result.retry();
        }

        Constraints downloadConstraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .build();
        OneTimeWorkRequest download = new OneTimeWorkRequest.Builder(
                GoogleDriveBackupDownloadWorker.class)
                .setConstraints(downloadConstraints)
                .setInputData(new androidx.work.Data.Builder()
                        .putString(GoogleDriveBackupDownloadWorker.INPUT_ACCOUNT_NAME, account)
                        .putString(GoogleDriveBackupDownloadWorker.INPUT_DRIVE_FILE_ID, fileId)
                        .build())
                .addTag(VERIFY_TAG)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,
                        androidx.work.WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        Constraints verifyConstraints = new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .build();
        OneTimeWorkRequest verify = new OneTimeWorkRequest.Builder(
                MonthlyBackupStagingVerifyWorker.class)
                .setConstraints(verifyConstraints)
                .addTag(VERIFY_TAG)
                .build();
        WorkManager.getInstance(context).beginUniqueWork(
                UNIQUE_CHAIN, ExistingWorkPolicy.KEEP, download)
                .then(verify)
                .enqueue();
        return Result.success();
    }
}
