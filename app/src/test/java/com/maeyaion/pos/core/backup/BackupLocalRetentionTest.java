package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

public final class BackupLocalRetentionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void removesOnlyExpiredConfirmedBackupsAndKeepsNewestConfirmed() throws Exception {
        File directory = temporary.newFolder("backups");
        long now = 2_000_000_000_000L;
        File expired = backup(directory, "old.myobak", now - 20L * 24 * 60 * 60 * 1000);
        marker(expired, ".uploaded");
        File latest = backup(directory, "latest.myobak", now - 15L * 24 * 60 * 60 * 1000);
        marker(latest, ".uploaded");
        File pending = backup(directory, "pending.myobak", now - 30L * 24 * 60 * 60 * 1000);
        marker(pending, ".pending");
        File ambiguous = backup(directory, "ambiguous.myobak", now - 30L * 24 * 60 * 60 * 1000);

        BackupLocalRetention.prune(directory, now);

        assertFalse(expired.exists());
        assertFalse(new File(expired.getAbsolutePath() + ".uploaded").exists());
        assertTrue(latest.exists());
        assertTrue(pending.exists());
        assertTrue(new File(pending.getAbsolutePath() + ".pending").exists());
        assertTrue(ambiguous.exists());
    }

    @Test public void keepsRecentConfirmedBackup() throws Exception {
        File directory = temporary.newFolder("recent");
        File recent = backup(directory, "recent.myobak", 1_999_999_000_000L);
        marker(recent, ".uploaded");
        BackupLocalRetention.prune(directory, 2_000_000_000_000L);
        assertTrue(recent.exists());
    }

    private static File backup(File directory, String name, long modified) throws Exception {
        File file = new File(directory, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3});
        }
        if (!file.setLastModified(modified)) throw new IllegalStateException("Unable to set test timestamp");
        return file;
    }

    private static void marker(File backup, String suffix) throws Exception {
        try (FileOutputStream output = new FileOutputStream(
                new File(backup.getAbsolutePath() + suffix))) {
            output.write(1);
        }
    }
}
