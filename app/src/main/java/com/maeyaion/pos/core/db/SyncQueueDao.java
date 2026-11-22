package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void enqueue(SyncOutboxEntity event);

    @Query("SELECT * FROM sync_outbox WHERE store_id=:storeId AND delivery_state='PENDING' ORDER BY rowid ASC LIMIT :limit")
    List<SyncOutboxEntity> pendingOutbox(String storeId, int limit);

    @Query("UPDATE sync_outbox SET delivery_state='CONFLICT', attempt_count=attempt_count+1, last_attempt_at_utc=:attemptedAt, last_error=:error WHERE event_id=:eventId AND delivery_state='PENDING'")
    int markConflict(String eventId, String attemptedAt, String error);

    @Query("SELECT * FROM sync_outbox WHERE store_id=:storeId AND delivery_state='CONFLICT' ORDER BY created_at_utc ASC, event_id ASC")
    List<SyncOutboxEntity> conflictedOutbox(String storeId);

    @Query("UPDATE sync_outbox SET attempt_count=attempt_count+1, last_attempt_at_utc=:attemptedAt, last_error=:error WHERE event_id=:eventId AND delivery_state='PENDING'")
    int recordAttempt(String eventId, String attemptedAt, String error);

    @Query("UPDATE sync_outbox SET delivery_state='ACKNOWLEDGED' WHERE event_id=:eventId AND delivery_state='PENDING'")
    int acknowledgeOutbox(String eventId);

    @Query("SELECT * FROM sync_outbox WHERE event_id=:eventId LIMIT 1")
    SyncOutboxEntity outboxEvent(String eventId);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long receiveRemote(SyncInboxEntity event);

    @Query("SELECT * FROM sync_inbox WHERE event_id=:eventId LIMIT 1")
    SyncInboxEntity inboxEvent(String eventId);

    @Query("SELECT * FROM sync_inbox WHERE store_id=:storeId AND applied=0 ORDER BY server_sequence ASC LIMIT :limit")
    List<SyncInboxEntity> unappliedInbox(String storeId, int limit);

    @Query("UPDATE sync_inbox SET applied=1 WHERE event_id=:eventId AND applied=0")
    int markRemoteApplied(String eventId);

    @Query("SELECT store_id FROM sync_cursor ORDER BY updated_at_utc DESC LIMIT 1")
    String boundStoreId();

    @Query("SELECT * FROM sync_cursor WHERE store_id=:storeId LIMIT 1")
    SyncCursorEntity cursor(String storeId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveCursor(SyncCursorEntity cursor);

    @Query("SELECT * FROM sync_entity_version WHERE entity_type=:entityType AND entity_id=:entityId LIMIT 1")
    SyncEntityVersionEntity entityVersion(String entityType, String entityId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void saveEntityVersion(SyncEntityVersionEntity version);

    @Query("UPDATE sync_outbox SET store_id=:storeId WHERE store_id='__UNBOUND__'")
    int bindUnboundOutbox(String storeId);

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE store_id=:storeId AND delivery_state='PENDING'")
    long pendingOutboxCount(String storeId);

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE store_id='__UNBOUND__' AND delivery_state='PENDING'")
    long unboundPendingOutboxCount(String storeId);

    @Query("SELECT COUNT(*) FROM sync_inbox WHERE store_id=:storeId AND applied=0")
    long unappliedInboxCount(String storeId);
}
