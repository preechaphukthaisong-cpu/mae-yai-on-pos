package com.maeyaion.pos.core.barcode;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.ProductDao;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;

import java.util.Objects;

/** Creates a product and reserves its barcode atomically in one database transaction. */
public final class ProductRegistrationService {
    private final BarcodeAllocator barcodeAllocator;
    private final ProductDao productDao;
    private final TransactionRunner transactions;

    public ProductRegistrationService(@NonNull BarcodeAllocator barcodeAllocator,
            @NonNull ProductDao productDao, @NonNull TransactionRunner transactions) {
        this.barcodeAllocator = Objects.requireNonNull(barcodeAllocator, "barcodeAllocator");
        this.productDao = Objects.requireNonNull(productDao, "productDao");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @NonNull
    public ProductEntity createWithSystemBarcode(@NonNull String sku, @NonNull String name,
            long priceSatang, long costSatang, @NonNull String unit, Long stockLimitQtyMilli,
            @NonNull String createdAtUtc, @NonNull String updatedAtUtc) {
        validateProduct(sku, name, priceSatang, costSatang, unit,
                stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
        return transactions.runInTransaction(() -> {
            BarcodeRegistryEntity barcode = barcodeAllocator.allocateSystemInTransaction(
                    name, priceSatang, createdAtUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, 0L, 1,
                    createdAtUtc, updatedAtUtc);
            productDao.insert(product);
            return product;
        });
    }

    @NonNull
    public ProductEntity createWithManufacturerBarcode(@NonNull String barcodeValue,
            @NonNull String sku, @NonNull String name, long priceSatang, long costSatang,
            @NonNull String unit, Long stockLimitQtyMilli, @NonNull String createdAtUtc,
            @NonNull String updatedAtUtc) {
        validateProduct(sku, name, priceSatang, costSatang, unit,
                stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
        return transactions.runInTransaction(() -> {
            BarcodeRegistryEntity barcode = barcodeAllocator.registerManufacturerInTransaction(
                    barcodeValue, name, priceSatang, createdAtUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, 0L, 1,
                    createdAtUtc, updatedAtUtc);
            productDao.insert(product);
            return product;
        });
    }

    private static void validateProduct(String sku, String name, long priceSatang,
            long costSatang, String unit, Long stockLimitQtyMilli,
            String createdAtUtc, String updatedAtUtc) {
        if (sku == null || sku.trim().isEmpty() || name == null || name.trim().isEmpty()
                || unit == null || unit.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU, product name and unit are required");
        }
        if (priceSatang < 0 || costSatang < 0
                || (stockLimitQtyMilli != null && stockLimitQtyMilli < 0)) {
            throw new IllegalArgumentException("Price, cost and stock limit cannot be negative");
        }
        if (createdAtUtc == null || createdAtUtc.trim().isEmpty()
                || updatedAtUtc == null || updatedAtUtc.trim().isEmpty()) {
            throw new IllegalArgumentException("Creation and update timestamps are required");
        }
    }
}
