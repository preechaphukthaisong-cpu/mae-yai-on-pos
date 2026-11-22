package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class StoreKeyManagerTest {
    @Test public void initializesIdempotentlyAndPersistsKeyAcrossManagerInstances() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storeId = "test_store_" + System.nanoTime();
        StoreKeyManager first = new StoreKeyManager(context);
        assertEquals(1L, first.initializeStore(storeId));
        byte[] original = first.getStoreKey(storeId, 1L);
        assertEquals(32, original.length);

        StoreKeyManager second = new StoreKeyManager(context);
        assertEquals(1L, second.initializeStore(storeId));
        byte[] restored = second.getStoreKey(storeId, 1L);
        assertArrayEquals(original, restored);
        Arrays.fill(original, (byte) 0);
        Arrays.fill(restored, (byte) 0);
    }

    @Test public void rotationAdvancesEpochAndRetainsHistoricalKeys() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storeId = "rotation_store_" + System.nanoTime();
        StoreKeyManager manager = new StoreKeyManager(context);
        manager.initializeStore(storeId);
        byte[] epochOne = manager.getStoreKey(storeId, 1L);

        assertEquals(2L, manager.rotateStoreKey(storeId));
        assertEquals(2L, manager.getCurrentEpoch(storeId));
        assertEquals(2L, manager.getPendingRotationEpoch(storeId));
        StoreKeyManager restarted = new StoreKeyManager(context);
        assertEquals(2L, restarted.getPendingRotationEpoch(storeId));
        byte[] epochTwo = manager.getStoreKey(storeId, 2L);
        byte[] historical = manager.getStoreKey(storeId, 1L);

        assertEquals(32, epochTwo.length);
        assertArrayEquals(epochOne, historical);
        assertFalse(Arrays.equals(epochOne, epochTwo));
        try {
            manager.getStoreKey(storeId, 3L);
            fail("Future key epoch must not be returned");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("Unknown"));
        }
        manager.confirmRotation(storeId, 2L);
        assertEquals(0L, manager.getPendingRotationEpoch(storeId));
        Arrays.fill(epochOne, (byte) 0);
        Arrays.fill(epochTwo, (byte) 0);
        Arrays.fill(historical, (byte) 0);
    }

    @Test public void historicalEpochCanBeRestoredWithoutRollingBackCurrentEpoch() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storeId = "history_store_" + System.nanoTime();
        StoreKeyManager manager = new StoreKeyManager(context);
        manager.initializeStore(storeId);
        byte[] epochOne = manager.getStoreKey(storeId, 1L);
        manager.rotateStoreKey(storeId);
        context.getSharedPreferences("maeyai_store_keys_v1", Context.MODE_PRIVATE)
                .edit().remove("store_key_" + storeId + "_1").commit();

        manager.importStoreKey(storeId, 1L, epochOne);
        assertEquals(2L, manager.getCurrentEpoch(storeId));
        assertArrayEquals(epochOne, manager.getStoreKey(storeId, 1L));
        Arrays.fill(epochOne, (byte) 0);
    }

    @Test public void rejectsInvalidStoreIdentifiersAndUninitializedReads() throws Exception {
        StoreKeyManager manager = new StoreKeyManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            manager.initializeStore("../other-store");
            fail("Unsafe store ID must be rejected");
        } catch (SyncException expected) { /* fail closed */ }
        try {
            manager.getStoreKey("not_initialized_" + System.nanoTime(), 1L);
            fail("Missing epoch must be rejected");
        } catch (SyncException expected) { /* fail closed */ }
    }
}
