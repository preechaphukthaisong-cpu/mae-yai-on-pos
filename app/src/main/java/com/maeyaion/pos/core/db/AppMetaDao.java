package com.maeyaion.pos.core.db;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
@Dao
public interface AppMetaDao {
    @Query("SELECT value FROM app_meta WHERE meta_key = :key LIMIT 1")
    String findValue(String key);
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(AppMetaEntity entry);
}
