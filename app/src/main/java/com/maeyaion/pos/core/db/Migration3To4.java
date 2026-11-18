package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Refreshes database invariants for installations that already run schema version 3. */
public final class Migration3To4 {
    private Migration3To4() {}

    public static final Migration INSTANCE = new Migration(3, 4) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            DatabaseGuards.installCore(db);
            DatabaseGuards.installBackupJournal(db);
        }
    };
}
