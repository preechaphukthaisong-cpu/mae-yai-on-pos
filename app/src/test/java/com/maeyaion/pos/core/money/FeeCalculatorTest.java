package com.maeyaion.pos.core.money;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
public final class FeeCalculatorTest {
    @Test public void calculatesFivePercentFromWholeBill() {
        assertEquals(5025L, FeeCalculator.fivePercentHalfUp(Satang.of(100500L)).value());
    }
    @Test public void roundsHalfUpAtHalfSatang() {
        assertEquals(1L, FeeCalculator.fivePercentHalfUp(Satang.of(10L)).value());
        assertEquals(0L, FeeCalculator.fivePercentHalfUp(Satang.of(9L)).value());
        assertEquals(1L, FeeCalculator.fivePercentHalfUp(Satang.of(11L)).value());
    }
    @Test public void zeroSubtotalHasZeroFee() {
        assertEquals(0L, FeeCalculator.fivePercentHalfUp(Satang.ZERO).value());
    }
    @Test public void rejectsNegativeSubtotal() {
        try { FeeCalculator.fivePercentHalfUp(Satang.of(-1L)); fail("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void usesCheckedArithmeticForSatangAddition() {
        try { Satang.of(Long.MAX_VALUE).plus(Satang.of(1L)); fail("Expected ArithmeticException"); }
        catch (ArithmeticException expected) { }
    }
}
