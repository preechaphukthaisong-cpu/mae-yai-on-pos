package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncOutboxEntity;
import com.maeyaion.pos.core.db.SyncQueueDao;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** At-least-once outbox delivery with server acknowledgements and durable conflict quarantine. */
public final class SyncPushCoordinator {
    private static final int BATCH_SIZE = 100;
    private final AppDatabase database;
    private final SyncApiClient api;

    public SyncPushCoordinator(@NonNull AppDatabase database, @NonNull SyncApiClient api) {
        if (database == null || api == null) throw new IllegalArgumentException("Database and API are required");
        this.database = database;
        this.api = api;
    }

    @NonNull
    public PushResult pushPending(@NonNull String storeId) throws SyncException {
        if (storeId == null || storeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Store ID is required");
        }
        SyncQueueDao dao = database.syncQueueDao();
        List<SyncOutboxEntity> batch = dao.pendingOutbox(storeId, BATCH_SIZE);
        if (batch.isEmpty()) return new PushResult(0, 0, 0);

        JSONArray wireEvents = new JSONArray();
        Set<String> expectedIds = new HashSet<>();
        try {
            for (SyncOutboxEntity event : batch) {
                if (!expectedIds.add(event.event_id)) {
                    throw new SyncException("Duplicate event ID exists in the local outbox");
                }
                JSONObject payload = new JSONObject(event.payload_json);
                String canonical = SyncEventEnvelope.canonicalJson(payload);
                if (!event.payload_sha256.equalsIgnoreCase(sha256(canonical.getBytes(StandardCharsets.UTF_8)))) {
                    throw new SyncException("Local outbox payload checksum mismatch: " + event.event_id);
                }
                if (event.base_version > 9_007_199_254_740_991L) {
                    throw new SyncException("Aggregate version exceeds the server safe integer range");
                }
                JSONObject wire = new JSONObject();
                wire.put("eventId", event.event_id);
                wire.put("operation", event.operation);
                wire.put("entityType", event.entity_type);
                wire.put("entityId", event.entity_id);
                wire.put("baseVersion", event.base_version);
                wire.put("payload", payload);
                wire.put("payloadSha256", event.payload_sha256);
                wireEvents.put(wire);
            }
        } catch (JSONException error) {
            throw new SyncException("Unable to serialize local outbox batch", error);
        }

        final JSONObject response;
        try {
            response = api.pushEvents(wireEvents);
        } catch (SyncException error) {
            recordFailure(batch, error.getMessage());
            throw error;
        }

        try {
            JSONArray acknowledgements = response.getJSONArray("acknowledgements");
            JSONArray conflicts = response.getJSONArray("conflicts");
            Set<String> handled = new HashSet<>();
            int acknowledged = 0;
            int conflicted = 0;
            for (int i = 0; i < acknowledgements.length(); i++) {
                String eventId = acknowledgements.getJSONObject(i).getString("eventId");
                requireExpected(expectedIds, eventId);
                if (!handled.add(eventId)) throw new SyncException("Server duplicated an event acknowledgement");
                acknowledged++;
            }
            for (int i = 0; i < conflicts.length(); i++) {
                JSONObject conflict = conflicts.getJSONObject(i);
                String eventId = conflict.getString("eventId");
                requireExpected(expectedIds, eventId);
                if (!handled.add(eventId)) throw new SyncException("Server both acknowledged and conflicted one event");
                conflicted++;
            }
            String now = Instant.now().toString();
            database.runInTransaction(() -> {
                for (int i = 0; i < acknowledgements.length(); i++) {
                    try {
                        String eventId = acknowledgements.getJSONObject(i).getString("eventId");
                        if (dao.acknowledgeOutbox(eventId) != 1) {
                            throw new IllegalStateException("Acknowledged outbox event was not pending: " + eventId);
                        }
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                }
                for (int i = 0; i < conflicts.length(); i++) {
                    try {
                        JSONObject conflict = conflicts.getJSONObject(i);
                        String eventId = conflict.getString("eventId");
                        String message = "VERSION_CONFLICT expected="
                                + conflict.optString("expectedVersion", "?")
                                + " actual=" + conflict.optString("actualVersion", "?");
                        if (dao.markConflict(eventId, now, message) != 1) {
                            throw new IllegalStateException("Conflicted outbox event was not pending: " + eventId);
                        }
                    } catch (JSONException error) {
                        throw new IllegalStateException(error);
                    }
                }
                for (SyncOutboxEntity event : batch) {
                    if (!handled.contains(event.event_id)) {
                        dao.recordAttempt(event.event_id, now, "Server response omitted this event acknowledgement");
                    }
                }
            });
            return new PushResult(batch.size(), acknowledged, conflicted);
        } catch (JSONException error) {
            recordFailure(batch, "Malformed push acknowledgement");
            throw new SyncException("Sync server returned a malformed push acknowledgement", error);
        } catch (SyncException error) {
            recordFailure(batch, error.getMessage());
            throw error;
        } catch (IllegalStateException error) {
            throw new SyncException("Unable to commit sync acknowledgements", error);
        }
    }

    private void recordFailure(List<SyncOutboxEntity> batch, String message) {
        String now = Instant.now().toString();
        String safe = message == null ? "Unknown sync failure" : message;
        if (safe.length() > 400) safe = safe.substring(0, 400);
        final String error = safe;
        database.runInTransaction(() -> {
            SyncQueueDao dao = database.syncQueueDao();
            for (SyncOutboxEntity event : batch) {
                dao.recordAttempt(event.event_id, now, error);
            }
        });
    }

    private static void requireExpected(Set<String> expected, String eventId) throws SyncException {
        if (!expected.contains(eventId)) {
            throw new SyncException("Server acknowledged an event that was not sent in this batch");
        }
    }

    private static String sha256(byte[] bytes) throws SyncException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new SyncException("SHA-256 is unavailable", error);
        }
    }

    public static final class PushResult {
        public final int sent;
        public final int acknowledged;
        public final int conflicts;
        PushResult(int sent, int acknowledged, int conflicts) {
            this.sent = sent; this.acknowledged = acknowledged; this.conflicts = conflicts;
        }
    }
}
