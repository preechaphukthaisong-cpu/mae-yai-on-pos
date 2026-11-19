package com.maeyaion.pos.core.backup;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GoogleDriveBackupDownloadWorkerTest {
    @Test
    public void acceptsDriveFileIdsWithSupportedCharacters() {
        assertTrue(GoogleDriveBackupDownloadWorker.isValidDriveFileId("1AbCdEfGhIjKlMnOp_123-456"));
    }

    @Test
    public void rejectsMissingShortOrMalformedDriveFileIds() {
        assertFalse(GoogleDriveBackupDownloadWorker.isValidDriveFileId(null));
        assertFalse(GoogleDriveBackupDownloadWorker.isValidDriveFileId(""));
        assertFalse(GoogleDriveBackupDownloadWorker.isValidDriveFileId("short"));
        assertFalse(GoogleDriveBackupDownloadWorker.isValidDriveFileId("../private.db"));
        assertFalse(GoogleDriveBackupDownloadWorker.isValidDriveFileId("id with spaces 123"));
        assertFalse(GoogleDriveBackupDownloadWorker.isValidDriveFileId(new String(new char[257]).replace('\0', 'a')));
    }
}
