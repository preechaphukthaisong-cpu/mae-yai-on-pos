package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface BackupJournalDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    long append(BackupJournalEntity entry);

    @Query("SELECT * FROM backup_journal WHERE operation_id = :operationId LIMIT 1")
    BackupJournalEntity findByOperationId(String operationId);

    @Query("SELECT * FROM backup_journal WHERE uploaded = 0 ORDER BY seq ASC LIMIT :limit")
    List<BackupJournalEntity> pendingBatch(int limit);

    @Query("UPDATE backup_journal SET uploaded = 1 WHERE seq = :seq AND uploaded = 0")
    int markUploaded(long seq);

    @Query("SELECT COUNT(*) FROM backup_journal WHERE uploaded = 0")
    long pendingCount();
}
