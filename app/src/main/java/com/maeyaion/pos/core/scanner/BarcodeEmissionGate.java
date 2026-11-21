package com.maeyaion.pos.core.scanner;

import java.util.HashSet;
import java.util.Set;

/** Suppresses repeated camera frames for every code currently visible to the camera. */
public final class BarcodeEmissionGate {
    private final Set<String> emittedWhileVisible = new HashSet<>();

    public BarcodeEmissionGate(long duplicateWindowMs) {
        if (duplicateWindowMs < 0) throw new IllegalArgumentException("Window cannot be negative");
    }

    public synchronized boolean shouldEmit(String value, long nowMs) {
        if (value == null || value.trim().isEmpty()) return false;
        return emittedWhileVisible.add(value);
    }

    /** Allows visible codes to be scanned again after the camera has seen no barcode. */
    public synchronized void reset() {
        emittedWhileVisible.clear();
    }
}
