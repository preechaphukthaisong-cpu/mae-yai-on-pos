package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.AppMetaEntity;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class RoomDatabaseRestorerTest {
    private Context context;
    private AppDatabase database;
    private File workDirectory;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppDatabase.closeInstanceForRestore();
        context.deleteDatabase("maeyaion_pos.db");
        database = AppDatabase.getInstance(context);
        database.getOpenHelper().getWritableDatabase();
        workDirectory = new File(context.getFilesDir(), "restore-activation-" + UUID.randomUUID());
        assertTrue(workDirectory.mkdirs());
    }

    @After public void tearDown() {
        AppDatabase.closeInstanceForRestore();
        context.deleteDatabase("maeyaion_pos.db");
        deleteRecursively(workDirectory);
    }

    @Test public void activationReplacesLiveDatabaseOnlyAfterSafetySnapshotAndValidation()
            throws Exception {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        transactions.runInTransaction(() -> {
            database.appMetaDao().put(new AppMetaEntity(
                    "restore_probe", "before-restore", "2026-11-18T10:00:00Z"));
            return null;
        });
        RoomDatabaseSnapshotter.Snapshot safety = new RoomDatabaseSnapshotter()
                .create(context, database, workDirectory);
        File staged = new File(workDirectory, "candidate-" + UUID.randomUUID() + ".db");
        Files.copy(safety.file.toPath(), staged.toPath());

        transactions.runInTransaction(() -> {
            database.appMetaDao().put(new AppMetaEntity(
                    "restore_probe", "after-restore", "2026-11-18T10:01:00Z"));
            return null;
        });
        assertEquals("after-restore", database.appMetaDao().findValue("restore_probe"));

        new RoomDatabaseRestorer().activate(context, staged, safety.file, 4);
        database = AppDatabase.getInstance(context);
        assertEquals("before-restore", database.appMetaDao().findValue("restore_probe"));
        assertEquals(4, database.getOpenHelper().getWritableDatabase().getVersion());
    }

    @Test public void invalidRestoreCandidateDoesNotCloseOrModifyLiveDatabase() throws Exception {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        transactions.runInTransaction(() -> {
            database.appMetaDao().put(new AppMetaEntity(
                    "restore_probe", "must-remain", "2026-11-18T10:00:00Z"));
            return null;
        });
        RoomDatabaseSnapshotter.Snapshot safety = new RoomDatabaseSnapshotter()
                .create(context, database, workDirectory);
        File invalid = new File(workDirectory, "invalid-" + UUID.randomUUID() + ".db");
        Files.write(invalid.toPath(), "not sqlite".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try {
            new RoomDatabaseRestorer().activate(context, invalid, safety.file, 4);
            fail("Invalid candidate must be rejected");
        } catch (java.io.IOException expected) {
            // Validation occurs before closing the live Room database.
        }
        assertEquals("must-remain", database.appMetaDao().findValue("restore_probe"));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
