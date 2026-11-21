package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface StockLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void append(StockLogEntity entry);

    @Query("SELECT * FROM stock_log WHERE sku = :sku ORDER BY created_at_utc DESC, rowid DESC")
    List<StockLogEntity> historyForSku(String sku);

    @Query("SELECT * FROM stock_log ORDER BY created_at_utc DESC, rowid DESC LIMIT :limit")
    List<StockLogEntity> latest(int limit);
}
