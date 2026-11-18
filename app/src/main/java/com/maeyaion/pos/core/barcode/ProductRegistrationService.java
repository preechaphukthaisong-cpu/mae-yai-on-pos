package com.maeyaion.pos.core.barcode;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.BackupJournalDao;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.OpLogDao;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.ProductDao;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates a product and reserves its barcode atomically in one database transaction. */
public final class ProductRegistrationService {
    private final BarcodeAllocator barcodeAllocator;
    private final ProductDao productDao;
    private final OpLogDao opLogDao;
    private final BackupJournalDao backupJournalDao;
    private final TransactionRunner transactions;

    public ProductRegistrationService(@NonNull BarcodeAllocator barcodeAllocator,
            @NonNull ProductDao productDao, @NonNull OpLogDao opLogDao,
            @NonNull BackupJournalDao backupJournalDao, @NonNull TransactionRunner transactions) {
        this.barcodeAllocator = Objects.requireNonNull(barcodeAllocator, "barcodeAllocator");
        this.productDao = Objects.requireNonNull(productDao, "productDao");
        this.opLogDao = Objects.requireNonNull(opLogDao, "opLogDao");
        this.backupJournalDao = Objects.requireNonNull(backupJournalDao, "backupJournalDao");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @NonNull
    public ProductEntity createWithSystemBarcode(@NonNull String operationId,
            @NonNull String sku, @NonNull String name,
            long priceSatang, long costSatang, @NonNull String unit, Long stockLimitQtyMilli,
            @NonNull String createdAtUtc, @NonNull String updatedAtUtc) {
        validateOperationId(operationId);
        validateProduct(sku, name, priceSatang, costSatang, unit,
                stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
        return transactions.runInTransaction(() -> {
            ProductEntity replay = findReplay(operationId, sku, name, priceSatang,
                    costSatang, unit, stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
            if (replay != null) return replay;
            BarcodeRegistryEntity barcode = barcodeAllocator.allocateSystemInTransaction(
                    name, priceSatang, createdAtUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, 0L, 1,
                    createdAtUtc, updatedAtUtc);
            persistEvidence(product, operationId, createdAtUtc);
            productDao.insert(product);
            return product;
        });
    }

    @NonNull
    public ProductEntity createWithManufacturerBarcode(@NonNull String operationId,
            @NonNull String barcodeValue, @NonNull String sku, @NonNull String name, long priceSatang, long costSatang,
            @NonNull String unit, Long stockLimitQtyMilli, @NonNull String createdAtUtc,
            @NonNull String updatedAtUtc) {
        validateOperationId(operationId);
        validateProduct(sku, name, priceSatang, costSatang, unit,
                stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
        return transactions.runInTransaction(() -> {
            ProductEntity replay = findReplay(operationId, sku, name, priceSatang,
                    costSatang, unit, stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
            if (replay != null) return replay;
            BarcodeRegistryEntity barcode = barcodeAllocator.registerManufacturerInTransaction(
                    barcodeValue, name, priceSatang, createdAtUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, 0L, 1,
                    createdAtUtc, updatedAtUtc);
            persistEvidence(product, operationId, createdAtUtc);
            productDao.insert(product);
            return product;
        });
    }

    private ProductEntity findReplay(String operationId, String sku, String name,
            long priceSatang, long costSatang, String unit, Long stockLimitQtyMilli,
            String createdAtUtc, String updatedAtUtc) {
        BackupJournalEntity prior = backupJournalDao.findByOperationId(operationId);
        if (prior == null) return null;
        ProductEntity existing = productDao.findBySku(sku);
        if (!"PRODUCT_CREATE".equals(prior.op) || existing == null
                || !name.equals(existing.name) || priceSatang != existing.priceSatang
                || costSatang != existing.costSatang || !unit.equals(existing.unit)
                || !Objects.equals(stockLimitQtyMilli, existing.stockLimitQtyMilli)
                || !createdAtUtc.equals(existing.createdAtUtc)
                || !updatedAtUtc.equals(existing.updatedAtUtc)
                || !prior.payload.equals(canonicalPayload(existing))) {
            throw new IllegalStateException("Idempotency key was already used for a different operation");
        }
        return existing;
    }

    private static void validateOperationId(String operationId) {
        try {
            if (operationId == null || !UUID.fromString(operationId).toString().equals(operationId)) {
                throw new IllegalArgumentException("A canonical UUID operation id is required");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("A canonical UUID operation id is required", invalid);
        }
    }

    private void persistEvidence(ProductEntity product, String operationId, String createdAtUtc) {
        String payload = canonicalPayload(product);
        String digest = sha256(payload);
        opLogDao.append(new OpLogEntity(UUID.randomUUID().toString(), "PRODUCT_CREATE",
                "PRODUCT", product.sku, payload, createdAtUtc));
        backupJournalDao.append(new BackupJournalEntity(0L, operationId, "PRODUCT_CREATE",
                payload, digest, 4, createdAtUtc, 0));
    }

    private static String canonicalPayload(ProductEntity product) {
        return "{\\"v\\":1,\\"op\\":\\"PRODUCT_CREATE\\","
                + "\\"sku\\":\\"" + jsonEscape(product.sku) + "\\","
                + "\\"barcode\\":\\"" + jsonEscape(product.barcode) + "\\","
                + "\\"name\\":\\"" + jsonEscape(product.name) + "\\","
                + "\\"price_satang\\":" + product.priceSatang + ","
                + "\\"cost_satang\\":" + product.costSatang + ","
                + "\\"unit\\":\\"" + jsonEscape(product.unit) + "\\","
                + "\\"stock_limit_qty_milli\\":"
                + (product.stockLimitQtyMilli == null ? "null" : product.stockLimitQtyMilli) + ","
                + "\\"created_at_utc\\":\\"" + jsonEscape(product.createdAtUtc) + "\\","
                + "\\"updated_at_utc\\":\\"" + jsonEscape(product.updatedAtUtc) + "\\"}";
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\\b': out.append("\\b"); break;
                case '\\f': out.append("\\f"); break;
                case '\\n': out.append("\\n"); break;
                case '\\r': out.append("\\r"); break;
                case '\\t': out.append("\\t"); break;
                default:
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
            }
        }
        return out.toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format("%02x", item & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
