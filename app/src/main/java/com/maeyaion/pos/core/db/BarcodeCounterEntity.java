package com.maeyaion.pos.core.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "barcode_counter")
public final class BarcodeCounterEntity {
    @PrimaryKey
    public final int id;
    @ColumnInfo(name = "next_value")
    public final long nextValue;

    public BarcodeCounterEntity(int id, long nextValue) {
        this.id = id;
        this.nextValue = nextValue;
    }
}