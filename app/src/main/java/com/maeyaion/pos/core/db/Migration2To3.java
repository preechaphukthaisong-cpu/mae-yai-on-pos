package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds the ordered, idempotency-keyed backup journal without changing existing records. */
public final class Migration2To3 {
    private Migration2To3() {}

    public static final Migration INSTANCE = new Migration(2, 3) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS backup_journal (seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, operation_id TEXT NOT NULL, op TEXT NOT NULL, payload TEXT NOT NULL, payload_sha256 TEXT NOT NULL, schema_version INTEGER NOT NULL, created_at_utc TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_backup_journal_operation_id ON backup_journal(operation_id)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_journal_uploaded_seq ON backup_journal(uploaded, seq)");
            db.execSQL("CREATE TRIGGER backup_journal_update_guard BEFORE UPDATE ON backup_journal WHEN OLD.seq != NEW.seq OR OLD.operation_id != NEW.operation_id OR OLD.op != NEW.op OR OLD.payload != NEW.payload OR OLD.payload_sha256 != NEW.payload_sha256 OR OLD.schema_version != NEW.schema_version OR OLD.created_at_utc != NEW.created_at_utc OR OLD.uploaded != 0 OR NEW.uploaded != 1 BEGIN SELECT RAISE(ABORT, 'backup_journal_immutable'); END");
            db.execSQL("CREATE TRIGGER backup_journal_no_delete BEFORE DELETE ON backup_journal BEGIN SELECT RAISE(ABORT, 'backup_journal_immutable'); END");
        }
    };
}
