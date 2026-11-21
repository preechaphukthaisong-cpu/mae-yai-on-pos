package com.maeyaion.pos.core.db;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;
@Dao public interface ProductDailyDao {
 @Insert(onConflict=OnConflictStrategy.IGNORE) void ensure(ProductDailyEntity row);
 @Query("UPDATE product_daily SET sold_qty_milli=sold_qty_milli+:qty, net_qty_milli=net_qty_milli+:qty, sold_goods_satang=sold_goods_satang+:goods, net_goods_satang=net_goods_satang+:goods WHERE business_date=:date AND sku=:sku")
 int addSale(String date,String sku,long qty,long goods);
 @Query("UPDATE product_daily SET returned_qty_milli=returned_qty_milli+:qty, net_qty_milli=net_qty_milli-:qty, returned_goods_satang=returned_goods_satang+:goods, net_goods_satang=net_goods_satang-:goods WHERE business_date=:date AND sku=:sku")
 int addReturn(String date,String sku,long qty,long goods);
 @Query("UPDATE product_daily SET cancelled_qty_milli=cancelled_qty_milli+:qty, net_qty_milli=net_qty_milli-:qty, cancelled_goods_satang=cancelled_goods_satang+:goods, net_goods_satang=net_goods_satang-:goods WHERE business_date=:date AND sku=:sku")
 int addCancel(String date,String sku,long qty,long goods);
 @Query("SELECT p.sku AS sku,p.name AS name,SUM(d.net_qty_milli) AS net_qty_milli,SUM(d.net_goods_satang) AS net_goods_satang FROM product_daily d JOIN product p ON p.sku=d.sku WHERE d.business_date BETWEEN :fromDate AND :toDate GROUP BY p.sku,p.name ORDER BY net_qty_milli DESC,p.name COLLATE NOCASE LIMIT 20")
 List<ProductRankRow> topSellers(String fromDate,String toDate);
 @Query("SELECT p.sku AS sku,p.name AS name,SUM(d.net_qty_milli) AS net_qty_milli,SUM(d.net_goods_satang) AS net_goods_satang FROM product_daily d JOIN product p ON p.sku=d.sku WHERE d.business_date BETWEEN :fromDate AND :toDate GROUP BY p.sku,p.name HAVING SUM(d.net_qty_milli)>0 ORDER BY net_qty_milli ASC,p.name COLLATE NOCASE LIMIT 20")
 List<ProductRankRow> leastSellers(String fromDate,String toDate);
}
