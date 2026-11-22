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
            int inserted = 0;
            int duplicates = 0;
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
                if (inbox.receive(mapped)) inserted++;
                else duplicates++;
            }
            long nextCursor = parseSequence(response.get("nextCursor"));
            if (nextCursor != expectedSequence) {
                throw new SyncException("Server pull cursor does not match the final event sequence");
            }
            Object hasMoreValue = response.get("hasMore");
            if (!(hasMoreValue instanceof Boolean)) {
                throw new SyncException("Server pull hasMore flag is missing or invalid");
            }
            return new PullResult(inserted, duplicates, nextCursor, (Boolean) hasMoreValue);
        } catch (JSONException error) {
            throw new SyncException("Sync server returned a malformed pull page", error);
        }
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
