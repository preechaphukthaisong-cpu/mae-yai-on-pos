package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.Migration1To2;
import com.maeyaion.pos.core.db.Migration2To3;
import com.maeyaion.pos.core.db.Migration3To4;
import com.maeyaion.pos.core.db.Migration4To5;
import com.maeyaion.pos.core.db.Migration5To6;
import com.maeyaion.pos.core.db.SyncInboxEntity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SyncDomainEventApplierTest {
    private AppDatabase db;
    private String databaseName;

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "sync-domain-" + System.nanoTime() + ".db";
        db = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(Migration1To2.INSTANCE, Migration2To3.INSTANCE,
                        Migration3To4.INSTANCE, Migration4To5.INSTANCE, Migration5To6.INSTANCE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries().build();
        db.getOpenHelper().getWritableDatabase();
    }

    @After public void tearDown() {
        if (db != null) db.close();
        InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(databaseName);
    }

    @Test public void productReplayCreatesRegistryAndProductAtomically() throws Exception {
        SyncInboxEntity event = productEvent("SKU-REMOTE", "2000001", "SYSTEM",
                "Remote product", 1250, 500, 0);
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        db.runInTransaction(() -> {
            try { applier.apply(event); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });

        assertNotNull(db.barcodeRegistryDao().findByBarcode("2000001"));
        assertEquals("SYSTEM", db.barcodeRegistryDao().findByBarcode("2000001").sourceType);
        assertEquals(0L, db.productDao().findBySku("SKU-REMOTE").stockQtyMilli);
        assertEquals(1250L, db.productDao().findBySku("SKU-REMOTE").priceSatang);
    }

    @Test public void conflictingImmutableBarcodeProvenanceRejectsReplay() throws Exception {
        db.runInTransaction(() -> db.barcodeRegistryDao().insert(new BarcodeRegistryEntity(
                "2000001", "Original", 1000, "SYSTEM", "2026-11-21T00:00:00Z")));
        SyncInboxEntity event = productEvent("SKU-REMOTE", "2000001", "SYSTEM",
                "Changed", 1250, 500, 9000);
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        try {
            db.runInTransaction(() -> {
                try { applier.apply(event); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            fail("Conflicting barcode provenance must be rejected");
        } catch (IllegalStateException expected) {
            assertNull(db.productDao().findBySku("SKU-REMOTE"));
        }
        assertEquals("Original", db.barcodeRegistryDao().findByBarcode("2000001").displayName);
    }

    @Test public void unsupportedFinancialAggregateFailsClosed() throws Exception {
        SyncInboxEntity event = new SyncInboxEntity("11111111-1111-4111-8111-111111111111",
                "store-1", 1, "upsert", "sale", "sale-1", 0, 1,
                "{\"schemaVersion\":1,\"data\":{}}", "0".repeat(64),
                "2026-11-21T00:00:00Z", 0);
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        try {
            db.runInTransaction(() -> {
                try { applier.apply(event); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            fail("Unsupported financial events must not be applied");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause().getMessage().contains("No typed replay handler"));
        }
    }

    private static SyncInboxEntity productEvent(String sku, String barcode, String source,
            String registryName, long registryPrice, long cost, long stock) throws Exception {
        JSONObject data = new JSONObject();
        data.put("sku", sku);
        data.put("barcode", barcode);
        data.put("barcode_source_type", source);
        data.put("barcode_registry_display_name", registryName);
        data.put("barcode_registry_price_satang", registryPrice);
        data.put("barcode_registry_created_at_utc", "2026-11-21T00:00:00Z");
        data.put("name", registryName);
        data.put("price_satang", registryPrice);
        data.put("cost_satang", cost);
        data.put("unit", "ชิ้น");
        data.put("stock_limit_qty_milli", JSONObject.NULL);
        data.put("stock_qty_milli", stock);
        data.put("is_active", 1);
        data.put("created_at_utc", "2026-11-21T00:00:00Z");
        data.put("updated_at_utc", "2026-11-21T00:00:00Z");
        JSONObject payload = new JSONObject();
        payload.put("schemaVersion", 1);
        payload.put("data", data);
        return new SyncInboxEntity("22222222-2222-4222-8222-222222222222",
                "store-1", 1, "upsert", "product", sku, 0, 1,
                payload.toString(), "0".repeat(64), "2026-11-21T00:00:00Z", 0);
    }
}
