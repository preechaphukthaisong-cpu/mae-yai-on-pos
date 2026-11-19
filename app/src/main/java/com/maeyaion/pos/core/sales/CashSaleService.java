package com.maeyaion.pos.core.sales;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.BackupJournalDao;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.OpLogDao;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.ProductDao;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.SaleDao;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.db.SaleLineEntity;
import com.maeyaion.pos.core.db.StockLogDao;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 1 single-device cash sale use case. The sale header, immutable line
 * snapshots, stock ledger, audit log and backup journal commit atomically.
 */
public final class CashSaleService {
    private static final int MAX_LINES = 100;
    private static final int SCHEMA_VERSION = 5;

    private final ProductDao products;
    private final SaleDao sales;
    private final StockLogDao stockLogs;
    private final OpLogDao opLogs;
    private final BackupJournalDao journal;
    private final TransactionRunner transactions;

    public CashSaleService(@NonNull ProductDao products, @NonNull SaleDao sales,
            @NonNull StockLogDao stockLogs, @NonNull OpLogDao opLogs,
            @NonNull BackupJournalDao journal, @NonNull TransactionRunner transactions) {
        this.products = Objects.requireNonNull(products, "products");
        this.sales = Objects.requireNonNull(sales, "sales");
        this.stockLogs = Objects.requireNonNull(stockLogs, "stockLogs");
        this.opLogs = Objects.requireNonNull(opLogs, "opLogs");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @NonNull
    public SaleEntity createCashSale(@NonNull String idempotencyKey,
            @NonNull List<LineInput> requestedLines, long cashReceivedSatang,
            @NonNull String createdAtUtc) {
        validateUuid(idempotencyKey);
        validateTimestamp(createdAtUtc);
        if (cashReceivedSatang < 0) throw new IllegalArgumentException("Cash received cannot be negative");
        if (requestedLines == null || requestedLines.isEmpty() || requestedLines.size() > MAX_LINES) {
            throw new IllegalArgumentException("A sale must contain between 1 and 100 lines");
        }
        List<LineInput> lines = new ArrayList<>(requestedLines);
        Set<String> seenSkus = new HashSet<>();
        for (LineInput line : lines) {
            if (line == null || line.sku == null || line.sku.trim().isEmpty()
                    || !line.sku.equals(line.sku.trim()) || line.qtyMilli <= 0
                    || !seenSkus.add(line.sku)) {
                throw new IllegalArgumentException("Sale lines require unique SKUs and positive quantities");
            }
        }
        String requestHash = hashRequest(lines, cashReceivedSatang);

        return transactions.runInTransaction(() -> {
            SaleEntity replay = sales.findByIdempotencyKey(idempotencyKey);
            if (replay != null) {
                if (!requestHash.equals(replay.contentHash)) {
                    throw new IllegalStateException("Idempotency key was reused for a different cash sale");
                }
                verifyReplayEvidence(replay);
                return replay;
            }

            List<ProductEntity> selectedProducts = new ArrayList<>(lines.size());
            List<Long> lineTotals = new ArrayList<>(lines.size());
            long subtotal = 0L;
            for (LineInput line : lines) {
                ProductEntity product = products.findBySku(line.sku);
                if (product == null || product.isActive != 1) {
                    throw new IllegalStateException("Product is missing or inactive: " + line.sku);
                }
                if (product.stockQtyMilli < line.qtyMilli) {
                    throw new InsufficientStockException(line.sku, product.stockQtyMilli, line.qtyMilli);
                }
                long lineTotal = calculateLineTotal(product.priceSatang, line.qtyMilli);
                subtotal = Math.addExact(subtotal, lineTotal);
                selectedProducts.add(product);
                lineTotals.add(lineTotal);
            }
            if (cashReceivedSatang < subtotal) {
                throw new InsufficientCashException(subtotal, cashReceivedSatang);
            }
            long change = Math.subtractExact(cashReceivedSatang, subtotal);
            String saleId = UUID.randomUUID().toString();
            SaleEntity sale = new SaleEntity(saleId, idempotencyKey, "CASH", "PAID",
                    "COMPLETED", null, subtotal, 0L, subtotal, cashReceivedSatang,
                    change, requestHash, createdAtUtc, createdAtUtc, null);
            sales.insertSale(sale);

            List<SaleLineEntity> saleLines = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                LineInput input = lines.get(i);
                ProductEntity product = selectedProducts.get(i);
                long lineTotal = lineTotals.get(i);
                saleLines.add(new SaleLineEntity(UUID.randomUUID().toString(), saleId, i + 1,
                        product.sku, product.name, product.priceSatang, product.costSatang,
                        input.qtyMilli, lineTotal));
            }
            sales.insertLines(saleLines);

            for (int i = 0; i < lines.size(); i++) {
                ProductEntity product = selectedProducts.get(i);
                long after = Math.subtractExact(product.stockQtyMilli, lines.get(i).qtyMilli);
                stockLogs.append(new StockLogEntity(UUID.randomUUID().toString(), product.sku,
                        product.stockQtyMilli, after, -lines.get(i).qtyMilli,
                        "SALE", saleId, createdAtUtc));
            }

            String payload = canonicalPayload(sale, saleLines);
            String payloadHash = sha256(payload);
            opLogs.append(new OpLogEntity(UUID.randomUUID().toString(), "SALE_CASH_CREATE",
                    "SALE", saleId, payload, createdAtUtc));
            journal.append(new BackupJournalEntity(0L, idempotencyKey, "SALE_CASH_CREATE",
                    payload, payloadHash, SCHEMA_VERSION, createdAtUtc, 0));
            return sale;
        });
    }

