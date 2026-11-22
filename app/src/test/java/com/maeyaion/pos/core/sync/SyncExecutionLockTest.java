package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import org.junit.Test;

public final class SyncExecutionLockTest {
    @Test public void onlyOneSyncPassCanOwnTheProcessLock() {
        assertTrue(SyncExecutionLock.tryAcquire());
        try {
            assertFalse(SyncExecutionLock.tryAcquire());
        } finally {
            SyncExecutionLock.release();
        }
        assertTrue(SyncExecutionLock.tryAcquire());
        SyncExecutionLock.release();
    }
}
