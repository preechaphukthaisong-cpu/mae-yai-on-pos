package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "stock_log",
        foreignKeys = @ForeignKey(entity = ProductEntity.class, parentColumns = "sku",
                childColumns = "sku", onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT),
        indices = {@Index(value = {"sku", "created_at_utc"}), @Index(value = "ref_id")})
public final class StockLogEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String sku;
    public final long before_qty_milli;
    public final long after_qty_milli;
    public final long diff_qty_milli;
    @NonNull public final String reason;
    public final String ref_id;
    @NonNull public final String created_at_utc;

    public StockLogEntity(@NonNull String id, @NonNull String sku, long before_qty_milli,
            long after_qty_milli, long diff_qty_milli, @NonNull String reason,
            String ref_id, @NonNull String created_at_utc) {
        this.id = id; this.sku = sku; this.before_qty_milli = before_qty_milli;
        this.after_qty_milli = after_qty_milli; this.diff_qty_milli = diff_qty_milli;
        this.reason = reason; this.ref_id = ref_id; this.created_at_utc = created_at_utc;
    }
}