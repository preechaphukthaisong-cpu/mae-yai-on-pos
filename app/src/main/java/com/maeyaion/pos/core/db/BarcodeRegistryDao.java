package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface BarcodeRegistryDao {
    @Query("SELECT * FROM barcode_registry WHERE barcode = :barcode LIMIT 1")
    BarcodeRegistryEntity findByBarcode(String barcode);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(BarcodeRegistryEntity entry);
}