package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncCursorEntity;

import java.time.Instant;

import org.json.JSONException;
import org.json.JSONObject;

/** Idempotent first-run store and device registration. Execute from a background worker. */
public final class SyncAccountBootstrapper {
    private final SyncApiClient api;
    private final AppDatabase database;

    public SyncAccountBootstrapper(@NonNull SyncApiClient api) {
        if (api == null) throw new IllegalArgumentException("SyncApiClient is required");
        this(api, null);
    }

    public SyncAccountBootstrapper(@NonNull SyncApiClient api, AppDatabase database) {
        if (api == null) throw new IllegalArgumentException("SyncApiClient is required");
        this.api = api;
        this.database = database;
    }

    /**
     * Ensures a store exists for the signed-in Google identity and this installation
     * is registered. A 404 from GET /stores/me is the only condition that creates a store.
     */

    /** Bind pre-cloud offline events only after both store and device registration succeed. */
    private void bindLocalOutbox(JSONObject store, JSONObject device) throws SyncException {
        try {
            String storeId = store.getString("storeId");
            device.getString("deviceId");
            if (storeId.trim().isEmpty()) throw new SyncException("Sync server returned an empty store ID");
            database.runInTransaction(() -> {
                String existing = database.syncQueueDao().boundStoreId();
                if (existing != null && !existing.equals(storeId)) {
                    throw new IllegalStateException("This local POS database is already bound to another store; refusing cross-account data transfer");
                }
                database.syncQueueDao().saveCursor(new SyncCursorEntity(
                        storeId, 0L, Instant.now().toString()));
                database.syncQueueDao().bindUnboundOutbox(storeId);
            });
        } catch (JSONException error) {
            throw new SyncException("Sync registration response is missing store/device identity", error);
        } catch (IllegalStateException error) {
            throw new SyncException(error.getMessage(), error);
        }
    }

    @NonNull
    public JSONObject ensureRegistered() throws SyncException {
        JSONObject store;
        try {
            store = api.getStore();
        } catch (SyncException error) {
            if (error.getHttpStatus() != 404) throw error;
            store = api.createStore();
        }
        JSONObject device = api.registerDevice();
        if (database != null) bindLocalOutbox(store, device);
        JSONObject result = new JSONObject();
        try {
            result.put("store", store);
            result.put("device", device);
        } catch (JSONException error) {
            throw new SyncException("Unable to prepare sync registration result", error);
        }
        return result;
    }
}
