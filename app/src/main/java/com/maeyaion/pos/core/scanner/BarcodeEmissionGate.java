package com.maeyaion.pos.core.scanner;

/** Suppresses repeated camera frames for the same code without hiding subsequent scans. */
public final class BarcodeEmissionGate {
    private final long duplicateWindowMs;
    private String lastValue;
    private long lastEmittedAtMs = Long.MIN_VALUE;

    public BarcodeEmissionGate(long duplicateWindowMs) {
        if (duplicateWindowMs < 0) throw new IllegalArgumentException("Window cannot be negative");
        this.duplicateWindowMs = duplicateWindowMs;
    }

    public synchronized boolean shouldEmit(String value, long nowMs) {
        if (value == null || value.trim().isEmpty()) return false;
        if (value.equals(lastValue)) return false;
        lastValue = value;
        lastEmittedAtMs = nowMs;
        return true;
    }

    /** Allows the same physical code to be scanned again after it leaves the camera view. */
    public synchronized void reset() {
        lastValue = null;
        lastEmittedAtMs = Long.MIN_VALUE;
    }
}
