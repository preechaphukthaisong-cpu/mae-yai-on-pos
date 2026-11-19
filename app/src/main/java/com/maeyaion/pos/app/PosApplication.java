package com.maeyaion.pos.app;

import android.app.Application;

import com.maeyaion.pos.core.db.AppDatabase;

public final class PosApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppDatabase.getInstance(this);
    }

    /**
     * Resolve the current singleton on every access. Restore replaces the
     * underlying Room instance, so caching it in Application would be stale.
     */
    public AppDatabase database() {
        return AppDatabase.getInstance(this);
    }
}