package com.maeyaion.pos.core.db;
import androidx.room.Dao;
import androidx.room.Query;
@Dao public interface SaleBalanceDao {
 @Query("SELECT s.total_satang-COALESCE((SELECT SUM(r.debt_reduction_satang) FROM sale_return r WHERE r.sale_id=s.id),0)-COALESCE((SELECT SUM(c.debt_reduction_satang) FROM sale_cancel c WHERE c.sale_id=s.id),0) FROM sale s WHERE s.id=:saleId")
 long outstanding(String saleId);
}
