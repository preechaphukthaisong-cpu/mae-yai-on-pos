package com.maeyaion.pos.core.db;
import androidx.room.Dao;import androidx.room.Insert;import androidx.room.OnConflictStrategy;import androidx.room.Query;import java.util.List;
@Dao public interface SaleCancelLineDao {
 @Insert(onConflict=OnConflictStrategy.ABORT) void insertAll(List<SaleCancelLineEntity> rows);
 @Query("SELECT COALESCE(SUM(cl.qty_milli),0) FROM sale_cancel_line cl JOIN sale_cancel c ON c.id=cl.cancel_id WHERE cl.sale_line_id=:lineId") long cancelledQty(String lineId);
 @Query("SELECT COALESCE(SUM(cl.value_satang),0) FROM sale_cancel_line cl JOIN sale_cancel c ON c.id=cl.cancel_id WHERE cl.sale_line_id=:lineId") long cancelledValue(String lineId);
 @Query("SELECT COALESCE(SUM(cl.fee_satang),0) FROM sale_cancel_line cl JOIN sale_cancel c ON c.id=cl.cancel_id WHERE c.sale_id=:saleId") long cancelledFee(String saleId);
}
