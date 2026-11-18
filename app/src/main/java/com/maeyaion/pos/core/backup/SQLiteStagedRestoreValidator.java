package com.maeyaion.pos.core.backup;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.io.IOException;

/** Validates a decrypted restore candidate without opening or mutating the live database. */
public final class SQLiteStagedRestoreValidator implements EncryptedBackupFileStore.StagedRestoreValidator {
    private final int expectedSchemaVersion;

    public SQLiteStagedRestoreValidator(int expectedSchemaVersion) {
        if (expectedSchemaVersion < 1) throw new IllegalArgumentException("Invalid schema version");
        this.expectedSchemaVersion = expectedSchemaVersion;
    }

    @Override
    public boolean isValid(File stagedDatabase) throws IOException {
        if (stagedDatabase == null || !stagedDatabase.isFile() || stagedDatabase.length() <= 0) {
            return false;
        }
        final SQLiteDatabase database;
        try {
            database = SQLiteDatabase.openDatabase(
                    stagedDatabase.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (android.database.sqlite.SQLiteException invalidDatabase) {
            return false;
        }
        try {
            try (Cursor integrity = database.rawQuery("PRAGMA integrity_check", null)) {
                if (!integrity.moveToFirst() || !"ok".equalsIgnoreCase(integrity.getString(0))) {
                    return false;
                }
            }
            try (Cursor version = database.rawQuery("PRAGMA user_version", null)) {
                if (!version.moveToFirst() || version.getInt(0) != expectedSchemaVersion) return false;
            }
            try (Cursor foreignKeys = database.rawQuery("PRAGMA foreign_key_check", null)) {
                if (foreignKeys.moveToFirst()) return false;
            }
            return hasRequiredTable(database, "room_master_table")
                    && hasRequiredTable(database, "app_meta")
                    && hasRequiredTable(database, "backup_journal")
                    && hasRequiredTable(database, "product")
                    && hasRequiredTable(database, "barcode_registry")
                    && hasRequiredTable(database, "stock_log");
        } finally {
            database.close();
        }
    }

    private static boolean hasRequiredTable(SQLiteDatabase database, String table) {
        try (Cursor cursor = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{table})) {
            return cursor.moveToFirst();
        }
    }
}
