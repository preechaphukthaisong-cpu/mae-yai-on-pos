package com.maeyaion.pos.core.money;
import java.util.Objects;
public final class FeeCalculator {
    private FeeCalculator() {}
    /** 5% of the whole bill subtotal, rounded once using Half Up with integer arithmetic. */
    public static Satang fivePercentHalfUp(Satang subtotal) {
        Objects.requireNonNull(subtotal, "subtotal");
        long amount = subtotal.value();
        if (amount < 0L) throw new IllegalArgumentException("Subtotal cannot be negative");
        long quotient = amount / 20L;
        long remainder = amount % 20L;
        long rounded = remainder >= 10L ? Math.addExact(quotient, 1L) : quotient;
        return Satang.of(rounded);
    }
}
