package com.maeyaion.pos.core.inventory;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.barcode.BarcodeAllocator;
import com.maeyaion.pos.core.db.BackupJournalDao;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.OpLogDao;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.ProductDao;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.StockLogDao;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Transactional inventory operations. All quantities use thousandths; all money uses satang. */
public final class InventoryService {
    private static final int SCHEMA_VERSION = 5;
    private final BarcodeAllocator barcodes;
    private final ProductDao products;
    private final StockLogDao stockLogs;
    private final OpLogDao opLogs;
    private final BackupJournalDao backupJournal;
    private final TransactionRunner transactions;

    public InventoryService(@NonNull BarcodeAllocator barcodes, @NonNull ProductDao products,
            @NonNull StockLogDao stockLogs, @NonNull OpLogDao opLogs,
            @NonNull BackupJournalDao backupJournal, @NonNull TransactionRunner transactions) {
        this.barcodes = Objects.requireNonNull(barcodes);
        this.products = Objects.requireNonNull(products);
        this.stockLogs = Objects.requireNonNull(stockLogs);
        this.opLogs = Objects.requireNonNull(opLogs);
        this.backupJournal = Objects.requireNonNull(backupJournal);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /** A new product, its generated barcode, opening receipt and audit evidence commit atomically. */
    @NonNull
    public ProductEntity createAndReceive(@NonNull String operationId, @NonNull String sku,
            @NonNull String name, long priceSatang, long costSatang, @NonNull String unit,
            Long stockLimitQtyMilli, long receivedQtyMilli, @NonNull String nowUtc) {
        validateOperation(operationId);
        validateProduct(sku, name, priceSatang, costSatang, unit, stockLimitQtyMilli, receivedQtyMilli, nowUtc);
        if (receivedQtyMilli <= 0) throw new IllegalArgumentException("Received quantity must be greater than zero");
        return transactions.runInTransaction(() -> {
            if (backupJournal.findByOperationId(operationId) != null) {
                throw new IllegalStateException("Operation id has already been used");
            }
            BarcodeRegistryEntity barcode = barcodes.allocateSystemInTransaction(name, priceSatang, nowUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name, priceSatang,
                    costSatang, unit, stockLimitQtyMilli, 0L, 1, nowUtc, nowUtc);
            products.insert(product);
            appendOperation(operationId, "INVENTORY_PRODUCT_CREATE", "PRODUCT", sku,
                    "{\"sku\":\"" + escape(sku) + "\",\"barcode\":\"" + escape(barcode.barcode)
                            + "\",\"name\":\"" + escape(name) + "\",\"price_satang\":" + priceSatang
                            + ",\"cost_satang\":" + costSatang + ",\"unit\":\"" + escape(unit)
                            + "\",\"stock_limit_qty_milli\":" + (stockLimitQtyMilli == null ? "null" : stockLimitQtyMilli) + "}", nowUtc);
            appendStock(UUID.randomUUID().toString(), product, receivedQtyMilli, "RECEIVE", nowUtc);
            return products.findBySku(sku);
        });
    }

    /** Receives stock for an existing barcode. */
    @NonNull
    public ProductEntity receive(@NonNull String operationId, @NonNull String barcode,
            long quantityMilli, @NonNull String nowUtc) {
        validateOperation(operationId);
        validateTimestamp(nowUtc);
        if (barcode.trim().isEmpty() || quantityMilli <= 0) throw new IllegalArgumentException("Barcode and positive quantity are required");
        return transactions.runInTransaction(() -> {
            if (backupJournal.findByOperationId(operationId) != null) {
                throw new IllegalStateException("Operation id has already been used");
            }
            ProductEntity product = products.findByBarcode(barcode);
            if (product == null || product.isActive != 1) throw new IllegalArgumentException("PRODUCT_NOT_FOUND");
            long after = Math.addExact(product.stockQtyMilli, quantityMilli);
            appendStock(operationId, product, quantityMilli, "RECEIVE", nowUtc);
            return products.findBySku(product.sku);
        });
    }

    /** Stock count replaces the on-hand balance immediately and preserves a COUNT_REPLACE ledger entry. */
    @NonNull
    public ProductEntity replaceCount(@NonNull String operationId, @NonNull String barcode,
            long countedQtyMilli, @NonNull String nowUtc) {
        validateOperation(operationId);
        validateTimestamp(nowUtc);
        if (barcode.trim().isEmpty() || countedQtyMilli < 0) throw new IllegalArgumentException("Barcode and non-negative count are required");
        return transactions.runInTransaction(() -> {
            if (backupJournal.findByOperationId(operationId) != null) {
                throw new IllegalStateException("Operation id has already been used");
            }
            ProductEntity product = products.findByBarcode(barcode);
            if (product == null || product.isActive != 1) throw new IllegalArgumentException("PRODUCT_NOT_FOUND");
            long diff = Math.subtractExact(countedQtyMilli, product.stockQtyMilli);
            appendStock(operationId, product, diff, "COUNT_REPLACE", nowUtc);
            return products.findBySku(product.sku);
        });
    }

    public void updateStockLimit(@NonNull String operationId, @NonNull String sku,
            Long stockLimitQtyMilli, @NonNull String nowUtc) {
        validateOperation(operationId);
        validateTimestamp(nowUtc);
        if (stockLimitQtyMilli != null && stockLimitQtyMilli < 0) throw new IllegalArgumentException("Stock limit cannot be negative");
        transactions.runInTransaction(() -> {
            if (backupJournal.findByOperationId(operationId) != null) {
                throw new IllegalStateException("Operation id has already been used");
            }
            ProductEntity current = products.findBySku(sku);
            if (current == null || current.isActive != 1) throw new IllegalArgumentException("PRODUCT_NOT_FOUND");
            ProductEntity updated = new ProductEntity(current.sku, current.barcode, current.name,
                    current.priceSatang, current.costSatang, current.unit, stockLimitQtyMilli,
                    current.stockQtyMilli, current.isActive, current.createdAtUtc, nowUtc);
            products.update(updated);
            String payload = "{\"sku\":\"" + escape(sku) + "\",\"stock_limit_qty_milli\":"
                    + (stockLimitQtyMilli == null ? "null" : stockLimitQtyMilli) + "}";
            appendOperation(operationId, "INVENTORY_STOCK_LIMIT_UPDATE", "PRODUCT", sku, payload, nowUtc);
            return null;
        });
    }

    @NonNull public List<ProductEntity> allProducts() { return products.listActiveByStockAscending(); }
    @NonNull public List<ProductEntity> belowStockLimit() { return products.listBelowStockLimit(); }
    public ProductEntity findByBarcode(@NonNull String barcode) {
        return products.findByBarcode(barcode);
    }
    @NonNull public List<StockLogEntity> history(@NonNull String sku) {
        return stockLogs.historyForSku(sku);
    }

    private void appendStock(String operationId, ProductEntity product, long diff,
            String reason, String nowUtc) {
        long after = Math.addExact(product.stockQtyMilli, diff);
        if (after < 0) throw new IllegalArgumentException("Stock cannot be negative");
        StockLogEntity log = new StockLogEntity(UUID.randomUUID().toString(), product.sku,
                product.stockQtyMilli, after, diff, reason, operationId, nowUtc);
        stockLogs.append(log);
        String payload = "{\"sku\":\"" + escape(product.sku) + "\",\"before_qty_milli\":"
                + product.stockQtyMilli + ",\"after_qty_milli\":" + after + ",\"diff_qty_milli\":"
                + diff + ",\"reason\":\"" + reason + "\"}";
        appendOperation(operationId, "INVENTORY_STOCK_" + reason, "PRODUCT", product.sku, payload, nowUtc);
    }

    private void appendOperation(String operationId, String action, String entityType,
            String entityId, String payload, String nowUtc) {
        opLogs.append(new OpLogEntity(UUID.randomUUID().toString(), action, entityType,
                entityId, payload, nowUtc));
        backupJournal.append(new BackupJournalEntity(0L, operationId, action, payload,
                sha256(payload), SCHEMA_VERSION, nowUtc, 0));
    }

    private static void validateProduct(String sku, String name, long price, long cost,
            String unit, Long limit, long quantity, String nowUtc) {
        if (sku.trim().isEmpty() || name.trim().isEmpty() || unit.trim().isEmpty()
                || price < 0 || cost < 0 || (limit != null && limit < 0) || quantity < 0) {
            throw new IllegalArgumentException("Invalid product details");
        }
        validateTimestamp(nowUtc);
    }
    private static void validateOperation(String id) {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("A canonical UUID operation id is required", invalid);
        }
    }
    private static void validateTimestamp(String value) {
        try { Instant.parse(value); }
        catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Timestamp must be ISO-8601 UTC", invalid);
        }
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
