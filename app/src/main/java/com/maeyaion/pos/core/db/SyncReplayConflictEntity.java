package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Durable quarantine for a remote event that cannot be safely replayed. */
@Entity(tableName = "sync_replay_conflict",
        indices = {@Index(value = {"store_id", "created_at_utc"})})
public final class SyncReplayConflictEntity {
    @PrimaryKey @NonNull public final String event_id;
    @NonNull public final String store_id;
    public final long server_sequence;
    @NonNull public final String entity_type;
    @NonNull public final String entity_id;
    @NonNull public final String reason;
    @NonNull public final String created_at_utc;

    public SyncReplayConflictEntity(@NonNull String event_id, @NonNull String store_id,
            long server_sequence, @NonNull String entity_type, @NonNull String entity_id,
            @NonNull String reason, @NonNull String created_at_utc) {
        this.event_id = event_id;
        this.store_id = store_id;
        this.server_sequence = server_sequence;
        this.entity_type = entity_type;
        this.entity_id = entity_id;
        this.reason = reason;
        this.created_at_utc = created_at_utc;
    }
}
