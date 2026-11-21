package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/** Idempotent first-run store and device registration. Execute from a background worker. */
public final class SyncAccountBootstrapper {
    private final SyncApiClient api;

    public SyncAccountBootstrapper(@NonNull SyncApiClient api) {
        if (api == null) throw new IllegalArgumentException("SyncApiClient is required");
        this.api = api;
    }

    /**
     * Ensures a store exists for the signed-in Google identity and this installation
     * is registered. A 404 from GET /stores/me is the only condition that creates a store.
     */
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
