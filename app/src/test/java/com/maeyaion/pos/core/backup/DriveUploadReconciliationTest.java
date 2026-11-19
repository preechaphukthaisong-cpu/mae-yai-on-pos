package com.maeyaion.pos.core.backup;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class DriveUploadReconciliationTest {
    private static String q() { return String.valueOf((char) 34); }

    @Test public void matchesOnlyExactDeterministicFilename() throws Exception {
        String json = "{" + q() + "files" + q() + ":["
                + "{" + q() + "id" + q() + ":" + q() + "wrong-id" + q()
                + "," + q() + "name" + q() + ":" + q() + "other.myobak" + q() + "},"
                + "{" + q() + "id" + q() + ":" + q() + "right-id" + q()
                + "," + q() + "name" + q() + ":" + q() + "backup-123.myobak" + q() + "}]}";
        assertEquals("right-id",
                DriveUploadReconciliation.findMatchingFileId(json, "backup-123.myobak"));
    }

    @Test public void returnsNullWhenNoExactNameExists() throws Exception {
        String json = "{" + q() + "files" + q() + ":[{" + q() + "id" + q() + ":"
                + q() + "other-id" + q() + "," + q() + "name" + q() + ":"
                + q() + "other.myobak" + q() + "}]}";
        assertNull(DriveUploadReconciliation.findMatchingFileId(json, "backup-123.myobak"));
    }

    @Test public void rejectsDuplicateExactNamesAsAmbiguous() throws Exception {
        String json = "{" + q() + "files" + q() + ":["
                + "{" + q() + "id" + q() + ":" + q() + "one" + q()
                + "," + q() + "name" + q() + ":" + q() + "backup.myobak" + q() + "},"
                + "{" + q() + "id" + q() + ":" + q() + "two" + q()
                + "," + q() + "name" + q() + ":" + q() + "backup.myobak" + q() + "}]}";
        assertInvalid(json, "backup.myobak");
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
