package com.maeyaion.pos.core.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.BackoffPolicy;
import androidx.work.Data;

import java.util.concurrent.TimeUnit;

/** Unique WorkManager orchestration; scheduling is explicit and never invoked at app startup. */
public final class SyncScheduler {
    private static final String PERIODIC_NAME = "maeyai-pos-sync-periodic-v1";
    private static final String ONCE_NAME = "maeyai-pos-sync-once-v1";

    private SyncScheduler() {}

    public static void schedulePeriodic(@NonNull Context context, @NonNull String serverUrl,
            @NonNull String webClientId, @NonNull String storeId) throws SyncException {
        requireConfig(serverUrl, webClientId, storeId);
        SyncReadinessGate.requireEnabled(context);
        Data input = input(serverUrl, webClientId, storeId);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 15, TimeUnit.MINUTES)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void enqueueOnce(@NonNull Context context, @NonNull String serverUrl,
            @NonNull String webClientId, @NonNull String storeId) throws SyncException {
        requireConfig(serverUrl, webClientId, storeId);
        SyncReadinessGate.requireEnabled(context);
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setInputData(input(serverUrl, webClientId, storeId))
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                ONCE_NAME, ExistingWorkPolicy.KEEP, request);
    }

    public static void cancel(@NonNull Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(PERIODIC_NAME);
        manager.cancelUniqueWork(ONCE_NAME);
    }

    private static Data input(String url, String clientId, String storeId) {
        return new Data.Builder().putString(SyncWorker.KEY_SERVER_URL, url)
                .putString(SyncWorker.KEY_WEB_CLIENT_ID, clientId)
                .putString(SyncWorker.KEY_STORE_ID, storeId).build();
    }

    private static void requireConfig(String url, String clientId, String storeId)
            throws SyncException {
        if (url == null || !url.trim().startsWith("https://")
                || clientId == null || !clientId.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")
                || storeId == null || !storeId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new SyncException("Valid HTTPS server, Google OAuth client and store IDs are required");
        }
    }
}
