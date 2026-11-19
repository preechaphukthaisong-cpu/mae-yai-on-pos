package com.maeyaion.pos.core.scanner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BarcodeEmissionGateTest {
    @Test public void suppressesRepeatedFramesButAllowsNewScansAndLaterRescan() {
        BarcodeEmissionGate gate = new BarcodeEmissionGate(1_000L);
        assertTrue(gate.shouldEmit("2000001", 10_000L));
        assertFalse(gate.shouldEmit("2000001", 10_250L));
        assertTrue(gate.shouldEmit("2000002", 10_300L));
        assertTrue(gate.shouldEmit("2000001", 11_400L));
    }

    @Test public void rejectsNullAndBlankValues() {
        BarcodeEmissionGate gate = new BarcodeEmissionGate(1_000L);
        assertFalse(gate.shouldEmit(null, 1L));
        assertFalse(gate.shouldEmit("   ", 2L));
        assertTrue(gate.shouldEmit("SKU-1", 3L));
    }
}
