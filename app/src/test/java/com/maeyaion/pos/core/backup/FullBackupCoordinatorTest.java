package com.maeyaion.pos.core.backup;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class FullBackupCoordinatorTest {
    @Test
    public void cleanupDeletesPlaintextSnapshotAfterSuccessfulBackup() throws Exception {
        File snapshot = File.createTempFile("myobak-plain-", ".sqlite");
        try {
            FullBackupCoordinator.cleanupSnapshot(snapshot, null);
            assertFalse(snapshot.exists());
        } finally {
            if (snapshot.exists()) snapshot.delete();
        }
    }

    @Test
    public void cleanupFailureDoesNotMaskOriginalBackupFailure() throws Exception {
        File nonEmptyDirectory = createNonEmptyDirectory();
        IOException original = new IOException("encryption failed");
        try {
            FullBackupCoordinator.cleanupSnapshot(nonEmptyDirectory, original);
            assertEquals(1, original.getSuppressed().length);
            assertNotNull(original.getSuppressed()[0]);
            assertEquals("Encrypted backup created, but plaintext snapshot cleanup failed",
                    original.getSuppressed()[0].getMessage());
        } finally {
            File child = new File(nonEmptyDirectory, "keep");
            child.delete();
            nonEmptyDirectory.delete();
        }
    }

    @Test
    public void cleanupFailureIsReportedWhenBackupOtherwiseSucceeded() throws Exception {
        File nonEmptyDirectory = createNonEmptyDirectory();
        try {
            FullBackupCoordinator.cleanupSnapshot(nonEmptyDirectory, null);
            fail("Expected plaintext cleanup failure");
        } catch (IOException expected) {
            assertEquals("Encrypted backup created, but plaintext snapshot cleanup failed",
                    expected.getMessage());
        } finally {
            new File(nonEmptyDirectory, "keep").delete();
            nonEmptyDirectory.delete();
        }
    }

    private static File createNonEmptyDirectory() throws Exception {
        File directory = new File(System.getProperty("java.io.tmpdir"),
                "myobak-cleanup-" + System.nanoTime());
        if (!directory.mkdir()) throw new IOException("Unable to create test directory");
        try (FileOutputStream output = new FileOutputStream(new File(directory, "keep"))) {
            output.write(1);
        }
        return directory;
    }
}
