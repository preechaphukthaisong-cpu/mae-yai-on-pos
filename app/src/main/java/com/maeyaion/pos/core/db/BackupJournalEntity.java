package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Append-only backup journal entry; only uploaded may transition from 0 to 1. */
@Entity(tableName = "backup_journal",
        indices = {@Index(value = {"uploaded", "seq"}), @Index(value = "operation_id", unique = true)})
public final class BackupJournalEntity {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "seq")
    public final long seq;

    @NonNull @ColumnInfo(name = "operation_id")
    public final String operation_id;

    @NonNull public final String op;
    @NonNull public final String payload;
    @NonNull @ColumnInfo(name = "payload_sha256")
    public final String payload_sha256;
    @ColumnInfo(name = "schema_version")
    public final int schema_version;
    @NonNull @ColumnInfo(name = "created_at_utc")
    public final String created_at_utc;
    @ColumnInfo(name = "uploaded", defaultValue = "0")
    public final int uploaded;

    public BackupJournalEntity(long seq, @NonNull String operation_id, @NonNull String op,
            @NonNull String payload, @NonNull String payload_sha256, int schema_version,
            @NonNull String created_at_utc, int uploaded) {
        this.seq = seq;
        this.operation_id = operation_id;
        this.op = op;
        this.payload = payload;
        this.payload_sha256 = payload_sha256;
        this.schema_version = schema_version;
        this.created_at_utc = created_at_utc;
        this.uploaded = uploaded;
    }
}
