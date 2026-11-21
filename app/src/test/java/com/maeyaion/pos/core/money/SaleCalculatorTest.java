package com.maeyaion.pos.core.money;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class SaleCalculatorTest {
    @Test public void cashBillRoundsEachLineAndCalculatesChange() {
        SaleCalculator.Result result = SaleCalculator.calculate(
                Collections.singletonList(new SaleCalculator.CartLine("SKU-1", "น้ำ", 1L, 0L, 1_500L)),
                SaleCalculator.SaleType.CASH, 2L);
        assertEquals(2L, result.subtotalSatang);
        assertEquals(0L, result.feeSatang);
        assertEquals(2L, result.totalSatang);
        assertEquals(Long.valueOf(2L), result.cashReceivedSatang);
        assertEquals(Long.valueOf(0L), result.changeSatang);
    }

    @Test public void creditFeeIsFivePercentOfWholeSubtotalRoundedHalfUp() {
        SaleCalculator.Result result = SaleCalculator.calculate(
                Arrays.asList(
                        new SaleCalculator.CartLine("A", "A", 101L, 40L, 1_000L),
                        new SaleCalculator.CartLine("B", "B", 99L, 30L, 1_000L)),
                SaleCalculator.SaleType.CREDIT, null);
        assertEquals(200L, result.subtotalSatang);
        assertEquals(10L, result.feeSatang);
        assertEquals(210L, result.totalSatang);
        assertNull(result.cashReceivedSatang);
        assertNull(result.changeSatang);
    }

    @Test public void duplicateSkuIsMergedBeforeSnapshotting() {
        SaleCalculator.Result result = SaleCalculator.calculate(
                Arrays.asList(
                        new SaleCalculator.CartLine("A", "Apple", 100L, 50L, 500L),
                        new SaleCalculator.CartLine("A", "Apple", 100L, 50L, 500L)),
                SaleCalculator.SaleType.CASH, 100L);
        assertEquals(1, result.lines.size());
        assertEquals(1_000L, result.lines.get(0).quantityMilli);
        assertEquals(100L, result.subtotalSatang);
    }

    @Test public void rejectsInsufficientCash() {
        try {
            SaleCalculator.calculate(
                    Collections.singletonList(new SaleCalculator.CartLine("A", "A", 101L, 0L, 1_000L)),
                    SaleCalculator.SaleType.CASH, 100L);
            fail("Expected insufficient cash to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals("Cash received must be at least the bill total", expected.getMessage());
        }
    }

    @Test public void arithmeticDoesNotOverflowIntermediateMultiplication() {
        long rounded = SaleCalculator.multiplyDivideHalfUp(Long.MAX_VALUE / 2L, 2L, 1L);
        assertEquals(Long.MAX_VALUE - 1L, rounded);
    }
}
