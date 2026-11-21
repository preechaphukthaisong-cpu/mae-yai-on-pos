package com.maeyaion.pos.core.backup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Streaming compression for database snapshots. Legacy uncompressed SQLite
 * backup payloads remain readable. Decompression is bounded to prevent zip bombs.
 */
public final class BackupCompression {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L;
    private static final byte GZIP_MAGIC_0 = (byte) 0x1f;
    private static final byte GZIP_MAGIC_1 = (byte) 0x8b;

    private BackupCompression() {}

    /**
     * Returns a GZIP-compressed temporary file only when it is smaller.
     * The caller owns and must delete the returned file when it differs from source.
     */
    public static File compressIfSmaller(File source, File privateDirectory) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0
                || source.length() > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("Snapshot is missing or outside the supported size");
        }
        File compressed = new File(privateDirectory, "." + UUID.randomUUID() + ".gz.tmp");
        boolean published = false;
        try {
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 OutputStream raw = new BufferedOutputStream(new FileOutputStream(compressed));
                 GZIPOutputStream gzip = new GZIPOutputStream(raw, BUFFER_SIZE)) {
                copyBounded(in, gzip, MAX_UNCOMPRESSED_BYTES);
                gzip.finish();
            }
            if (compressed.length() >= source.length()) {
                return source;
            }
            published = true;
            return compressed;
        } finally {
            if (!published && compressed.exists()) {
                // If source is returned, the unused compressed temporary is discarded.
                //noinspection ResultOfMethodCallIgnored
                compressed.delete();
            }
        }
    }

    /**
     * Expands GZIP payloads or copies legacy raw SQLite payloads into destination.
     * Output is written to a private temporary path by the caller and is not
     * considered valid until this method returns and SQLite validation succeeds.
     */
    public static void expandIfCompressed(File payload, File destination) throws IOException {
        if (payload == null || !payload.isFile() || payload.length() <= 0) {
            throw new IOException("Decrypted backup payload is missing");
        }
        try (InputStream raw = new BufferedInputStream(new FileInputStream(payload))) {
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            if (first == (GZIP_MAGIC_0 & 0xff) && second == (GZIP_MAGIC_1 & 0xff)) {
                try (GZIPInputStream gzip = new GZIPInputStream(raw, BUFFER_SIZE);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
                    copyBounded(gzip, out, MAX_UNCOMPRESSED_BYTES);
                }
            } else {
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
                    copyBounded(raw, out, MAX_UNCOMPRESSED_BYTES);
                }
            }
            try (FileOutputStream sync = new FileOutputStream(destination, true)) {
                sync.getFD().sync();
            }
        } catch (IOException failure) {
            // Never leave a partial decompressed database that could be mistaken for a restore.
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw failure;
        }
        if (!destination.isFile() || destination.length() <= 0) {
            throw new IOException("Expanded backup payload is empty");
        }
    }

    private static void copyBounded(InputStream in, OutputStream out, long maximum) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maximum) throw new IOException("Expanded backup exceeds 512 MiB limit");
            out.write(buffer, 0, read);
        }
        out.flush();
    }
}
