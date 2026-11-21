package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface PriceHistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) void append(PriceHistoryEntity entry);
    @Query("SELECT * FROM price_history WHERE sku = :sku ORDER BY changed_at_utc DESC") List<PriceHistoryEntity> historyForSku(String sku);
    @Query("SELECT * FROM price_history ORDER BY changed_at_utc DESC LIMIT :limit") List<PriceHistoryEntity> latest(int limit);
}
