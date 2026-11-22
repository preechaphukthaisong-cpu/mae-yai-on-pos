package com.maeyaion.pos.core.sync;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Fail-closed sync readiness switch. Defaults to disabled and is not enabled by app startup. */
public final class SyncReadinessGate {
    private static final String PREFS = "maeyai_sync_readiness_v1";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_REASON = "disabled_reason";

    private SyncReadinessGate() {}

    public static boolean isEnabled(@NonNull Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, false);
    }

    public static void enableAfterQualification(@NonNull Context context) throws SyncException {
        if (context == null) throw new IllegalArgumentException("Context is required");
        // This API is deliberately explicit; callers must first complete key enrollment,
        // baseline validation, replay coverage and conflict-resolution qualification.
        if (!preferences(context).edit().putBoolean(KEY_ENABLED, true)
                .remove(KEY_REASON).commit()) {
            throw new SyncException("Unable to persist sync readiness state");
        }
    }

    public static void disable(@NonNull Context context, @NonNull String reason) {
        String safe = reason == null ? "Disabled" : reason;
        if (safe.length() > 240) safe = safe.substring(0, 240);
        preferences(context).edit().putBoolean(KEY_ENABLED, false)
                .putString(KEY_REASON, safe).commit();
    }

    public static void requireEnabled(Context context) throws SyncException {
        if (!isEnabled(context)) throw new SyncException("Live sync is disabled until all readiness gates pass");
    }

    private static SharedPreferences preferences(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
