package com.maeyaion.pos.core.backup;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/** Rolling local cache retention; never removes pending files or the last confirmed copy. */
public final class BackupLocalRetention {
    private static final long RETENTION_MILLIS = 14L * 24L * 60L * 60L * 1000L;

    private BackupLocalRetention() {}

    public static void prune(File directory, long nowMillis) throws IOException {
        if (directory == null || !directory.isDirectory()) return;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".myobak"));
        if (files == null) throw new IOException("Unable to enumerate local backup cache");
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());

        File newestConfirmed = null;
        for (File file : files) {
            if (new File(file.getAbsolutePath() + ".uploaded").isFile()) {
                newestConfirmed = file;
                break;
            }
        }
        for (File file : files) {
            // A file without an uploaded marker is pending or ambiguous: never delete it.
            if (new File(file.getAbsolutePath() + ".pending").exists()) continue;
            if (!new File(file.getAbsolutePath() + ".uploaded").isFile()) continue;
            if (file.equals(newestConfirmed)) continue;
            if (nowMillis - file.lastModified() > RETENTION_MILLIS) {
                deleteIfExists(new File(file.getAbsolutePath() + ".localkey"));
                deleteIfExists(new File(file.getAbsolutePath() + ".uploaded"));
                deleteIfExists(file);
            }
        }
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to remove expired local backup cache entry");
        }
    }
}
