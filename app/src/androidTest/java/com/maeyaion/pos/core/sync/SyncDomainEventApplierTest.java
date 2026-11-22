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

    @Test public void saleReplayCreatesSaleLinesAndAccountingProjections() throws Exception {
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        SyncInboxEntity product = productEvent("SKU-SALE", "2000002", "SYSTEM",
                "Sale product", 500, 200, 0);
        db.runInTransaction(() -> {
            try { applier.apply(product); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        SyncInboxEntity sale = saleEvent();
        db.runInTransaction(() -> {
            try { applier.apply(sale); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });

        assertNotNull(db.saleDao().findById("sale-remote-1"));
        assertEquals(1, db.saleLineDao().forSale("sale-remote-1").size());
        assertEquals(500L, db.dailySummaryDao().find("2026-11-21").net_sales_satang);
        assertEquals(1000L, db.productDailyDao().topSellers("2026-11-21", "2026-11-21")
                .get(0).net_qty_milli);
    }

    @Test public void saleReplayRejectsMismatchedFinancialTotalsAtomically() throws Exception {
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        SyncInboxEntity product = productEvent("SKU-SALE", "2000002", "SYSTEM",
                "Sale product", 500, 200, 0);
        db.runInTransaction(() -> {
            try { applier.apply(product); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        SyncInboxEntity invalid = saleEventWithTotal(999);
        try {
            db.runInTransaction(() -> {
                try { applier.apply(invalid); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            fail("Mismatched sale totals must be rejected");
        } catch (IllegalStateException expected) {
            assertNull(db.saleDao().findById("sale-remote-1"));
            assertNull(db.dailySummaryDao().find("2026-11-21"));
        }
    }

    private static SyncInboxEntity saleEvent() throws Exception {
        return saleEventWithTotal(500);
    }

    private static SyncInboxEntity saleEventWithTotal(long total) throws Exception {
        JSONObject data = new JSONObject();
        data.put("sale_id", "sale-remote-1");
        data.put("idempotency_key", "remote-idem-1");
        data.put("type", "CASH");
        data.put("payment_status", "PAID");
        data.put("lifecycle_status", "COMPLETED");
        data.put("customer_id", JSONObject.NULL);
        data.put("customer", JSONObject.NULL);
        data.put("subtotal_satang", 500);
        data.put("fee_total_satang", 0);
        data.put("total_satang", total);
        data.put("cash_received_satang", 500);
        data.put("change_satang", 0);
        data.put("content_hash", "a".repeat(64));
        data.put("created_at_utc", "2026-11-21T00:00:00Z");
        data.put("paid_at_utc", "2026-11-21T00:00:00Z");
        JSONObject line = new JSONObject();
        line.put("id", "sale-line-remote-1");
        line.put("line_no", 1);
        line.put("sku", "SKU-SALE");
        line.put("name_snap", "Sale product");
        line.put("price_snap_satang", 500);
        line.put("cost_snap_satang", 200);
        line.put("qty_milli", 1000);
        line.put("line_total_satang", 500);
        data.put("lines", new org.json.JSONArray().put(line));
        JSONObject payload = new JSONObject();
        payload.put("schemaVersion", 1);
        payload.put("data", data);
        return new SyncInboxEntity("11111111-1111-4111-8111-111111111111",
                "store-1", 2, "upsert", "sale", "sale-remote-1", 0, 1,
                payload.toString(), "0".repeat(64), "2026-11-21T00:00:00Z", 0);
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
