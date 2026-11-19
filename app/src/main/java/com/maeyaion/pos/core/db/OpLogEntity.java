package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "op_log",
        indices = {@Index(value = "created_at_utc"),
                @Index(value = {"entity_type", "entity_id"})})
public final class OpLogEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String action;
    @NonNull public final String entity_type;
    public final String entity_id;
    public final String detail_json;
    @NonNull public final String created_at_utc;

    public OpLogEntity(@NonNull String id, @NonNull String action, @NonNull String entity_type,
            String entity_id, String detail_json, @NonNull String created_at_utc) {
        this.id = id; this.action = action; this.entity_type = entity_type;
        this.entity_id = entity_id; this.detail_json = detail_json;
        this.created_at_utc = created_at_utc;
    }
}