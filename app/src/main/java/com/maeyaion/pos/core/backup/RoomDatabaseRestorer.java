package com.maeyaion.pos.core.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.tx.DatabaseMaintenanceGate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Activates a previously validated restore candidate with a local rollback copy.
 * The safety snapshot must be created and validated before this operation.
 */
public final class RoomDatabaseRestorer {
    private static final String DATABASE_NAME = "maeyaion_pos.db";

    public void activate(@NonNull Context context, @NonNull File stagedDatabase,
                         @NonNull File safetySnapshot, int expectedSchemaVersion)
            throws IOException {
        SQLiteStagedRestoreValidator validator =
                new SQLiteStagedRestoreValidator(expectedSchemaVersion);
        if (!validator.isValid(stagedDatabase)) {
            throw new IOException("Restore candidate failed validation");
        }
        if (!validator.isValid(safetySnapshot)) {
            throw new IOException("Pre-restore safety snapshot is missing or invalid");
        }

        try {
            DatabaseMaintenanceGate.withExclusiveAccess(() -> {
                synchronized (AppDatabase.class) {
                    AppDatabase.closeInstanceForRestore();
                    File live = context.getDatabasePath(DATABASE_NAME);
                    File parent = live.getParentFile();
                    if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                        throw new IOException("Database directory is unavailable");
                    }
                    File replacement = new File(parent, "." + UUID.randomUUID() + ".restore.tmp");
                    File rollback = new File(parent, "." + UUID.randomUUID() + ".rollback.db");
                    boolean oldDatabaseMoved = false;
                    try {
                        copyAndSync(stagedDatabase, replacement);
                        deleteSidecars(live);
                        if (live.exists()) {
                            if (!live.renameTo(rollback)) {
                                throw new IOException("Unable to preserve current database for rollback");
                            }
                            oldDatabaseMoved = true;
                        }
                        if (!replacement.renameTo(live)) {
                            throw new IOException("Unable to atomically activate staged database");
                        }

                        try {
                            AppDatabase restored = AppDatabase.getInstance(context);
                            SupportSQLiteDatabase sql = restored.getOpenHelper().getWritableDatabase();
                            if (sql.getVersion() != expectedSchemaVersion || !validator.isValid(live)) {
                                throw new IOException("Activated database failed post-open validation");
                            }
                            if (oldDatabaseMoved && rollback.exists() && !rollback.delete()) {
                                throw new IOException("Restored database is active but rollback file cleanup failed");
                            }
                            return null;
                        } catch (Exception activationFailure) {
                            AppDatabase.closeInstanceForRestore();
                            deleteSidecars(live);
                            if (live.exists() && !live.delete()) {
                                throw new IOException("Failed restore is active and cannot be removed", activationFailure);
                            }
                            if (oldDatabaseMoved && rollback.exists() && !rollback.renameTo(live)) {
                                throw new IOException("Restore failed and previous database rollback failed", activationFailure);
                            }
                            // Reopen the previous database before returning control to the app.
                            AppDatabase.getInstance(context).getOpenHelper().getWritableDatabase();
                            if (activationFailure instanceof IOException) throw (IOException) activationFailure;
                            throw new IOException("Database activation failed; previous database restored",
                                    activationFailure);
                        }
                    } finally {
                        if (replacement.exists()) replacement.delete();
                        if (rollback.exists() && !oldDatabaseMoved) rollback.delete();
                    }
                }
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to activate restored Room database", e);
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

    private static void deleteSidecars(File database) throws IOException {
        for (String suffix : new String[]{"-wal", "-shm", "-journal"}) {
            File sidecar = new File(database.getPath() + suffix);
            if (sidecar.exists() && !sidecar.delete()) {
                throw new IOException("Unable to remove SQLite sidecar: " + suffix);
            }
        }
    }
}
