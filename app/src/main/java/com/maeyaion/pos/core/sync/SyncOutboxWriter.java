package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncEntityVersionEntity;
import com.maeyaion.pos.core.db.SyncOutboxEntity;
import com.maeyaion.pos.core.db.SyncQueueDao;

import java.util.UUID;

/**
 * Writes an event and its next aggregate version inside the caller's business transaction.
 * This class deliberately refuses to open its own transaction: the business mutation,
 * version increment and outbox insert must commit or roll back together.
 */
public final class SyncOutboxWriter {
    public static final String UNBOUND_STORE_ID = "__UNBOUND__";
    private final AppDatabase database;

    public SyncOutboxWriter(@NonNull AppDatabase database) {
        if (database == null) throw new IllegalArgumentException("Database is required");
        this.database = database;
    }

    @NonNull
    public SyncEventEnvelope appendUnboundInTransaction(@NonNull String operation,
            @NonNull String entityType, @NonNull String entityId,
            @NonNull org.json.JSONObject domainData, @NonNull String createdAtUtc)
            throws SyncException {
        return appendInTransaction(UNBOUND_STORE_ID, operation, entityType, entityId,
                domainData, createdAtUtc);
    }

    @NonNull
    public SyncEventEnvelope appendInTransaction(@NonNull String storeId,
            @NonNull String operation, @NonNull String entityType, @NonNull String entityId,
            @NonNull org.json.JSONObject domainData, @NonNull String createdAtUtc)
            throws SyncException {
        if (!database.inTransaction()) {
            throw new SyncException("Outbox events must be appended inside the business Room transaction");
        }
        if (storeId == null || storeId.trim().isEmpty() || storeId.length() > 160
                || createdAtUtc == null || createdAtUtc.trim().isEmpty()) {
            throw new SyncException("Store ID and event timestamp are required");
        }
        SyncQueueDao dao = database.syncQueueDao();
        SyncEntityVersionEntity current = dao.entityVersion(entityType, entityId);
        long baseVersion = current == null ? 0 : current.version;
        if (baseVersion == Long.MAX_VALUE) throw new SyncException("Aggregate version limit reached");

        SyncEventEnvelope envelope = SyncEventEnvelope.create(
                operation, entityType, entityId, baseVersion, domainData);
        final String payloadJson;
        try {
            payloadJson = envelope.payload.toString();
        } catch (RuntimeException error) {
            throw new SyncException("Unable to serialize outbox payload", error);
        }
        dao.enqueue(new SyncOutboxEntity(envelope.eventId, storeId, operation, entityType,
                entityId, baseVersion, payloadJson, envelope.payloadSha256, createdAtUtc,
                "PENDING", 0, null, null));
        dao.saveEntityVersion(new SyncEntityVersionEntity(entityType, entityId,
                baseVersion + 1, createdAtUtc));
        return envelope;
    }
}
