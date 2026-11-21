package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/** Local aggregate version used for optimistic concurrency and conflict detection. */
@Entity(tableName = "sync_entity_version",
        primaryKeys = {"entity_type", "entity_id"})
public final class SyncEntityVersionEntity {
    @NonNull @ColumnInfo(name = "entity_type") public final String entity_type;
    @NonNull @ColumnInfo(name = "entity_id") public final String entity_id;
    @ColumnInfo(name = "version") public final long version;
    @NonNull @ColumnInfo(name = "updated_at_utc") public final String updated_at_utc;

    public SyncEntityVersionEntity(@NonNull String entity_type, @NonNull String entity_id,
            long version, @NonNull String updated_at_utc) {
        this.entity_type=entity_type; this.entity_id=entity_id; this.version=version;
        this.updated_at_utc=updated_at_utc;
    }
}
