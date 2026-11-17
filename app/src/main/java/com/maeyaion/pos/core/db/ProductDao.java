package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface ProductDao {
    @Query("SELECT * FROM product WHERE sku = :sku LIMIT 1")
    ProductEntity findBySku(String sku);

    @Query("SELECT * FROM product WHERE barcode = :barcode LIMIT 1")
    ProductEntity findByBarcode(String barcode);

    @Query("SELECT * FROM product WHERE is_active = 1 ORDER BY name COLLATE NOCASE")
    java.util.List<ProductEntity> listActive();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(ProductEntity product);
}