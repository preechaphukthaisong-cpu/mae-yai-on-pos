package com.maeyaion.pos.core.backup;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class GoogleDriveBackupUploadWorkerTest {
    @Test
    public void extractsDriveFileIdFromSuccessfulResponse() throws Exception {
        assertEquals("drive-file-123",
                GoogleDriveBackupUploadWorker.extractDriveFileId(
                        "{\"id\": \"drive-file-123\", \"name\": \"backup.myobak\"}"));
    }

    @Test
    public void rejectsSuccessfulHttpResponseWithoutDriveFileId() throws Exception {
        assertMissingId("{}");
        assertMissingId("{\"name\": \"backup.myobak\"}");
        assertMissingId("{\"id\": \"\"}");
    }

    @Test
    public void rejectsNullOrOversizedResponse() throws Exception {
        assertMissingId(null);
        assertMissingId(new String(new char[32 * 1024 + 1]));
    }

    private static void assertMissingId(String response) throws Exception {
        try {
            GoogleDriveBackupUploadWorker.extractDriveFileId(response);
            fail("Expected malformed Drive response to be rejected");
        } catch (IOException expected) {
            // A retry can reconcile an upload whose server response was incomplete.
        }
    }
}
