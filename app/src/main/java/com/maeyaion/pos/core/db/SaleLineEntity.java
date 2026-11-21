package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "sale_line",
        foreignKeys = {
                @ForeignKey(entity = SaleEntity.class, parentColumns = "id", childColumns = "sale_id", onDelete = ForeignKey.RESTRICT),
                @ForeignKey(entity = ProductEntity.class, parentColumns = "sku", childColumns = "sku", onDelete = ForeignKey.RESTRICT)
        },
        indices = {@Index(value = {"sale_id", "line_no"}, unique = true), @Index(value = "sku")})
public final class SaleLineEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull public final String sale_id;
    public final int line_no;
    @NonNull public final String sku;
    @NonNull public final String name_snap;
    public final long price_snap_satang;
    public final long cost_snap_satang;
    public final long qty_milli;
    public final long line_total_satang;

    public SaleLineEntity(@NonNull String id, @NonNull String sale_id, int line_no,
            @NonNull String sku, @NonNull String name_snap, long price_snap_satang,
            long cost_snap_satang, long qty_milli, long line_total_satang) {
        this.id=id; this.sale_id=sale_id; this.line_no=line_no; this.sku=sku;
        this.name_snap=name_snap; this.price_snap_satang=price_snap_satang;
        this.cost_snap_satang=cost_snap_satang; this.qty_milli=qty_milli;
        this.line_total_satang=line_total_satang;
    }
}
