package com.maeyaion.pos.core.money;

import androidx.annotation.NonNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure, overflow-safe POS bill calculation. All money is satang; quantity is milli-unit. */
public final class SaleCalculator {
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);
    private SaleCalculator() {}

    public enum SaleType { CASH, CREDIT }

    public static final class CartLine {
        public final String sku;
        public final String name;
        public final long priceSatang;
        public final long costSatang;
        public final long quantityMilli;

        public CartLine(@NonNull String sku, @NonNull String name, long priceSatang,
                long costSatang, long quantityMilli) {
            this.sku = requireText(sku, "sku");
            this.name = requireText(name, "name");
            if (priceSatang < 0 || costSatang < 0 || quantityMilli <= 0) {
                throw new IllegalArgumentException("Price/cost must be non-negative and quantity must be positive");
            }
            this.priceSatang = priceSatang;
            this.costSatang = costSatang;
            this.quantityMilli = quantityMilli;
        }
    }

    public static final class LineTotal {
        public final String sku;
        public final String nameSnapshot;
        public final long priceSnapshotSatang;
        public final long costSnapshotSatang;
        public final long quantityMilli;
        public final long lineTotalSatang;

        private LineTotal(CartLine line, long total) {
            sku = line.sku; nameSnapshot = line.name;
            priceSnapshotSatang = line.priceSatang; costSnapshotSatang = line.costSatang;
            quantityMilli = line.quantityMilli; lineTotalSatang = total;
        }
    }

    public static final class Result {
        public final SaleType type;
        public final List<LineTotal> lines;
        public final long subtotalSatang;
        public final long feeSatang;
        public final long totalSatang;
        public final Long cashReceivedSatang;
        public final Long changeSatang;

        private Result(SaleType type, List<LineTotal> lines, long subtotal, long fee,
                long total, Long received, Long change) {
            this.type = type;
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            subtotalSatang = subtotal; feeSatang = fee; totalSatang = total;
            cashReceivedSatang = received; changeSatang = change;
        }
    }

    /** Calculates a complete bill. CREDIT requires a customer in the persistence service. */
    @NonNull
    public static Result calculate(@NonNull List<CartLine> cart, @NonNull SaleType type,
            Long cashReceivedSatang) {
        Objects.requireNonNull(cart, "cart");
        Objects.requireNonNull(type, "type");
        if (cart.isEmpty()) throw new IllegalArgumentException("A sale must contain at least one item");

        Map<String, CartLine> unique = new LinkedHashMap<>();
        for (CartLine line : cart) {
            Objects.requireNonNull(line, "cart line");
            CartLine previous = unique.putIfAbsent(line.sku, line);
            if (previous != null) {
                if (!previous.name.equals(line.name) || previous.priceSatang != line.priceSatang
                        || previous.costSatang != line.costSatang) {
                    throw new IllegalArgumentException("Conflicting snapshots for duplicate SKU: " + line.sku);
                }
                long mergedQty = Math.addExact(previous.quantityMilli, line.quantityMilli);
                unique.put(line.sku, new CartLine(line.sku, line.name, line.priceSatang,
                        line.costSatang, mergedQty));
            }
        }

        List<LineTotal> totals = new ArrayList<>(unique.size());
        long subtotal = 0L;
        for (CartLine line : unique.values()) {
            long lineTotal = multiplyDivideHalfUp(line.priceSatang, line.quantityMilli, 1_000L);
            subtotal = Math.addExact(subtotal, lineTotal);
            totals.add(new LineTotal(line, lineTotal));
        }

        long fee = type == SaleType.CREDIT ? FeeCalculator.fivePercentHalfUp(Satang.of(subtotal)).value() : 0L;
        long total = Math.addExact(subtotal, fee);
        if (type == SaleType.CASH) {
            if (cashReceivedSatang == null || cashReceivedSatang < total) {
                throw new IllegalArgumentException("Cash received must be at least the bill total");
            }
            long change = Math.subtractExact(cashReceivedSatang, total);
            return new Result(type, totals, subtotal, fee, total, cashReceivedSatang, change);
        }
        if (cashReceivedSatang != null) {
            throw new IllegalArgumentException("Credit sales do not accept cash received");
        }
        return new Result(type, totals, subtotal, fee, total, null, null);
    }

    /** Half-up integer rounding of (a*b)/divisor without intermediate long overflow. */
    static long multiplyDivideHalfUp(long a, long b, long divisor) {
        if (a < 0 || b < 0 || divisor <= 0) throw new IllegalArgumentException("Invalid arithmetic operands");
        BigInteger numerator = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
        BigInteger[] qr = numerator.divideAndRemainder(BigInteger.valueOf(divisor));
        BigInteger rounded = qr[1].shiftLeft(1).compareTo(BigInteger.valueOf(divisor)) >= 0
                ? qr[0].add(BigInteger.ONE) : qr[0];
        if (rounded.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new ArithmeticException("long overflow");
        }
        return rounded.longValue();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
