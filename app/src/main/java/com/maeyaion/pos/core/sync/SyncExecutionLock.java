package com.maeyaion.pos.core.sync;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide exclusion for all WorkManager sync requests, regardless of unique-work name. */
final class SyncExecutionLock {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private SyncExecutionLock() {}

    static boolean tryAcquire() {
        return RUNNING.compareAndSet(false, true);
    }

    static void release() {
        if (!RUNNING.compareAndSet(true, false)) {
            throw new IllegalStateException("Sync execution lock was not held");
        }
    }
}
