package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncCursorEntity;
import com.maeyaion.pos.core.db.SyncInboxEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;

/**
 * Downloads authoritative events into the durable inbox. It never advances the applied
 * cursor; only SyncInboxProcessor may do that after a domain transaction commits.
 */
public final class SyncPullCoordinator {
    private static final int PAGE_SIZE = 200;
    private final AppDatabase database;
    private final SyncApiClient api;
    private final SyncInboxProcessor inbox;

    public SyncPullCoordinator(@NonNull AppDatabase database, @NonNull SyncApiClient api) {
        if (database == null || api == null) throw new IllegalArgumentException("Database and API are required");
        this.database = database;
        this.api = api;
        this.inbox = new SyncInboxProcessor(database);
    }

    @NonNull
    public PullResult pullPage(@NonNull String storeId) throws SyncException {
        if (storeId == null || storeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Store ID is required");
        }
        SyncCursorEntity cursor = database.syncQueueDao().cursor(storeId);
        long after = cursor == null ? 0L : cursor.last_applied_sequence;
        JSONObject response = api.pullEvents(after, PAGE_SIZE);
        try {
            JSONArray events = response.getJSONArray("events");
            java.util.List<SyncInboxEntity> mappedEvents = new java.util.ArrayList<>();
            long expectedSequence = after;
            String receivedAt = Instant.now().toString();
            for (int i = 0; i < events.length(); i++) {
                JSONObject raw = events.getJSONObject(i);
                SyncInboxEntity mapped = SyncInboxEventMapper.fromServer(storeId, raw, receivedAt);
                if (expectedSequence == Long.MAX_VALUE
                        || mapped.server_sequence != expectedSequence + 1) {
                    throw new SyncException("Server pull page contains a sequence gap or reordering");
                }
                expectedSequence = mapped.server_sequence;
                mappedEvents.add(mapped);
            }
            long nextCursor = parseSequence(response.get("nextCursor"));
            if (nextCursor != expectedSequence) {
                throw new SyncException("Server pull cursor does not match the final event sequence");
            }
            Object hasMoreValue = response.get("hasMore");
            if (!(hasMoreValue instanceof Boolean)) {
                throw new SyncException("Server pull hasMore flag is missing or invalid");
            }
            final int[] counts = {0, 0};
            database.runInTransaction(() -> {
                for (SyncInboxEntity mapped : mappedEvents) {
                    try {
                        if (inbox.receive(mapped)) counts[0]++;
                        else counts[1]++;
                    } catch (IllegalArgumentException invalidEvent) {
                        throw new IllegalStateException("Pull page contains a conflicting duplicate event", invalidEvent);
                    }
                }
            });
            return new PullResult(counts[0], counts[1], nextCursor, (Boolean) hasMoreValue);
        } catch (JSONException error) {
            throw new SyncException("Sync server returned a malformed pull page", error);
        } catch (IllegalStateException error) {
            throw new SyncException("Unable to persist the complete sync pull page", error);
        }
    }


    /** Applies only authenticated/decrypted events; a failed domain event remains quarantined in inbox. */
    public int applyPending(@NonNull String storeId, @NonNull String nowUtc,
            @NonNull StoreKeyManager keyManager, int maxEvents) throws SyncException {
        if (storeId == null || storeId.trim().isEmpty() || nowUtc == null
                || nowUtc.trim().isEmpty() || keyManager == null || maxEvents < 1
                || maxEvents > 1000) {
            throw new IllegalArgumentException("Store, timestamp, key manager and bounded event count are required");
        }
        int applied = 0;
        while (applied < maxEvents && inbox.applyNext(storeId, nowUtc, keyManager)) {
            applied++;
        }
        return applied;
    }

    private static long parseSequence(Object value) throws SyncException {
        try {
            if (value instanceof Number) return new java.math.BigDecimal(value.toString()).longValueExact();
            if (value instanceof String) return new java.math.BigDecimal((String) value).longValueExact();
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new SyncException("Server pull cursor is not an exact integer", invalid);
        }
        throw new SyncException("Server pull cursor is missing");
    }

    public static final class PullResult {
        public final int inserted;
        public final int duplicates;
        public final long serverNextCursor;
        public final boolean hasMore;

        PullResult(int inserted, int duplicates, long serverNextCursor, boolean hasMore) {
            this.inserted = inserted;
            this.duplicates = duplicates;
            this.serverNextCursor = serverNextCursor;
            this.hasMore = hasMore;
        }
    }
}
