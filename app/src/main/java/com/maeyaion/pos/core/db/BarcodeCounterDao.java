package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Query;
import androidx.room.Update;

@Dao
public interface BarcodeCounterDao {
    @Query("SELECT * FROM barcode_counter WHERE id = 1 LIMIT 1")
    BarcodeCounterEntity get();

    @Update
    int update(BarcodeCounterEntity counter);
}