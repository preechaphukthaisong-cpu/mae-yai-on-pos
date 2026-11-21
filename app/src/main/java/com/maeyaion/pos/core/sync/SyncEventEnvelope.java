package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Versioned, immutable wire envelope for one domain event.
 *
 * The payload is wrapped as {schemaVersion, data}; this keeps event schema
 * evolution explicit while remaining compatible with the current server
 * envelope. Callers must create and persist the envelope in the same Room
 * transaction as the business mutation before attempting network delivery.
 */
public final class SyncEventEnvelope {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024;

    @NonNull public final String eventId;
    @NonNull public final String operation;
    @NonNull public final String entityType;
    @NonNull public final String entityId;
    public final long baseVersion;
    @NonNull public final JSONObject payload;
    @NonNull public final String payloadSha256;

    private SyncEventEnvelope(String eventId, String operation, String entityType,
            String entityId, long baseVersion, JSONObject payload, String payloadSha256) {
        this.eventId = eventId;
        this.operation = operation;
        this.entityType = entityType;
        this.entityId = entityId;
        this.baseVersion = baseVersion;
        this.payload = payload;
        this.payloadSha256 = payloadSha256;
    }

    @NonNull
    public static SyncEventEnvelope create(@NonNull String operation,
            @NonNull String entityType, @NonNull String entityId, long baseVersion,
            @NonNull JSONObject domainData) throws SyncException {
        if (!"upsert".equals(operation) && !"delete".equals(operation)) {
            throw new SyncException("Unsupported sync event operation");
        }
        if (entityType == null || !entityType.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new SyncException("Invalid sync entity type");
        }
        if (entityId == null || entityId.trim().isEmpty() || entityId.length() > 160) {
            throw new SyncException("Invalid sync entity ID");
        }
        if (baseVersion < 0) throw new SyncException("Invalid sync base version");
        if (domainData == null) throw new SyncException("Sync event data is required");

        JSONObject wrapped = new JSONObject();
        try {
            wrapped.put("schemaVersion", CURRENT_SCHEMA_VERSION);
            wrapped.put("data", domainData);
        } catch (JSONException error) {
            throw new SyncException("Unable to encode versioned sync event", error);
        }

        String canonical = canonicalJson(wrapped);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) {
            throw new SyncException("Sync event payload exceeds 256 KiB");
        }
        String eventId = UUID.randomUUID().toString();
        return new SyncEventEnvelope(eventId, operation, entityType, entityId,
                baseVersion, wrapped, sha256Hex(bytes));
    }

    /** Revalidates the immutable content before it is placed in a network batch. */
    @NonNull
    public JSONObject toJsonObject() throws SyncException {
        String canonical = canonicalJson(payload);
        if (!payloadSha256.equals(sha256Hex(canonical.getBytes(StandardCharsets.UTF_8)))) {
            throw new SyncException("Sync event payload checksum mismatch");
        }
        JSONObject result = new JSONObject();
        try {
            result.put("eventId", eventId);
            result.put("operation", operation);
            result.put("entityType", entityType);
            result.put("entityId", entityId);
            result.put("baseVersion", baseVersion);
            result.put("payload", payload);
            result.put("payloadSha256", payloadSha256);
            return result;
        } catch (JSONException error) {
            throw new SyncException("Unable to serialize sync event", error);
        }
    }

    /** Matches backend canonicalJson: recursively sort object keys; preserve array order. */
    @NonNull
    static String canonicalJson(@NonNull Object value) throws SyncException {
        if (value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(',');
                String key = keys.get(i);
                out.append(quoteJsonString(key)).append(':')
                        .append(canonicalJson(object.opt(key)));
            }
            return out.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(',');
                out.append(canonicalJson(array.opt(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof String) return quoteJsonString((String) value);
        if (value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Short
                || value instanceof Byte) return String.valueOf(value);
        throw new SyncException("Sync payload supports only JSON strings, booleans, integers, arrays, objects, and null");
    }

    /** JSON string escaping independent of Android's mocked org.json implementation. */
    @NonNull
    private static String quoteJsonString(@NonNull String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\\\': out.append("\\\\\\\\"); break;
                case '\\b': out.append("\\\\b"); break;
                case '\\f': out.append("\\\\f"); break;
                case '\\n': out.append("\\\\n"); break;
                case '\\r': out.append("\\\\r"); break;
                case '\\t': out.append("\\\\t"); break;
                default:
                    if (ch < 0x20 || (Character.isSurrogate(ch)
                            && !(Character.isHighSurrogate(ch) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1)))
                            && !(Character.isLowSurrogate(ch) && i > 0
                            && Character.isHighSurrogate(value.charAt(i - 1))))) {
                        out.append(String.format(Locale.ROOT, "\\\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
            }
        }
        return out.append('"').toString();
    }

    private static String sha256Hex(byte[] bytes) throws SyncException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new SyncException("SHA-256 is unavailable", error);
        }
    }
}
