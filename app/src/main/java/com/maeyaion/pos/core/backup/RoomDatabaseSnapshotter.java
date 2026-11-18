package com.maeyaion.pos.core.backup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.maeyaion.pos.core.tx.DatabaseMaintenanceGate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Produces a consistent standalone SQLite snapshot from a Room WAL database.
 * All application writes must go through RoomTransactionRunner so the shared
 * maintenance gate can exclude writers during checkpoint and file copy.
 */
public final class RoomDatabaseSnapshotter {
    private static final int MAX_DATABASE_BYTES = 512 * 1024 * 1024;

    public Snapshot create(@NonNull Context context, @NonNull RoomDatabase roomDatabase,
                           @NonNull File privateSnapshotDirectory)
            throws IOException, GeneralSecurityException {
        if (!privateSnapshotDirectory.isDirectory() && !privateSnapshotDirectory.mkdirs()) {
            throw new IOException("Cannot create private snapshot directory");
        }
        if (!privateSnapshotDirectory.canWrite()) {
            throw new IOException("Snapshot directory is not writable");
        }

        try {
            return DatabaseMaintenanceGate.withExclusiveAccess(() -> {
                SupportSQLiteDatabase database = roomDatabase.getOpenHelper().getWritableDatabase();
                checkpointWal(database);
                String databaseName = roomDatabase.getOpenHelper().getDatabaseName();
                if (databaseName == null) throw new IOException("In-memory databases cannot be snapshotted");
                File source = context.getDatabasePath(databaseName);
                if (!source.isFile() || source.length() <= 0 || source.length() > MAX_DATABASE_BYTES) {
                    throw new IOException("Live database is missing, empty, or exceeds snapshot limit");
                }

                String id = UUID.randomUUID().toString();
                File temporary = new File(privateSnapshotDirectory, "." + id + ".sqlite.tmp");
                File destination = new File(privateSnapshotDirectory, id + ".sqlite");
                try {
                    copyAndSync(source, temporary);
                    validateSnapshot(temporary, database.getVersion());
                    if (!temporary.renameTo(destination)) {
                        throw new IOException("Atomic snapshot publication failed");
                    }
                    return new Snapshot(destination, sha256(destination), destination.length(),
                            database.getVersion());
                } finally {
                    if (temporary.exists() && !temporary.delete()) {
                        // Incomplete snapshots are never published as completed files.
                    }
                }
            });
        } catch (IOException | GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to create consistent Room snapshot", e);
        }
    }

    private static void checkpointWal(SupportSQLiteDatabase database) throws IOException {
        try (Cursor cursor = database.query("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (!cursor.moveToFirst() || cursor.getColumnCount() < 3) {
                throw new IOException("SQLite did not return WAL checkpoint status");
            }
            int busy = cursor.getInt(0);
            int logFrames = cursor.getInt(1);
            int checkpointedFrames = cursor.getInt(2);
            if (busy != 0 || (logFrames >= 0 && checkpointedFrames != logFrames)) {
                throw new IOException("WAL checkpoint incomplete; refusing inconsistent snapshot");
            }
        }
    }

    private static void copyAndSync(File source, File destination) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void validateSnapshot(File file, int expectedVersion) throws IOException {
        SQLiteDatabase snapshot = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor integrity = snapshot.rawQuery("PRAGMA integrity_check", null)) {
                if (!integrity.moveToFirst() || !"ok".equalsIgnoreCase(integrity.getString(0))) {
                    throw new IOException("SQLite integrity_check failed for snapshot");
                }
            }
            try (Cursor version = snapshot.rawQuery("PRAGMA user_version", null)) {
                if (!version.moveToFirst() || version.getInt(0) != expectedVersion) {
                    throw new IOException("Snapshot schema version does not match live database");
                }
            }
            try (Cursor foreignKeys = snapshot.rawQuery("PRAGMA foreign_key_check", null)) {
                if (foreignKeys.moveToFirst()) {
                    throw new IOException("Snapshot contains foreign-key violations");
                }
            }
        } finally {
            snapshot.close();
        }
    }

    private static String sha256(File file) throws IOException, GeneralSecurityException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new GeneralSecurityException("SHA-256 is unavailable", e);
        }
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    public static final class Snapshot {
        public final File file;
        public final String sha256;
        public final long sizeBytes;
        public final int schemaVersion;

        Snapshot(File file, String sha256, long sizeBytes, int schemaVersion) {
            this.file = file;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.schemaVersion = schemaVersion;
        }
    }
}
