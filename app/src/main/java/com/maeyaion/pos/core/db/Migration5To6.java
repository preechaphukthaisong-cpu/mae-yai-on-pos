package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds durable sync queues without altering or deleting any existing POS data. */
public final class Migration5To6 {
    private Migration5To6() {}
    public static final Migration INSTANCE = new Migration(5, 6) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE sync_outbox (event_id TEXT NOT NULL PRIMARY KEY, store_id TEXT NOT NULL, operation TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, base_version INTEGER NOT NULL CHECK(base_version>=0), payload_json TEXT NOT NULL, payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64), created_at_utc TEXT NOT NULL, delivery_state TEXT NOT NULL DEFAULT 'PENDING' CHECK(delivery_state IN ('PENDING')), attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0), last_attempt_at_utc TEXT, last_error TEXT)");
            db.execSQL("CREATE UNIQUE INDEX index_sync_outbox_event_id ON sync_outbox(event_id)");
            db.execSQL("CREATE INDEX index_sync_outbox_delivery_state_created_at_utc_event_id ON sync_outbox(delivery_state,created_at_utc,event_id)");
            db.execSQL("CREATE TABLE sync_inbox (event_id TEXT NOT NULL PRIMARY KEY, store_id TEXT NOT NULL, server_sequence INTEGER NOT NULL CHECK(server_sequence>0), operation TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, base_version INTEGER NOT NULL CHECK(base_version>=0), entity_version INTEGER NOT NULL CHECK(entity_version>0), payload_json TEXT NOT NULL, payload_sha256 TEXT NOT NULL CHECK(length(payload_sha256)=64), received_at_utc TEXT NOT NULL, applied INTEGER NOT NULL DEFAULT 0 CHECK(applied IN (0,1)))");
            db.execSQL("CREATE UNIQUE INDEX index_sync_inbox_store_id_server_sequence ON sync_inbox(store_id,server_sequence)");
            db.execSQL("CREATE INDEX index_sync_inbox_store_id_applied_server_sequence ON sync_inbox(store_id,applied,server_sequence)");
            db.execSQL("CREATE TABLE sync_cursor (store_id TEXT NOT NULL PRIMARY KEY, last_applied_sequence INTEGER NOT NULL DEFAULT 0 CHECK(last_applied_sequence>=0), updated_at_utc TEXT NOT NULL)");
            db.execSQL("CREATE TABLE sync_entity_version (entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>=0), updated_at_utc TEXT NOT NULL, PRIMARY KEY(entity_type,entity_id))");
        }
    };
}
