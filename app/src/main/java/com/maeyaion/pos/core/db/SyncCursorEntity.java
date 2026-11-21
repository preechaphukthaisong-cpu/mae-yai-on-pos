package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Last durably applied server sequence, isolated by store. */
@Entity(tableName = "sync_cursor")
public final class SyncCursorEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "store_id") public final String store_id;
    @ColumnInfo(name = "last_applied_sequence", defaultValue = "0") public final long last_applied_sequence;
    @NonNull @ColumnInfo(name = "updated_at_utc") public final String updated_at_utc;

    public SyncCursorEntity(@NonNull String store_id, long last_applied_sequence,
            @NonNull String updated_at_utc) {
        this.store_id=store_id; this.last_applied_sequence=last_applied_sequence;
        this.updated_at_utc=updated_at_utc;
    }
}
