package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface SaleLineDao {
    @Query("SELECT * FROM sale_line WHERE sale_id = :saleId ORDER BY line_no") List<SaleLineEntity> forSale(String saleId);
    @Query("SELECT * FROM sale_line WHERE id = :id LIMIT 1") SaleLineEntity findById(String id);
    @Insert(onConflict = OnConflictStrategy.ABORT) void insertAll(List<SaleLineEntity> lines);
}