    private void verifyReplayEvidence(SaleEntity sale) {
        BackupJournalEntity entry = journal.findByOperationId(sale.idemKey);
        if (entry == null || !"SALE_CASH_CREATE".equals(entry.op)
                || entry.schema_version != SCHEMA_VERSION
                || !entry.payload_sha256.equals(sha256(entry.payload))
                || !entry.payload.equals(canonicalPayload(sale, sales.linesForSale(sale.id)))) {
            throw new IllegalStateException("Cash sale idempotency evidence is missing or inconsistent");
        }
    }

    static long calculateLineTotal(long priceSatang, long qtyMilli) {
        if (priceSatang < 0 || qtyMilli <= 0) {
            throw new IllegalArgumentException("Price must be non-negative and quantity positive");
        }
        BigInteger numerator = BigInteger.valueOf(priceSatang).multiply(BigInteger.valueOf(qtyMilli));
        BigInteger[] parts = numerator.divideAndRemainder(BigInteger.valueOf(1_000L));
        if (parts[1].multiply(BigInteger.valueOf(2L)).compareTo(BigInteger.valueOf(1_000L)) >= 0) {
            parts[0] = parts[0].add(BigInteger.ONE);
        }
        if (parts[0].compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new ArithmeticException("Sale line total exceeds supported satang range");
        }
        return parts[0].longValueExact();
    }

    private static String hashRequest(List<LineInput> lines, long cashReceived) {
        StringBuilder canonical = new StringBuilder("CASH|").append(cashReceived).append('|');
        for (LineInput line : lines) {
            canonical.append(line.sku.length()).append(':').append(line.sku)
                    .append(':').append(line.qtyMilli).append('|');
        }
        return sha256(canonical.toString());
    }

    private static String canonicalPayload(SaleEntity sale, List<SaleLineEntity> lines) {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"v\":1,\"op\":\"SALE_CASH_CREATE\",\"sale_id\":");
        quote(json, sale.id);
        json.append(",\"idempotency_key\":");
        quote(json, sale.idemKey);
        json.append(",\"subtotal_satang\":").append(sale.subtotalSatang)
                .append(",\"cash_received_satang\":").append(sale.cashReceivedSatang)
                .append(",\"change_satang\":").append(sale.changeSatang)
                .append(",\"created_at_utc\":");
        quote(json, sale.createdAtUtc);
        json.append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) json.append(',');
            SaleLineEntity line = lines.get(i);
            json.append("{\"line_no\":").append(line.lineNo).append(",\"sku\":");
            quote(json, line.sku);
            json.append(",\"name\":");
            quote(json, line.nameSnap);
            json.append(",\"price_satang\":").append(line.priceSnapSatang)
                    .append(",\"cost_satang\":").append(line.costSnapSatang)
                    .append(",\"qty_milli\":").append(line.qtyMilli)
                    .append(",\"line_total_satang\":").append(line.lineTotalSatang).append('}');
        }
        return json.append("]}").toString();
    }

    private static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') out.append('\\').append(ch);
            else if (ch < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
            else out.append(ch);
        }
        out.append('"');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validateUuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("Canonical UUID idempotency key required");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Canonical UUID idempotency key required", invalid);
        }
    }

    private static void validateTimestamp(String value) {
        try {
            Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new IllegalArgumentException("Creation timestamp must be ISO-8601 UTC", invalid);
        }
    }

    public static final class LineInput {
        public final String sku;
        public final long qtyMilli;
        public LineInput(String sku, long qtyMilli) {
            this.sku = sku;
            this.qtyMilli = qtyMilli;
        }
    }

    public static final class InsufficientStockException extends IllegalStateException {
        public final String sku;
        public final long availableQtyMilli;
        public final long requestedQtyMilli;
        InsufficientStockException(String sku, long available, long requested) {
            super("Insufficient stock for " + sku);
            this.sku = sku; this.availableQtyMilli = available; this.requestedQtyMilli = requested;
        }
    }

    public static final class InsufficientCashException extends IllegalArgumentException {
        public final long totalSatang;
        public final long receivedSatang;
        InsufficientCashException(long total, long received) {
            super("Cash received is less than the sale total");
            this.totalSatang = total; this.receivedSatang = received;
        }
    }
}
