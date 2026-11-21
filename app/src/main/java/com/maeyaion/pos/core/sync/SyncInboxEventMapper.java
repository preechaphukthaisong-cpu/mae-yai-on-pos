package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.SyncInboxEntity;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.UUID;

/** Strict mapper for the backend pull response; does not mutate the database. */
public final class SyncInboxEventMapper {
    private SyncInboxEventMapper() {}

    @NonNull
    public static SyncInboxEntity fromServer(@NonNull String storeId,
            @NonNull JSONObject event, @NonNull String receivedAtUtc) throws SyncException {
        try {
            String eventId = event.getString("eventId");
            if (!UUID.fromString(eventId).toString().equalsIgnoreCase(eventId)) {
                throw new SyncException("Server event ID is not a canonical UUID");
            }
            String operation = event.getString("operation");
            if (!"upsert".equals(operation) && !"delete".equals(operation)) {
                throw new SyncException("Server event operation is invalid");
            }
            String entityType = event.getString("entityType");
            if (!entityType.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new SyncException("Server event entity type is invalid");
            }
            String entityId = event.getString("entityId");
            if (entityId.trim().isEmpty() || entityId.length() > 160) {
                throw new SyncException("Server event entity ID is invalid");
            }
            long sequence = exactLong(event.get("seq"), "seq");
            long baseVersion = exactLong(event.get("baseVersion"), "baseVersion");
            long entityVersion = exactLong(event.get("entityVersion"), "entityVersion");
            if (sequence <= 0 || baseVersion < 0 || entityVersion != baseVersion + 1) {
                throw new SyncException("Server event sequence or version is invalid");
            }
            JSONObject payload = event.getJSONObject("payload");
            String digest = event.getString("payloadSha256");
            if (!digest.matches("(?i)[0-9a-f]{64}")) {
                throw new SyncException("Server event checksum is invalid");
            }
            return new SyncInboxEntity(eventId, storeId, sequence, operation, entityType,
                    entityId, baseVersion, entityVersion, payload.toString(), digest.toLowerCase(),
                    receivedAtUtc, 0);
        } catch (JSONException | NumberFormatException error) {
            throw new SyncException("Server returned a malformed event envelope", error);
        }
    }

    private static long exactLong(Object value, String field) throws SyncException {
        try {
            if (value instanceof Number) return new BigDecimal(value.toString()).longValueExact();
            if (value instanceof String) return new BigDecimal((String) value).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new SyncException("Server event " + field + " is not an exact integer", invalid);
        }
        throw new SyncException("Server event " + field + " is missing");
    }
}
