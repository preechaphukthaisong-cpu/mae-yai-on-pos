package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface StockLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void append(StockLogEntity entry);

    @Query("SELECT * FROM stock_log WHERE sku = :sku ORDER BY created_at_utc DESC")
    java.util.List<StockLogEntity> historyForSku(String sku);
}