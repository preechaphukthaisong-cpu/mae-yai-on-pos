package th.co.maeyai.onpos;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Verified, single-file SQLite backup/restore. Never restore an unverified database. */
public final class DatabaseBackup {
    private DatabaseBackup() {}

    public static void exportTo(Context context, Uri destination) throws Exception {
        File snapshot = new File(context.getCacheDir(), "pos-backup-" + UUID.randomUUID() + ".db");
        File liveDb = context.getDatabasePath("mae_yai_on_pos.db");
        try {
            SQLiteDatabase db = PosDatabase.get(context).getWritableDatabase();
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
                if (c.moveToFirst() && c.getInt(0) != 0) throw new IllegalStateException("WAL checkpoint busy");
            }
            // Closing the helper checkpoints and closes WAL before copying the main file.
            PosDatabase.closeForRestore();
            copy(liveDb, snapshot);
            verify(snapshot, true);
            try (InputStream in = new FileInputStream(snapshot);
                 OutputStream out = context.getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new IllegalStateException("Cannot open backup destination");
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                out.flush();
            }
        } finally {
            PosDatabase.get(context).getReadableDatabase();
            snapshot.delete();
        }
    }

    public static void restoreFrom(Context context, Uri source) throws Exception {
        File incoming = new File(context.getCacheDir(), "pos-restore-" + UUID.randomUUID() + ".db");
        File dbFile = context.getDatabasePath("mae_yai_on_pos.db");
        File staged = new File(dbFile.getParentFile(), dbFile.getName() + ".restore");
        File previous = new File(dbFile.getParentFile(), dbFile.getName() + ".previous");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(incoming)) {
                if (in == null) throw new IllegalStateException("Cannot open backup file");
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                out.flush();
            }
            verify(incoming, true);
            copy(incoming, staged);
            PosDatabase.closeForRestore();
            deleteSidecars(dbFile);
            if (previous.exists() && !previous.delete()) throw new IllegalStateException("Cannot clear previous database");
            if (dbFile.exists() && !dbFile.renameTo(previous)) throw new IllegalStateException("Cannot stage current database");
            if (!staged.renameTo(dbFile)) {
                if (previous.exists()) previous.renameTo(dbFile);
                throw new IllegalStateException("Cannot install restored database");
            }
            try {
                verify(dbFile, false);
                PosDatabase.get(context).getReadableDatabase();
                if (previous.exists()) previous.delete();
            } catch (Exception failure) {
                PosDatabase.closeForRestore();
                dbFile.delete();
                if (previous.exists()) previous.renameTo(dbFile);
                throw failure;
            }
        } finally {
            incoming.delete();
            staged.delete();
        }
    }

    private static void verify(File file, boolean requireCurrentVersion) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor c = db.rawQuery("PRAGMA integrity_check", null)) {
                if (!c.moveToFirst() || !"ok".equalsIgnoreCase(c.getString(0)))
                    throw new IllegalArgumentException("Database integrity check failed");
            }
            try (Cursor c = db.rawQuery("PRAGMA foreign_key_check", null)) {
                if (c.moveToFirst()) throw new IllegalArgumentException("Database foreign key check failed");
            }
            if (!tableExists(db, "products") || !tableExists(db, "sales") ||
                !tableExists(db, "schema_migrations") || !tableExists(db, "backup_log"))
                throw new IllegalArgumentException("Not a supported POS database");
            if (requireCurrentVersion && db.getVersion() != 2)
                throw new IllegalArgumentException("Unsupported backup database version");
        } finally {
            db.close();
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) {
            return c.moveToFirst();
        }
    }

    private static void copy(File source, File destination) throws Exception {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.getFD().sync();
        }
    }

    private static void deleteSidecars(File db) {
        new File(db.getAbsolutePath() + "-wal").delete();
        new File(db.getAbsolutePath() + "-shm").delete();
        new File(db.getAbsolutePath() + "-journal").delete();
    }
}
