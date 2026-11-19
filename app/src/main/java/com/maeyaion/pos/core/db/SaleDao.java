package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface SaleDao {
    @Query("SELECT * FROM sale WHERE idem_key = :idemKey LIMIT 1")
    SaleEntity findByIdempotencyKey(String idemKey);

    @Query("SELECT * FROM sale WHERE id = :saleId LIMIT 1")
    SaleEntity findById(String saleId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertSale(SaleEntity sale);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertLines(List<SaleLineEntity> lines);

    @Query("SELECT * FROM sale_line WHERE sale_id = :saleId ORDER BY line_no ASC")
    List<SaleLineEntity> linesForSale(String saleId);
}
