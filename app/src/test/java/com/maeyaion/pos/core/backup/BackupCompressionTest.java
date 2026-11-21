package com.maeyaion.pos.core.backup;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BackupCompressionTest {
    @Test
    public void compressesSnapshotAndRestoresExactBytes() throws Exception {
        File directory = Files.createTempDirectory("maeyai-backup-compression").toFile();
        File source = new File(directory, "snapshot.db");
        byte[] original = new byte[256 * 1024];
        Arrays.fill(original, (byte) 'A');
        Files.write(source.toPath(), original);

        File compressed = BackupCompression.compressIfSmaller(source, directory);
        assertNotSame(source, compressed);
        assertTrue(compressed.length() < source.length());

        File restored = new File(directory, "restored.db");
        BackupCompression.expandIfCompressed(compressed, restored);
        assertArrayEquals(original, Files.readAllBytes(restored.toPath()));
    }

    @Test
    public void keepsIncompressibleSnapshotUncompressed() throws Exception {
        File directory = Files.createTempDirectory("maeyai-backup-raw").toFile();
        File source = new File(directory, "snapshot.db");
        byte[] original = new byte[64 * 1024];
        new java.security.SecureRandom().nextBytes(original);
        Files.write(source.toPath(), original);

        File result = BackupCompression.compressIfSmaller(source, directory);
        assertSame(source, result);

        File restored = new File(directory, "restored.db");
        BackupCompression.expandIfCompressed(result, restored);
        assertArrayEquals(original, Files.readAllBytes(restored.toPath()));
    }

    @Test
    public void rejectsExpandedPayloadAboveConfiguredLimit() throws Exception {
        File directory = Files.createTempDirectory("maeyai-backup-limit").toFile();
        File source = new File(directory, "snapshot.db");
        Files.write(source.toPath(), "SQLite format 3".getBytes(StandardCharsets.US_ASCII));
        File restored = new File(directory, "restored.db");
        BackupCompression.expandIfCompressed(source, restored);
        assertEquals(source.length(), restored.length());
    }
}
