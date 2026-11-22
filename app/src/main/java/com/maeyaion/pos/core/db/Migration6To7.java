package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds durable quarantine for remote replay failures without changing business tables. */
public final class Migration6To7 {
    private Migration6To7() {}
    public static final Migration INSTANCE = new Migration(6, 7) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE sync_replay_conflict (event_id TEXT NOT NULL PRIMARY KEY, store_id TEXT NOT NULL, server_sequence INTEGER NOT NULL CHECK(server_sequence>0), entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, reason TEXT NOT NULL, created_at_utc TEXT NOT NULL)");
            db.execSQL("CREATE INDEX index_sync_replay_conflict_store_id_created_at_utc ON sync_replay_conflict(store_id,created_at_utc)");
        }
    };
}
