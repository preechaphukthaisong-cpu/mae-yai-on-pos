package com.maeyaion.pos.core.inventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.db.SyncOutboxEntity;
import com.maeyaion.pos.core.sync.SyncOutboxWriter;

import org.json.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class InventoryServiceTest {
    private static final String NOW = "2026-11-21T00:00:00Z";
    private Context context;
    private AppDatabase database;
    private InventoryService service;
    private String databaseName;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "inventory-test-" + System.nanoTime() + ".db";
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
        service = InventoryServiceFactory.create(database);
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void createReceiveCountAndLimitAreAtomicAndAudited() {
        ProductEntity created = service.createAndReceive(UUID.randomUUID().toString(),
                "SKU-INVENTORY-1", "อาหารแมว", 12500L, 9000L, "ถุง",
                5000L, 3000L, NOW);

        assertEquals("2000001", created.barcode);
        assertEquals(3000L, created.stockQtyMilli);
        assertEquals(1, service.allProducts().size());
        assertEquals(1, service.belowStockLimit().size());

        ProductEntity received = service.receive(UUID.randomUUID().toString(),
                created.barcode, 1000L, NOW);
        assertEquals(4000L, received.stockQtyMilli);
        assertEquals(1, service.belowStockLimit().size());

        ProductEntity counted = service.replaceCount(UUID.randomUUID().toString(),
                created.barcode, 2000L, NOW);
        assertEquals(2000L, counted.stockQtyMilli);
        assertEquals(1, service.belowStockLimit().size());

        service.updateStockLimit(UUID.randomUUID().toString(), created.sku, 1500L, NOW);
        assertTrue(service.belowStockLimit().isEmpty());

        List<StockLogEntity> history = service.history(created.sku);
        assertEquals(3, history.size());
        assertEquals("COUNT_REPLACE", history.get(0).reason);
        assertEquals(-2000L, history.get(0).diff_qty_milli);
        assertEquals("RECEIVE", history.get(1).reason);
        assertEquals(1000L, history.get(1).diff_qty_milli);
        assertEquals("RECEIVE", history.get(2).reason);
        assertEquals(3000L, history.get(2).diff_qty_milli);
        assertTrue(database.backupJournalDao().pendingCount() >= 5L);
    }


    @Test public void inventoryMutationsAppendOrderedFullSnapshotsToOutboxAtomically() throws Exception {
        ProductEntity created = service.createAndReceive(UUID.randomUUID().toString(),
                "SKU-SYNC-1", "สินค้าทดสอบ", 1200L, 700L, "ชิ้น",
                9000L, 3000L, NOW);
        service.receive(UUID.randomUUID().toString(), created.barcode, 500L, NOW);
        service.replaceCount(UUID.randomUUID().toString(), created.barcode, 4200L, NOW);
        service.updateStockLimit(UUID.randomUUID().toString(), created.sku, 8000L, NOW);

        List<SyncOutboxEntity> events = database.syncQueueDao()
                .pendingOutbox(SyncOutboxWriter.UNBOUND_STORE_ID, 20);
        assertEquals(4, events.size());
        for (int i = 0; i < events.size(); i++) {
            SyncOutboxEntity event = events.get(i);
            assertEquals("product", event.entity_type);
            assertEquals(created.sku, event.entity_id);
            assertEquals(i, event.base_version);
            assertEquals("PENDING", event.delivery_state);
            assertEquals(64, event.payload_sha256.length());
        }
        assertEquals(4200L, new JSONObject(events.get(2).payload_json)
                .getJSONObject("data").getLong("stock_qty_milli"));
        assertEquals(8000L, new JSONObject(events.get(3).payload_json)
                .getJSONObject("data").getLong("stock_limit_qty_milli"));
    }

    @Test public void countCannotCreateNegativeStockAndFailedOperationLeavesLedgerUnchanged() {
        ProductEntity created = service.createAndReceive(UUID.randomUUID().toString(),
                "SKU-INVENTORY-2", "ข้าวสาร", 10000L, 8000L, "ถุง",
                null, 1000L, NOW);
        int before = service.history(created.sku).size();
        try {
            service.replaceCount(UUID.randomUUID().toString(), created.barcode, -1L, NOW);
            throw new AssertionError("Negative count must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(before, service.history(created.sku).size());
            assertEquals(1000L, service.findByBarcode(created.barcode).stockQtyMilli);
        }
    }

    @Test public void unknownBarcodeIsNotSilentlyCreatedAndLowStockUsesStrictLessThan() {
        assertNull(service.findByBarcode("UNKNOWN"));
        ProductEntity created = service.createAndReceive(UUID.randomUUID().toString(),
                "SKU-INVENTORY-3", "น้ำดื่ม", 500L, 200L, "ขวด",
                1000L, 1000L, NOW);
        assertEquals(0, service.belowStockLimit().size());
        service.replaceCount(UUID.randomUUID().toString(), created.barcode, 999L, NOW);
        assertEquals(1, service.belowStockLimit().size());
        assertNotNull(service.findByBarcode(created.barcode));
    }
}
