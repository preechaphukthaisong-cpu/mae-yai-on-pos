package com.maeyaion.pos.core.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "barcode_registry")
public final class BarcodeRegistryEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "barcode")
    public final String barcode;
    @NonNull @ColumnInfo(name = "display_name") public final String displayName;
    @ColumnInfo(name = "price_satang") public final long priceSatang;
    @NonNull @ColumnInfo(name = "source_type") public final String sourceType;
    @NonNull @ColumnInfo(name = "created_at_utc") public final String createdAtUtc;

    public BarcodeRegistryEntity(@NonNull String barcode, @NonNull String displayName,
            long priceSatang, @NonNull String sourceType, @NonNull String createdAtUtc) {
        this.barcode = barcode;
        this.displayName = displayName;
        this.priceSatang = priceSatang;
        this.sourceType = sourceType;
        this.createdAtUtc = createdAtUtc;
    }
}