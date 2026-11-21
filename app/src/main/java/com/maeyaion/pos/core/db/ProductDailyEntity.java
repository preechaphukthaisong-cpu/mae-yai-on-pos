package com.maeyaion.pos.core.db;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
@Entity(tableName="product_daily",primaryKeys={"business_date","sku"},foreignKeys=@ForeignKey(entity=ProductEntity.class,parentColumns="sku",childColumns="sku",onDelete=ForeignKey.RESTRICT),indices=@Index(value={"business_date","sku"}))
public final class ProductDailyEntity {
 @NonNull public final String business_date; @NonNull public final String sku;
 public final long sold_qty_milli,returned_qty_milli,cancelled_qty_milli,net_qty_milli,sold_goods_satang,returned_goods_satang,cancelled_goods_satang,net_goods_satang;
 public ProductDailyEntity(@NonNull String business_date,@NonNull String sku,long sold_qty_milli,long returned_qty_milli,long cancelled_qty_milli,long net_qty_milli,long sold_goods_satang,long returned_goods_satang,long cancelled_goods_satang,long net_goods_satang){this.business_date=business_date;this.sku=sku;this.sold_qty_milli=sold_qty_milli;this.returned_qty_milli=returned_qty_milli;this.cancelled_qty_milli=cancelled_qty_milli;this.net_qty_milli=net_qty_milli;this.sold_goods_satang=sold_goods_satang;this.returned_goods_satang=returned_goods_satang;this.cancelled_goods_satang=cancelled_goods_satang;this.net_goods_satang=net_goods_satang;}
}
