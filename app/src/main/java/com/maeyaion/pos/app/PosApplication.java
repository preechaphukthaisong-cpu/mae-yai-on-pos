package com.maeyaion.pos.app;
import android.app.Application;
import com.maeyaion.pos.core.db.AppDatabase;
public final class PosApplication extends Application {
    private AppDatabase database;
    @Override public void onCreate() {
        super.onCreate();
        database = AppDatabase.getInstance(this);
    }
    public AppDatabase database() { return database; }
}
