package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Deduplicated remote event receipt; applied=1 is written only with domain replay commit. */
@Entity(tableName = "sync_inbox",
        indices = {@Index(value = {"store_id", "server_sequence"}, unique = true),
                @Index(value = {"store_id", "applied", "server_sequence"})})
public final class SyncInboxEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "event_id") public final String event_id;
    @NonNull @ColumnInfo(name = "store_id") public final String store_id;
    @ColumnInfo(name = "server_sequence") public final long server_sequence;
    @NonNull @ColumnInfo(name = "operation") public final String operation;
    @NonNull @ColumnInfo(name = "entity_type") public final String entity_type;
    @NonNull @ColumnInfo(name = "entity_id") public final String entity_id;
    @ColumnInfo(name = "base_version") public final long base_version;
    @ColumnInfo(name = "entity_version") public final long entity_version;
    @NonNull @ColumnInfo(name = "payload_json") public final String payload_json;
    @NonNull @ColumnInfo(name = "payload_sha256") public final String payload_sha256;
    @NonNull @ColumnInfo(name = "received_at_utc") public final String received_at_utc;
    @ColumnInfo(name = "applied", defaultValue = "0") public final int applied;

    public SyncInboxEntity(@NonNull String event_id, @NonNull String store_id,
            long server_sequence, @NonNull String operation, @NonNull String entity_type,
            @NonNull String entity_id, long base_version, long entity_version,
            @NonNull String payload_json, @NonNull String payload_sha256,
            @NonNull String received_at_utc, int applied) {
        this.event_id=event_id; this.store_id=store_id; this.server_sequence=server_sequence;
        this.operation=operation; this.entity_type=entity_type; this.entity_id=entity_id;
        this.base_version=base_version; this.entity_version=entity_version;
        this.payload_json=payload_json; this.payload_sha256=payload_sha256;
        this.received_at_utc=received_at_utc; this.applied=applied;
    }
}
