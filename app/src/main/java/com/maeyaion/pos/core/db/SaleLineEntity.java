package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "sale_line",
        foreignKeys = {
                @ForeignKey(entity = SaleEntity.class, parentColumns = "id",
                        childColumns = "sale_id", onDelete = ForeignKey.RESTRICT,
                        onUpdate = ForeignKey.RESTRICT),
                @ForeignKey(entity = ProductEntity.class, parentColumns = "sku",
                        childColumns = "sku", onDelete = ForeignKey.RESTRICT,
                        onUpdate = ForeignKey.RESTRICT)
        },
        indices = {@Index(value = {"sale_id", "line_no"}, unique = true),
                @Index(value = "sku")})
public final class SaleLineEntity {
    @PrimaryKey @NonNull public final String id;
    @NonNull @ColumnInfo(name = "sale_id") public final String saleId;
    @ColumnInfo(name = "line_no") public final int lineNo;
    @NonNull public final String sku;
    @NonNull @ColumnInfo(name = "name_snap") public final String nameSnap;
    @ColumnInfo(name = "price_snap_satang") public final long priceSnapSatang;
    @ColumnInfo(name = "cost_snap_satang") public final long costSnapSatang;
    @ColumnInfo(name = "qty_milli") public final long qtyMilli;
    @ColumnInfo(name = "line_total_satang") public final long lineTotalSatang;

    public SaleLineEntity(@NonNull String id, @NonNull String saleId, int lineNo,
            @NonNull String sku, @NonNull String nameSnap, long priceSnapSatang,
            long costSnapSatang, long qtyMilli, long lineTotalSatang) {
        this.id = id; this.saleId = saleId; this.lineNo = lineNo; this.sku = sku;
        this.nameSnap = nameSnap; this.priceSnapSatang = priceSnapSatang;
        this.costSnapSatang = costSnapSatang; this.qtyMilli = qtyMilli;
        this.lineTotalSatang = lineTotalSatang;
    }
}
