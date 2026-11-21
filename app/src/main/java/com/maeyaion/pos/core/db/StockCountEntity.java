package com.maeyaion.pos.core.db;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName="stock_count")
public final class StockCountEntity {
 @PrimaryKey @NonNull public final String id; @NonNull public final String created_at_utc; public final String note;
 public StockCountEntity(@NonNull String id,@NonNull String created_at_utc,String note){this.id=id;this.created_at_utc=created_at_utc;this.note=note;}
}
