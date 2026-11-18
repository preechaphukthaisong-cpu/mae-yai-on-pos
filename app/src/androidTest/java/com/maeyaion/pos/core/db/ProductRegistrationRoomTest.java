package com.maeyaion.pos.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.barcode.BarcodeAllocator;
import com.maeyaion.pos.core.barcode.ProductRegistrationService;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class ProductRegistrationRoomTest {
    private static final String TIME = "2026-11-18T00:00:00Z";
    private static final String OP_ID = "00000000-0000-0000-0000-000000000101";

    private Context context;
    private AppDatabase database;
    private String databaseName;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "product-registration-" + System.nanoTime() + ".db";
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void productBarcodeAuditAndJournalCommitOrRollbackAsOneUnit() {
        RoomTransactionRunner transactions = new RoomTransactionRunner(database);
        BarcodeAllocator allocator = new BarcodeAllocator(
                database.barcodeRegistryDao(), database.barcodeCounterDao(), transactions);
        ProductRegistrationService service = new ProductRegistrationService(
                allocator, database.productDao(), database.opLogDao(),
                database.backupJournalDao(), transactions);

        ProductEntity first = service.createWithSystemBarcode(OP_ID, "SKU-ATOMIC",
                "Rice", 2500, 1800, "bag", null, TIME, TIME);
        assertEquals("2000001", first.barcode);
        assertEquals(0L, first.stockQtyMilli);
        assertEquals(2000002L, database.barcodeCounterDao().get().nextValue);
        assertNotNull(database.barcodeRegistryDao().findByBarcode("2000001"));
        assertEquals(1, database.opLogDao().latest(10).size());
        assertEquals(1L, database.backupJournalDao().pendingCount());

        ProductEntity retry = service.createWithSystemBarcode(OP_ID, "SKU-ATOMIC",
                "Rice", 2500, 1800, "bag", null, TIME, TIME);
        assertEquals(first.barcode, retry.barcode);
        assertEquals(1, database.opLogDao().latest(10).size());
        assertEquals(1L, database.backupJournalDao().pendingCount());

        try {
            service.createWithSystemBarcode("00000000-0000-0000-0000-000000000102",
                    "SKU-ATOMIC", "Duplicate SKU", 3000, 2000, "bag", null, TIME, TIME);
            fail("Expected duplicate SKU to abort registration");
        } catch (RuntimeException expected) {
            // Product insert failure must roll back every preceding write.
        }

        assertNull(database.barcodeRegistryDao().findByBarcode("2000002"));
        assertEquals(2000002L, database.barcodeCounterDao().get().nextValue);
        assertEquals(1, database.opLogDao().latest(10).size());
        assertEquals(1L, database.backupJournalDao().pendingCount());
        assertEquals("2000001", database.productDao().findBySku("SKU-ATOMIC").barcode);
    }
    @Test public void concurrentRegistrationsAllocateUniqueBarcodesAndCommitAllEvidence() throws Exception {
        final int workers = 8;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            RoomTransactionRunner transactions = new RoomTransactionRunner(database);
            BarcodeAllocator allocator = new BarcodeAllocator(
                    database.barcodeRegistryDao(), database.barcodeCounterDao(), transactions);
            ProductRegistrationService service = new ProductRegistrationService(
                    allocator, database.productDao(), database.opLogDao(),
                    database.backupJournalDao(), transactions);
            List<Future<ProductEntity>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                final int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start timed out");
                    }
                    return service.createWithSystemBarcode(UUID.randomUUID().toString(),
                            "SKU-CONCURRENT-" + index, "Concurrent product " + index,
                            1000L + index, 500L + index, "piece", null, TIME, TIME);
                }));
            }
            assertTrue("Workers did not become ready", ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<String> barcodes = new HashSet<>();
            for (Future<ProductEntity> future : futures) {
                barcodes.add(future.get(120, TimeUnit.SECONDS).barcode);
            }
            assertEquals(workers, barcodes.size());
            assertEquals(workers, database.productDao().listActive().size());
            assertEquals(workers, database.opLogDao().latest(workers + 1).size());
            assertEquals(workers, database.backupJournalDao().pendingCount());
            assertEquals(2_000_001L + workers, database.barcodeCounterDao().get().nextValue);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

}
