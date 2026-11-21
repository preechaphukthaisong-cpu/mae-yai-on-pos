package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncCursorEntity;
import com.maeyaion.pos.core.db.SyncEntityVersionEntity;
import com.maeyaion.pos.core.db.SyncInboxEntity;
import com.maeyaion.pos.core.db.SyncQueueDao;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Crash-safe inbox replay coordinator. Domain handlers must use the same Room database
 * and enforce the same business invariants as local commands. No generic SQL/JSON mapping
 * is permitted. A handler failure rolls back both the domain mutation and inbox/cursor state.
 */
public final class SyncInboxProcessor {
    public interface DomainEventApplier {
        void apply(@NonNull SyncInboxEntity event) throws Exception;
    }

    private final AppDatabase database;

    public SyncInboxProcessor(@NonNull AppDatabase database) {
        if (database == null) throw new IllegalArgumentException("Database is required");
        this.database = database;
    }

    /** Stores a remote event once. Duplicate event IDs are ignored by the inbox primary key. */
    public boolean receive(@NonNull SyncInboxEntity event) {
        if (event == null) throw new IllegalArgumentException("Remote event is required");
        if (event.server_sequence <= 0 || event.base_version < 0
                || event.entity_version <= 0 || event.payload_sha256.length() != 64) {
            throw new IllegalArgumentException("Remote event metadata is invalid");
        }
        try {
            JSONObject payload = new JSONObject(event.payload_json);
            String canonical = SyncEventEnvelope.canonicalJson(payload);
            String actual = sha256(canonical.getBytes(StandardCharsets.UTF_8));
            if (!actual.equalsIgnoreCase(event.payload_sha256)) {
                throw new IllegalArgumentException("Remote event payload checksum mismatch");
            }
        } catch (JSONException | SyncException error) {
            throw new IllegalArgumentException("Remote event payload is invalid", error);
        }
        SyncQueueDao dao = database.syncQueueDao();
        long inserted = dao.receiveRemote(event);
        if (inserted != -1L) return true;
        SyncInboxEntity existing = dao.inboxEvent(event.event_id);
        if (existing == null || !existing.store_id.equals(event.store_id)
                || existing.server_sequence != event.server_sequence
                || !existing.payload_sha256.equalsIgnoreCase(event.payload_sha256)
                || !existing.payload_json.equals(event.payload_json)
                || !existing.entity_type.equals(event.entity_type)
                || !existing.entity_id.equals(event.entity_id)
                || existing.base_version != event.base_version
                || existing.entity_version != event.entity_version
                || !existing.operation.equals(event.operation)) {
            throw new IllegalArgumentException("A remote event ID was reused with different content");
        }
        return false;
    }

    /**
     * Applies at most one event per transaction. Returns false when no unapplied event exists.
     * The cursor moves only in the same commit as the business mutation and inbox acknowledgement.
     */
    public boolean applyNext(@NonNull String storeId, @NonNull String nowUtc,
            @NonNull DomainEventApplier applier) throws SyncException {
        if (storeId == null || storeId.trim().isEmpty() || nowUtc == null
                || nowUtc.trim().isEmpty() || applier == null) {
            throw new IllegalArgumentException("Store, timestamp and domain applier are required");
        }
        final boolean[] applied = {false};
        final SyncException[] failure = {null};
        try {
            database.runInTransaction(() -> {
                try {
                    SyncQueueDao dao = database.syncQueueDao();
                    List<SyncInboxEntity> batch = dao.unappliedInbox(storeId, 1);
                    if (batch.isEmpty()) return;
                    SyncInboxEntity event = batch.get(0);
                    SyncCursorEntity cursor = dao.cursor(storeId);
                    long lastSequence = cursor == null ? 0 : cursor.last_applied_sequence;

                    if (event.server_sequence <= lastSequence) {
                        dao.markRemoteApplied(event.event_id);
                        applied[0] = true;
                        return;
                    }
                    if (event.server_sequence != lastSequence + 1) {
                        throw new SyncException("Remote event sequence gap; refusing to advance cursor");
                    }

                    SyncEntityVersionEntity current = dao.entityVersion(event.entity_type, event.entity_id);
                    long localVersion = current == null ? 0 : current.version;
                    if (localVersion != event.base_version) {
                        throw new SyncException("Sync conflict: local aggregate version does not match remote base version");
                    }
                    if (event.entity_version != event.base_version + 1) {
                        throw new SyncException("Remote aggregate version is not the expected next version");
                    }

                    // Must call a domain service that writes through this same AppDatabase transaction.
                    applier.apply(event);
                    dao.saveEntityVersion(new SyncEntityVersionEntity(event.entity_type,
                            event.entity_id, event.entity_version, nowUtc));
                    if (dao.markRemoteApplied(event.event_id) != 1) {
                        throw new SyncException("Remote inbox acknowledgement was not persisted");
                    }
                    dao.saveCursor(new SyncCursorEntity(storeId, event.server_sequence, nowUtc));
                    applied[0] = true;
                } catch (SyncException error) {
                    failure[0] = error;
                    throw new ReplayAbort(error);
                } catch (Exception error) {
                    failure[0] = new SyncException("Remote event replay failed; transaction rolled back", error);
                    throw new ReplayAbort(failure[0]);
                }
            });
        } catch (ReplayAbort ignored) {
            throw failure[0] == null ? new SyncException("Remote event replay rolled back") : failure[0];
        }
        return applied[0];
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

    private static final class ReplayAbort extends RuntimeException {
        ReplayAbort(Throwable cause) { super(cause); }
    }
}
