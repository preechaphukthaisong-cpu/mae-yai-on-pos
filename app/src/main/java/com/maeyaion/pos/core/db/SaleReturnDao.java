package com.maeyaion.pos.core.db;
import androidx.room.Dao;import androidx.room.Insert;import androidx.room.OnConflictStrategy;import androidx.room.Query;
@Dao public interface SaleReturnDao {
 @Insert(onConflict=OnConflictStrategy.ABORT) void insert(SaleReturnEntity row);
 @Query("SELECT * FROM sale_return WHERE sale_id=:saleId ORDER BY created_at_utc") java.util.List<SaleReturnEntity> forSale(String saleId);
 @Query("SELECT COALESCE(SUM(cash_refund_satang),0) FROM sale_return WHERE sale_id=:saleId") long cashRefunded(String saleId);
}
