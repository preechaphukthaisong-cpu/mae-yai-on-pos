package com.maeyaion.pos.core.db;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;
@Dao public interface DailySummaryDao {
 @Insert(onConflict=OnConflictStrategy.IGNORE) void ensure(DailySummaryEntity row);
 @Query("UPDATE daily_summary SET cash_sales_satang=cash_sales_satang+:cash, credit_sales_satang=credit_sales_satang+:credit, fees_satang=fees_satang+:fee, net_sales_satang=net_sales_satang+:net, updated_at_utc=:updated WHERE business_date=:date")
 int addSale(String date,long cash,long credit,long fee,long net,String updated);
 @Query("SELECT * FROM daily_summary WHERE business_date BETWEEN :fromDate AND :toDate ORDER BY business_date") List<DailySummaryEntity> range(String fromDate,String toDate);
 @Query("SELECT * FROM daily_summary WHERE business_date=:date LIMIT 1") DailySummaryEntity find(String date);
}
