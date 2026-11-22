package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Canonical authenticated metadata for an encrypted sync event.
 *
 * The exact field set and canonical key ordering are protocol data. Do not add
 * optional fields without incrementing the AAD protocol version and updating
 * cross-platform test vectors.
 */
public final class SyncAssociatedData {
    public static final int VERSION = 1;
    private static final int MAX_BYTES = 4096;

    private SyncAssociatedData() {}

    @NonNull
    public static byte[] encode(@NonNull String storeId, @NonNull String eventId,
            @NonNull String operation, @NonNull String entityType, @NonNull String entityId,
            long baseVersion, int encryptionVersion, long keyEpoch) throws SyncException {
        requireText(storeId, "store ID", 160);
        requireText(eventId, "event ID", 64);
        requireText(entityType, "entity type", 64);
        requireText(entityId, "entity ID", 160);
        if (!"upsert".equals(operation) && !"delete".equals(operation)) {
            throw new SyncException("Invalid associated-data operation");
        }
        if (baseVersion < 0 || encryptionVersion < 1 || keyEpoch < 1) {
            throw new SyncException("Invalid associated-data version metadata");
        }

        JSONObject value = new JSONObject();
        try {
            // SyncEventEnvelope.canonicalJson sorts keys recursively.
            value.put("aadVersion", VERSION);
            value.put("baseVersion", baseVersion);
            value.put("encryptionVersion", encryptionVersion);
            value.put("entityId", entityId);
            value.put("entityType", entityType);
            value.put("eventId", eventId);
            value.put("keyEpoch", keyEpoch);
            value.put("operation", operation);
            value.put("storeId", storeId);
            byte[] encoded = SyncEventEnvelope.canonicalJson(value)
                    .getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_BYTES) {
                throw new SyncException("Associated data exceeds the protocol size limit");
            }
            return encoded;
        } catch (JSONException error) {
            throw new SyncException("Unable to encode sync associated data", error);
        }
    }

    private static void requireText(String value, String label, int maxLength)
            throws SyncException {
        if (value == null || value.trim().isEmpty() || value.length() > maxLength) {
            throw new SyncException("Invalid associated-data " + label);
        }
    }
}
