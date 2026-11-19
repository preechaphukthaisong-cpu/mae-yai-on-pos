package com.maeyaion.pos.core.backup;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class DriveUploadReconciliationTest {
    @Test public void matchesOnlyExactDeterministicFilename() throws Exception {
        assertEquals("right-id", DriveUploadReconciliation.findMatchingFileId(
                "{\\"files\\":[{\\"id\\":\\"wrong-id\\",\\"name\\":\\"other.myobak\\"},{\\"id\\":\\"right-id\\",\\"name\\":\\"backup-123.myobak\\"}]}",
                "backup-123.myobak"));
    }

    @Test public void returnsNullWhenNoExactNameExists() throws Exception {
        assertNull(DriveUploadReconciliation.findMatchingFileId(
                "{\\"files\\":[{\\"id\\":\\"other-id\\",\\"name\\":\\"other.myobak\\"}]}",
                "backup-123.myobak"));
    }

    @Test public void rejectsDuplicateExactNamesAsAmbiguous() throws Exception {
        assertInvalid("{\\"files\\":[{\\"id\\":\\"one\\",\\"name\\":\\"backup.myobak\\"},{\\"id\\":\\"two\\",\\"name\\":\\"backup.myobak\\"}]}",
                "backup.myobak");
    }

    @Test public void rejectsMalformedOrOversizedLookupResponse() throws Exception {
        assertInvalid("{}", "backup.myobak");
        assertInvalid(new String(new char[32 * 1024 + 1]), "backup.myobak");
    }

    private static void assertInvalid(String response, String name) throws Exception {
        try {
            DriveUploadReconciliation.findMatchingFileId(response, name);
            fail("Expected ambiguous or malformed response to be rejected");
        } catch (IOException expected) {
            // A failed lookup must not be treated as proof that no remote object exists.
        }
    }
}
