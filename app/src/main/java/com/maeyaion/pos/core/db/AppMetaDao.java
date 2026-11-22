package com.maeyaion.pos.core.db;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
@Dao
public interface AppMetaDao {
    @Query("SELECT value FROM app_meta WHERE meta_key = :key LIMIT 1")
    String findValue(String key);
    @Query("SELECT * FROM app_meta WHERE meta_key = :key LIMIT 1")\n    AppMetaEntity findEntry(String key);\n\n    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(AppMetaEntity entry);
}
