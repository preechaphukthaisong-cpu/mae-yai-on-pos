package com.maeyaion.pos.core.backup;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.util.concurrent.TimeUnit;

/** Owns the opt-in daily unattended full-backup schedule. */
public final class UnattendedBackupScheduler {
    public static final String UNIQUE_PERIODIC_WORK = "maeyaion-pos.unattended-full-backup.v1";
    public static final String PREFS = "unattended_backup_preferences";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_ACCOUNT = "account_name";
    public static final String KEY_LAST_SUCCESS = "last_success_epoch_ms";
    public static final String UPLOAD_WORK_TAG = "maeyaion-pos.unattended-full-backup-upload.v1";
    private static final String KEY_LAST_FAILURE = "last_failure_epoch_ms";

    private UnattendedBackupScheduler() {}

    /**
     * Consent is explicit and separate from merely selecting a Google account.
     * The Recovery Key acknowledgement must be confirmed by the setup UI.
     */
    public static void enable(@NonNull Context context, @NonNull String accountName,
            boolean unattendedAccessConfirmed, boolean recoveryKeyOfflineCopyConfirmed)
            throws Exception {
        if (TextUtils.isEmpty(accountName) || !unattendedAccessConfirmed
                || !recoveryKeyOfflineCopyConfirmed) {
            throw new IllegalArgumentException("Explicit unattended-access and offline-key confirmation are required");
        }
        AndroidKeystoreCredentialVault vault =
                new AndroidKeystoreCredentialVault(context.getApplicationContext());
        if (!vault.exists()) {
            throw new IllegalStateException("Recovery credentials must be saved before enabling unattended backup");
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_ACCOUNT, accountName)
                .remove(KEY_LAST_FAILURE)
                .apply();
        schedule(context);
    }

    /** Account changes require an explicit confirmation from the caller/UI. */
    public static void changeAccount(@NonNull Context context, @NonNull String accountName,
            boolean explicitlyConfirmed) {
        if (!explicitlyConfirmed) throw new IllegalArgumentException("Account change was not confirmed");
        if (TextUtils.isEmpty(accountName)) throw new IllegalArgumentException("Account name is required");
        if (isEnabled(context)) {
            WorkManager.getInstance(context).cancelAllWorkByTag(UPLOAD_WORK_TAG);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ACCOUNT, accountName).apply();
        if (isEnabled(context)) schedule(context);
    }

    public static void disable(@NonNull Context context) throws Exception {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK);
        WorkManager.getInstance(context).cancelAllWorkByTag(UPLOAD_WORK_TAG);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, false).remove(KEY_ACCOUNT).apply();
        new AndroidKeystoreCredentialVault(context.getApplicationContext()).clear();
        // Uploaded cloud archives are intentionally not deleted by disabling local automation.
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void enqueueUpload(@NonNull Context context, @NonNull String accountName,
            @NonNull String encryptedBackupPath) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .build();
        androidx.work.Data input = new androidx.work.Data.Builder()
                .putString(GoogleDriveBackupUploadWorker.INPUT_ACCOUNT_NAME, accountName)
                .putString(GoogleDriveBackupUploadWorker.INPUT_ENCRYPTED_BACKUP_PATH,
                        encryptedBackupPath)
                .putBoolean(GoogleDriveBackupUploadWorker.INPUT_FULL_BACKUP, true)
                .build();
        OneTimeWorkRequest upload = new OneTimeWorkRequest.Builder(GoogleDriveBackupUploadWorker.class)
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(UPLOAD_WORK_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();
        String uniqueName = UPLOAD_WORK_TAG + "." + new java.io.File(encryptedBackupPath).getName();
        WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName, ExistingPeriodicWorkPolicy.KEEP, upload);
    }

    private static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                // Snapshot generation is local/offline; do not require a network connection here.
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UnattendedFullBackupWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
