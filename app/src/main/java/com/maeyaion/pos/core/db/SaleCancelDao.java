package com.maeyaion.pos.core.db;
import androidx.room.Dao;import androidx.room.Insert;import androidx.room.OnConflictStrategy;import androidx.room.Query;
@Dao public interface SaleCancelDao {
 @Insert(onConflict=OnConflictStrategy.ABORT) void insert(SaleCancelEntity row);
 @Query("SELECT * FROM sale_cancel WHERE sale_id=:saleId LIMIT 1") SaleCancelEntity findForSale(String saleId);
}
