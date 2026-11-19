package com.maeyaion.pos.core.backup;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strictly reconciles Drive list results against the deterministic encrypted-backup filename. */
final class DriveUploadReconciliation {
    private static final int MAX_RESPONSE_CHARS = 32 * 1024;
    private static final Pattern OBJECT = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"\\\\]+)\"");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"\\\\]*)\"");

    private DriveUploadReconciliation() {}

    static String findMatchingFileId(String response, String expectedName) throws IOException {
        return findMatchingFileId(response, expectedName, -1L);
    }

    static String findMatchingFileId(String response, String expectedName, long expectedSize)
            throws IOException {
        if (response == null || response.length() > MAX_RESPONSE_CHARS
                || expectedName == null || expectedName.isEmpty() || expectedSize < -1L) {
            throw new IOException("Drive lookup response or expected filename is invalid");
        }
        int filesKey = response.indexOf("files");
        if (filesKey < 0) throw new IOException("Drive lookup response has no files array");
        int arrayStart = response.indexOf('[', filesKey);
        if (arrayStart < 0) throw new IOException("Drive lookup response has no files array");
        int arrayEnd = response.indexOf(']', arrayStart);
        if (arrayEnd < 0) throw new IOException("Drive lookup files array is incomplete");
        String files = response.substring(arrayStart + 1, arrayEnd);
        Matcher objects = OBJECT.matcher(files);
        String match = null;
        while (objects.find()) {
            String object = objects.group(1);
            Matcher id = ID.matcher(object);
            Matcher name = NAME.matcher(object);
            if (!id.find() || !name.find()) continue;
            if (expectedName.equals(name.group(1))) {
                if (match != null) {
                    throw new IOException("Drive contains multiple objects with the deterministic backup filename");
                }
                if (expectedSize >= 0L) {
                    Matcher size = SIZE.matcher(object);
                    if (!size.find() || Long.parseLong(size.group(1)) != expectedSize) {
                        throw new IOException("Drive object size does not match the local encrypted backup");
                    }
                }
                match = id.group(1);
            }
        }
        return match;
    }
}
