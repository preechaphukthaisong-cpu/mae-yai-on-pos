package com.maeyaion.pos.core.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {AppMetaEntity.class}, version = 1, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract AppMetaDao appMetaDao();

    public static AppDatabase getInstance(Context context) {
        AppDatabase local = INSTANCE;
        if (local == null) {
            synchronized (AppDatabase.class) {
                local = INSTANCE;
                if (local == null) {
                    local = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "maeyaion_pos.db")
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .addCallback(new Callback() {
                                @Override
                                public void onOpen(@NonNull SupportSQLiteDatabase db) {
                                    super.onOpen(db);
                                    db.setForeignKeyConstraintsEnabled(true);
                                    db.execSQL("PRAGMA busy_timeout=5000");
                                }
                            })
                            .build();
                    INSTANCE = local;
                }
            }
        }
        return local;
    }
}
