package com.maeyaion.pos.core.tx;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes all application write transactions with maintenance operations
 * such as WAL checkpoint + database snapshot. Every write must use
 * RoomTransactionRunner; bypassing this gate invalidates snapshot guarantees.
 */
public final class DatabaseMaintenanceGate {
    private static final ReentrantLock LOCK = new ReentrantLock(true);

    private DatabaseMaintenanceGate() {}

    public static <T> T withExclusiveAccess(Callable<T> operation) throws Exception {
        LOCK.lock();
        try {
            return operation.call();
        } finally {
            LOCK.unlock();
        }
    }
}
