package com.maeyaion.pos.core.db;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;
@Entity(tableName="stock_count_line",foreignKeys={@ForeignKey(entity=StockCountEntity.class,parentColumns="id",childColumns="stock_count_id",onDelete=ForeignKey.RESTRICT),@ForeignKey(entity=ProductEntity.class,parentColumns="sku",childColumns="sku",onDelete=ForeignKey.RESTRICT)},indices={@Index(value={"stock_count_id","sku"},unique=true),@Index(value="sku")})
public final class StockCountLineEntity {
 @PrimaryKey @NonNull public final String id; @NonNull public final String stock_count_id; @NonNull public final String sku;
 public final long system_qty_milli,counted_qty_milli,diff_qty_milli;
 public StockCountLineEntity(@NonNull String id,@NonNull String stock_count_id,@NonNull String sku,long system_qty_milli,long counted_qty_milli,long diff_qty_milli){this.id=id;this.stock_count_id=stock_count_id;this.sku=sku;this.system_qty_milli=system_qty_milli;this.counted_qty_milli=counted_qty_milli;this.diff_qty_milli=diff_qty_milli;}
}
