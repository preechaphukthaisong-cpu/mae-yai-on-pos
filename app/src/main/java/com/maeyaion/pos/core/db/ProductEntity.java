package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "product",
        foreignKeys = @ForeignKey(entity = BarcodeRegistryEntity.class,
                parentColumns = "barcode", childColumns = "barcode",
                onDelete = ForeignKey.RESTRICT, onUpdate = ForeignKey.RESTRICT),
        indices = {@Index(value = "barcode", unique = true),
                @Index(value = "name"),
                @Index(value = {"is_active", "stock_qty_milli"})})
public final class ProductEntity {
    @PrimaryKey @NonNull public final String sku;
    @NonNull public final String barcode;
    @NonNull public final String name;
    @ColumnInfo(name = "price_satang") public final long priceSatang;
    @ColumnInfo(name = "cost_satang") public final long costSatang;
    @NonNull public final String unit;
    @ColumnInfo(name = "stock_limit_qty_milli") public final Long stockLimitQtyMilli;
    @ColumnInfo(name = "stock_qty_milli", defaultValue = "0") public final long stockQtyMilli;
    @ColumnInfo(name = "is_active", defaultValue = "1") public final int isActive;
    @NonNull @ColumnInfo(name = "created_at_utc") public final String createdAtUtc;
    @NonNull @ColumnInfo(name = "updated_at_utc") public final String updatedAtUtc;

    public ProductEntity(@NonNull String sku, @NonNull String barcode, @NonNull String name,
            long priceSatang, long costSatang, @NonNull String unit, Long stockLimitQtyMilli,
            long stockQtyMilli, int isActive, @NonNull String createdAtUtc,
            @NonNull String updatedAtUtc) {
        this.sku = sku; this.barcode = barcode; this.name = name;
        this.priceSatang = priceSatang; this.costSatang = costSatang; this.unit = unit;
        this.stockLimitQtyMilli = stockLimitQtyMilli; this.stockQtyMilli = stockQtyMilli;
        this.isActive = isActive; this.createdAtUtc = createdAtUtc; this.updatedAtUtc = updatedAtUtc;
    }
}