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
            ProductEntity replay = findReplay(operationId, "PRODUCT_CREATE_SYSTEM", sku, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
            if (replay != null) return replay;
            BarcodeRegistryEntity barcode = barcodeAllocator.allocateSystemInTransaction(
                    name, priceSatang, createdAtUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, 0L, 1,
                    createdAtUtc, updatedAtUtc);
            persistEvidence(product, operationId, "PRODUCT_CREATE_SYSTEM", createdAtUtc);
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
            ProductEntity replay = findReplay(operationId, "PRODUCT_CREATE_MANUFACTURER", sku, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, createdAtUtc, updatedAtUtc);
            if (replay != null) {
                if (!barcodeValue.equals(replay.barcode)) {
                    throw new IllegalStateException("Idempotency key was already used for a different barcode");
                }
                return replay;
            }
            BarcodeRegistryEntity barcode = barcodeAllocator.registerManufacturerInTransaction(
                    barcodeValue, name, priceSatang, createdAtUtc);
            ProductEntity product = new ProductEntity(sku, barcode.barcode, name,
                    priceSatang, costSatang, unit, stockLimitQtyMilli, 0L, 1,
                    createdAtUtc, updatedAtUtc);
            persistEvidence(product, operationId, "PRODUCT_CREATE_MANUFACTURER", createdAtUtc);
            productDao.insert(product);
            return product;
        });
    }

    private ProductEntity findReplay(String operationId, String expectedOperation, String sku, String name,
            long priceSatang, long costSatang, String unit, Long stockLimitQtyMilli,
            String createdAtUtc, String updatedAtUtc) {
        BackupJournalEntity prior = backupJournalDao.findByOperationId(operationId);
        if (prior == null) return null;
        ProductEntity existing = productDao.findBySku(sku);
        if (!expectedOperation.equals(prior.op) || existing == null
                || !name.equals(existing.name) || priceSatang != existing.priceSatang
                || costSatang != existing.costSatang || !unit.equals(existing.unit)
                || !Objects.equals(stockLimitQtyMilli, existing.stockLimitQtyMilli)
                || !createdAtUtc.equals(existing.createdAtUtc)
                || !updatedAtUtc.equals(existing.updatedAtUtc)
                || !prior.payload.equals(canonicalPayload(existing, expectedOperation))
                || !prior.payload_sha256.equals(sha256(prior.payload))) {
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

    private void persistEvidence(ProductEntity product, String operationId,
            String operation, String createdAtUtc) {
        String payload = canonicalPayload(product, operation);
        String digest = sha256(payload);
        opLogDao.append(new OpLogEntity(UUID.randomUUID().toString(), operation,
                "PRODUCT", product.sku, payload, createdAtUtc));
        backupJournalDao.append(new BackupJournalEntity(0L, operationId, operation,
                payload, digest, 4, createdAtUtc, 0));
    }

    private static String canonicalPayload(ProductEntity product, String operation) {
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendQuoted(json, "v");
        json.append(":1,");
        appendQuoted(json, "op");
        json.append(':');
        appendQuoted(json, operation);
        json.append(',');
        appendQuoted(json, "sku");
        json.append(':');
        appendQuoted(json, product.sku);
        json.append(',');
        appendQuoted(json, "barcode");
        json.append(':');
        appendQuoted(json, product.barcode);
        json.append(',');
        appendQuoted(json, "name");
        json.append(':');
        appendQuoted(json, product.name);
        json.append(',');
        appendQuoted(json, "price_satang");
        json.append(':').append(product.priceSatang).append(',');
        appendQuoted(json, "cost_satang");
        json.append(':').append(product.costSatang).append(',');
        appendQuoted(json, "unit");
        json.append(':');
        appendQuoted(json, product.unit);
        json.append(',');
        appendQuoted(json, "stock_limit_qty_milli");
        json.append(':');
        if (product.stockLimitQtyMilli == null) json.append("null");
        else json.append(product.stockLimitQtyMilli);
        json.append(',');
        appendQuoted(json, "created_at_utc");
        json.append(':');
        appendQuoted(json, product.createdAtUtc);
        json.append(',');
        appendQuoted(json, "updated_at_utc");
        json.append(':');
        appendQuoted(json, product.updatedAtUtc);
        return json.append('}').toString();
    }

    private static void appendQuoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            switch (ch) {
                case 34: out.append((char) 92).append((char) 34); break;
                case 92: out.append((char) 92).append((char) 92); break;
                case 8: out.append((char) 92).append('b'); break;
                case 12: out.append((char) 92).append('f'); break;
                case 10: out.append((char) 92).append('n'); break;
                case 13: out.append((char) 92).append('r'); break;
                case 9: out.append((char) 92).append('t'); break;
                default:
                    if (ch < 0x20) {
                        out.append((char) 92).append('u');
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append((char) ch);
                    }
            }
        }
        out.append('"');
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
