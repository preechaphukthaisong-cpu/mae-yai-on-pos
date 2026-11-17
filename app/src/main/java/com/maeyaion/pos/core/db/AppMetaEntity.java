package com.maeyaion.pos.core.db;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "app_meta")
public final class AppMetaEntity {
    @PrimaryKey @NonNull public String key;
    @NonNull public String value;
    @NonNull public String updatedAtUtc;
    public AppMetaEntity(@NonNull String key, @NonNull String value, @NonNull String updatedAtUtc) {
        this.key = key; this.value = value; this.updatedAtUtc = updatedAtUtc;
    }
}
