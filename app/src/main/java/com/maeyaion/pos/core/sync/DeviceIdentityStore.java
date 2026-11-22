package com.maeyaion.pos.core.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.UUID;

/**
 * Stable installation identity stored in app-private preferences.
 * Clearing app data creates a new identity; uninstall/reinstall is a new device.
 */
public final class DeviceIdentityStore {
    private static final String PREFS = "maeyai_sync_identity";
    private static final String KEY_DEVICE_ID = "device_id";

    private final SharedPreferences preferences;
    private final Context context;

    public DeviceIdentityStore(@NonNull Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @NonNull
    public synchronized String getOrCreateDeviceId() throws SyncException {
        String existing = preferences.getString(KEY_DEVICE_ID, null);
        if (isUuid(existing)) return existing;
        String generated = UUID.randomUUID().toString();
        if (!preferences.edit().putString(KEY_DEVICE_ID, generated).commit()) {
            throw new SyncException("Unable to persist this installation's sync identity");
        }
        return generated;
    }

    @NonNull
    public String getPublicKeyBase64() throws SyncException {
        return new DeviceKeyPairManager(context).getPublicKeyBase64();
    }

    @NonNull
    public String getDeviceLabel() {
        String model = Build.MODEL == null ? "Android device" : Build.MODEL.trim();
        if (model.isEmpty()) model = "Android device";
        if (model.length() > 65) model = model.substring(0, 65);
        return String.format(Locale.ROOT, "%s (Android %s)", model, Build.VERSION.RELEASE);
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
