package com.maeyaion.pos.core.db;
import androidx.room.Dao;import androidx.room.Insert;import androidx.room.OnConflictStrategy;import androidx.room.Query;import java.util.List;
@Dao public interface SaleReturnLineDao {
 @Insert(onConflict=OnConflictStrategy.ABORT) void insertAll(List<SaleReturnLineEntity> rows);
 @Query("SELECT COALESCE(SUM(rl.qty_milli),0) FROM sale_return_line rl JOIN sale_return r ON r.id=rl.return_id WHERE rl.sale_line_id=:lineId") long returnedQty(String lineId);
 @Query("SELECT COALESCE(SUM(rl.value_satang),0) FROM sale_return_line rl JOIN sale_return r ON r.id=rl.return_id WHERE rl.sale_line_id=:lineId") long returnedValue(String lineId);
 @Query("SELECT COALESCE(SUM(rl.fee_satang),0) FROM sale_return_line rl JOIN sale_return r ON r.id=rl.return_id WHERE r.sale_id=:saleId") long returnedFee(String saleId);
 @Query("SELECT COALESCE(SUM(rl.value_satang),0) FROM sale_return_line rl JOIN sale_return r ON r.id=rl.return_id WHERE r.sale_id=:saleId") long returnedGoods(String saleId);
 @Query("SELECT * FROM sale_return_line rl JOIN sale_return r ON r.id=rl.return_id WHERE r.sale_id=:saleId") List<SaleReturnLineEntity> forSale(String saleId);
}
