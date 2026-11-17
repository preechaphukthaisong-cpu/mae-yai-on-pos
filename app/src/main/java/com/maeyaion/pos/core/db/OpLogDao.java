package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface OpLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void append(OpLogEntity entry);

    @Query("SELECT * FROM op_log ORDER BY created_at_utc DESC LIMIT :limit")
    java.util.List<OpLogEntity> latest(int limit);
}