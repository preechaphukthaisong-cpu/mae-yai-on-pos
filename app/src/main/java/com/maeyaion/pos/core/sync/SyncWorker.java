package com.maeyaion.pos.core.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.maeyaion.pos.core.db.AppDatabase;

import org.json.JSONObject;

/** Bounded, retryable sync pass. It never runs unless the explicit readiness gate is enabled. */
public final class SyncWorker extends Worker {
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_WEB_CLIENT_ID = "web_client_id";
    public static final String KEY_STORE_ID = "store_id";
    private static final int MAX_PULL_PAGES = 20;
    private static final int MAX_REPLAY_EVENTS = 500;

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!SyncReadinessGate.isEnabled(context)) return Result.success();
        String serverUrl = getInputData().getString(KEY_SERVER_URL);
        String webClientId = getInputData().getString(KEY_WEB_CLIENT_ID);
        String expectedStoreId = getInputData().getString(KEY_STORE_ID);
        if (serverUrl == null || webClientId == null || expectedStoreId == null
                || serverUrl.trim().isEmpty() || webClientId.trim().isEmpty()
                || expectedStoreId.trim().isEmpty()) return Result.failure();

        try {
            GoogleSignInClient signIn = SyncGoogleSignIn.createClient(context, webClientId);
            SyncApiClient api = new SyncApiClient(serverUrl,
                    new GoogleSignInTokenProvider(signIn), new DeviceIdentityStore(context));
            AppDatabase database = AppDatabase.getInstance(context);
            JSONObject registration = new SyncAccountBootstrapper(api, database).ensureRegistered();
            String registeredStore = registration.getJSONObject("store").getString("storeId");
            if (!expectedStoreId.equals(registeredStore)) {
                SyncReadinessGate.disable(context, "Registered store changed; manual re-enrollment required");
                return Result.failure();
            }

            StoreKeyManager keys = new StoreKeyManager(context);
            // Verify/install the complete approved epoch history on every pass. Checking only
            // the latest key would strand historical events after a device joins or rotates.
            new StoreKeyEnrollmentService(context, api)
                    .installAllApprovedDeviceKeys(registeredStore);
            keys.getCurrentEpoch(registeredStore);
            SyncPushCoordinator push = new SyncPushCoordinator(database, api, keys);
            SyncPullCoordinator pull = new SyncPullCoordinator(database, api);
            SyncInboxProcessor inbox = new SyncInboxProcessor(database);
            push.pushPending(registeredStore);

            int replayed = 0;
            for (int page = 0; page < MAX_PULL_PAGES && !isStopped(); page++) {
                SyncPullCoordinator.PullResult result = pull.pullPage(registeredStore);
                while (replayed < MAX_REPLAY_EVENTS
                        && inbox.applyNext(registeredStore, java.time.Instant.now().toString(), keys)) {
                    replayed++;
                }
                if (replayed >= MAX_REPLAY_EVENTS || !result.hasMore) break;
            }
            if (isStopped()) return Result.retry();
            push.pushPending(registeredStore);
            return Result.success();
        } catch (SyncException error) {
            if (error.getHttpStatus() == 401 || error.getHttpStatus() == 403) {
                SyncReadinessGate.disable(context, "Authentication or device authorization requires attention");
                return Result.failure();
            }
            return getRunAttemptCount() >= 8 ? Result.failure() : Result.retry();
        } catch (Exception error) {
            return getRunAttemptCount() >= 8 ? Result.failure() : Result.retry();
        }
    }
}
