package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Durable local events. Rows are retained after acknowledgement for echo suppression. */
@Entity(tableName = "sync_outbox",
        indices = {@Index(value = {"delivery_state", "created_at_utc", "event_id"}),
                @Index(value = "event_id", unique = true)})
public final class SyncOutboxEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "event_id") public final String event_id;
    @NonNull @ColumnInfo(name = "store_id") public final String store_id;
    @NonNull @ColumnInfo(name = "operation") public final String operation;
    @NonNull @ColumnInfo(name = "entity_type") public final String entity_type;
    @NonNull @ColumnInfo(name = "entity_id") public final String entity_id;
    @ColumnInfo(name = "base_version") public final long base_version;
    @NonNull @ColumnInfo(name = "payload_json") public final String payload_json;
    @NonNull @ColumnInfo(name = "payload_sha256") public final String payload_sha256;
    @NonNull @ColumnInfo(name = "created_at_utc") public final String created_at_utc;
    @NonNull @NonNull @ColumnInfo(name = "delivery_state", defaultValue = "'PENDING'") public final String delivery_state;
    @ColumnInfo(name = "attempt_count", defaultValue = "0") public final int attempt_count;
    @ColumnInfo(name = "last_attempt_at_utc") public final String last_attempt_at_utc;
    @ColumnInfo(name = "last_error") public final String last_error;

    public SyncOutboxEntity(@NonNull String event_id, @NonNull String store_id,
            @NonNull String operation, @NonNull String entity_type, @NonNull String entity_id,
            long base_version, @NonNull String payload_json, @NonNull String payload_sha256,
            @NonNull String created_at_utc, @NonNull String delivery_state,
            int attempt_count, String last_attempt_at_utc, String last_error) {
        this.event_id = event_id; this.store_id = store_id; this.operation = operation;
        this.entity_type = entity_type; this.entity_id = entity_id; this.base_version = base_version;
        this.payload_json = payload_json; this.payload_sha256 = payload_sha256;
        this.created_at_utc = created_at_utc; this.delivery_state = delivery_state;
        this.attempt_count = attempt_count; this.last_attempt_at_utc = last_attempt_at_utc;
        this.last_error = last_error;
    }
}
