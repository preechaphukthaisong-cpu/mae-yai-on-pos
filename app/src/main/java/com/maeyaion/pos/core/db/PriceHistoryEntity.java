package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "price_history",
        foreignKeys = @ForeignKey(entity = ProductEntity.class, parentColumns = "sku",
                childColumns = "sku", onDelete = ForeignKey.RESTRICT),
        indices = @Index(value = {"sku", "changed_at_utc"}))
public final class PriceHistoryEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String sku;
    public final long old_price_satang;
    public final long new_price_satang;
    @NonNull public final String changed_at_utc;
    public PriceHistoryEntity(@NonNull String id, @NonNull String sku, long old_price_satang,
            long new_price_satang, @NonNull String changed_at_utc) {
        this.id=id; this.sku=sku; this.old_price_satang=old_price_satang;
        this.new_price_satang=new_price_satang; this.changed_at_utc=changed_at_utc;
    }
}
