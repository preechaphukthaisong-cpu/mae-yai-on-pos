package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "customer",
        indices = @Index(value = {"name_key", "identity_key"}, unique = true))
public final class CustomerEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String name;
    @NonNull public final String name_key;
    @NonNull public final String identity_key;
    @NonNull public final String created_at_utc;
    @NonNull public final String updated_at_utc;

    public CustomerEntity(@NonNull String id, @NonNull String name, @NonNull String name_key,
            @NonNull String identity_key, @NonNull String created_at_utc,
            @NonNull String updated_at_utc) {
        this.id = id; this.name = name; this.name_key = name_key;
        this.identity_key = identity_key; this.created_at_utc = created_at_utc;
        this.updated_at_utc = updated_at_utc;
    }
}