package com.maeyaion.pos.core.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ProductDao {
    @Query("SELECT * FROM product WHERE sku = :sku LIMIT 1")
    ProductEntity findBySku(String sku);

    @Query("SELECT * FROM product WHERE barcode = :barcode LIMIT 1")
    ProductEntity findByBarcode(String barcode);

    @Query("SELECT * FROM product WHERE is_active = 1 ORDER BY name COLLATE NOCASE")
    List<ProductEntity> listActive();

    @Query("SELECT * FROM product WHERE is_active = 1 ORDER BY stock_qty_milli ASC, name COLLATE NOCASE ASC")
    List<ProductEntity> listActiveByStockAscending();

    @Query("SELECT * FROM product WHERE is_active = 1 AND stock_limit_qty_milli IS NOT NULL AND stock_qty_milli < stock_limit_qty_milli ORDER BY stock_qty_milli ASC, name COLLATE NOCASE ASC")
    List<ProductEntity> listBelowStockLimit();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(ProductEntity product);

    @Update
    int update(ProductEntity product);
}
