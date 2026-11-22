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
import com.maeyaion.pos.core.db.Migration6To7;
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
                        Migration3To4.INSTANCE, Migration4To5.INSTANCE, Migration5To6.INSTANCE, Migration6To7.INSTANCE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries().build();
        db.getOpenHelper().getWritableDatabase();
    }

    @After public void tearDown() {
        if (db != null) db.close();
        InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(databaseName);
    }

    @Test public void legacyPushCoordinatorRefusesPlaintextBeforeNetworkOrDatabaseAccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SyncApiClient api = new SyncApiClient("https://sync.invalid",
                () -> "test-id-token", new DeviceIdentityStore(context));
        try {
            new SyncPushCoordinator(db, api).pushPending("store-test");
            fail("Plaintext push constructor must fail closed");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("encryption is mandatory"));
        }
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

    @Test public void inventoryReplayPreservesReceiptMovementAndOperationProvenance() throws Exception {
        SyncInboxEntity base = productEvent("SKU-STOCK", "2000099", "SYSTEM",
                "Stock item", 2500, 900, 1500);
        JSONObject payload = new JSONObject(base.payload_json);
        JSONObject data = payload.getJSONObject("data");
        data.put("stock_movement", new JSONObject()
                .put("operation_id", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
                .put("before_qty_milli", 0)
                .put("diff_qty_milli", 1500)
                .put("after_qty_milli", 1500)
                .put("reason", "RECEIVE")
                .put("created_at_utc", "2026-11-21T00:00:00Z"));
        SyncInboxEntity event = new SyncInboxEntity(base.event_id, base.store_id,
                base.server_sequence, base.operation, base.entity_type, base.entity_id,
                base.base_version, base.entity_version, payload.toString(),
                "0".repeat(64), base.received_at_utc, 0);
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        db.runInTransaction(() -> {
            try { applier.apply(event); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        java.util.List<com.maeyaion.pos.core.db.StockLogEntity> history =
                db.stockLogDao().historyForSku("SKU-STOCK");
        assertEquals(1, history.size());
        assertEquals(0L, history.get(0).before_qty_milli);
        assertEquals(1500L, history.get(0).after_qty_milli);
        assertEquals(1500L, history.get(0).diff_qty_milli);
        assertEquals("RECEIVE", history.get(0).reason);
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", history.get(0).ref_id);
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

    @Test public void returnAndCancellationReplayPreserveDailyAccounting() throws Exception {
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        db.runInTransaction(() -> {
            try { applier.apply(productEvent("SKU-SALE", "2000002", "SYSTEM",
                    "Sale product", 500, 200, 0)); applier.apply(saleEvent()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        db.runInTransaction(() -> {
            try { applier.apply(returnEvent()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertEquals(250L, db.dailySummaryDao().find("2026-11-21").net_sales_satang);
        assertEquals(500L, db.productDailyDao().topSellers("2026-11-21", "2026-11-21")
                .get(0).net_qty_milli);

        db.runInTransaction(() -> {
            try { applier.apply(cancelEvent()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertEquals(0L, db.dailySummaryDao().find("2026-11-21").net_sales_satang);
        assertEquals(0L, db.productDailyDao().topSellers("2026-11-21", "2026-11-21").size() == 0
                ? 0L : db.productDailyDao().topSellers("2026-11-21", "2026-11-21").get(0).net_qty_milli);
        assertEquals("CANCELLED", db.saleDao().findById("sale-remote-1").lifecycle_status);
    }

    @Test public void debtPaymentBatchReplaysCustomerDebtSettlement() throws Exception {
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        db.runInTransaction(() -> {
            try {
                applier.apply(productEvent("SKU-CREDIT", "2000003", "SYSTEM",
                        "Credit product", 500, 200, 0));
                applier.apply(creditSaleEvent());
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        db.runInTransaction(() -> {
            try { applier.apply(debtPaymentEvent()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertEquals("PAID", db.saleDao().findById("sale-credit-1").payment_status);
        assertEquals(500L, db.debtPaymentDao().findForSale("sale-credit-1").amount_satang);
        assertNotNull(db.customerDao().findById("customer-credit-1"));
    }

    @Test public void failedEncryptedReplayIsQuarantinedWithoutAdvancingCursor() throws Exception {
        String storeId = "store-1";
        StoreKeyManager keys = new StoreKeyManager(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        keys.initializeStore(storeId);
        EncryptedSyncEventCodec codec = new EncryptedSyncEventCodec(keys);
        String eventId = "99999999-9999-4999-8999-999999999991";
        JSONObject plain = new JSONObject();
        plain.put("schemaVersion", 1);
        plain.put("data", new JSONObject());
        JSONObject wire = codec.encrypt(storeId, eventId, "upsert",
                "unsupported_aggregate", "entity-1", 0, plain);
        JSONObject encrypted = wire.getJSONObject("payload");
        String canonical = SyncEventEnvelope.canonicalJson(encrypted);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder(64);
        for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        SyncInboxEntity inboxEvent = new SyncInboxEntity(eventId, storeId, 1,
                "upsert", "unsupported_aggregate", "entity-1", 0, 1,
                encrypted.toString(), hash.toString(), "2026-11-21T00:00:00Z", 0);
        SyncInboxProcessor processor = new SyncInboxProcessor(db);
        db.runInTransaction(() -> processor.receive(inboxEvent));
        try {
            processor.applyNext(storeId, "2026-11-21T00:01:00Z", keys);
            fail("Unsupported aggregate must fail closed");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("No typed replay handler"));
        }
        assertNotNull(db.syncQueueDao().replayConflict(eventId));
        assertEquals(0, db.syncQueueDao().cursor(storeId) == null
                ? 0L : db.syncQueueDao().cursor(storeId).last_applied_sequence);
        assertEquals(0, db.syncQueueDao().inboxEvent(eventId).applied);
        db.runInTransaction(() -> {
            assertEquals(1, db.syncQueueDao().clearReplayConflictForRetry(eventId));
        });
        assertNull(db.syncQueueDao().replayConflict(eventId));
        assertEquals(0, db.syncQueueDao().inboxEvent(eventId).applied);
        assertEquals(1, db.syncQueueDao().unappliedInboxCount(storeId));
        try {
            processor.applyNext(storeId, "2026-11-21T00:02:00Z", keys);
            fail("Retry must execute the same typed replay and fail closed again");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("No typed replay handler"));
        }
        assertNotNull(db.syncQueueDao().replayConflict(eventId));
        assertEquals(0, db.syncQueueDao().inboxEvent(eventId).applied);
    }

    @Test public void localStoreSettingUpdateWritesOutboxInSameTransaction() throws Exception {
        com.maeyaion.pos.core.store.StoreSettingsSyncService service =
                new com.maeyaion.pos.core.store.StoreSettingsSyncService(
                        db, new com.maeyaion.pos.core.tx.RoomTransactionRunner(db));
        service.update("store_name", "แม่ใหญ่อ่อน", "2026-11-21T00:00:00Z");
        assertEquals("แม่ใหญ่อ่อน", db.appMetaDao().findValue("store_name"));
        java.util.List<com.maeyaion.pos.core.db.SyncOutboxEntity> events =
                db.syncQueueDao().pendingOutbox(SyncOutboxWriter.UNBOUND_STORE_ID, 10);
        assertEquals(1, events.size());
        assertEquals("store_setting", events.get(0).entity_type);
        assertEquals("store_name", events.get(0).entity_id);
        assertTrue(events.get(0).payload_json.contains("แม่ใหญ่อ่อน"));
        try {
            service.update("google_refresh_token", "secret", "2026-11-21T00:01:00Z");
            fail("Secret metadata must not be synchronized");
        } catch (IllegalArgumentException expected) {
            assertEquals(1, db.syncQueueDao().pendingOutbox(
                    SyncOutboxWriter.UNBOUND_STORE_ID, 10).size());
        }
    }

    @Test public void storeSettingReplayAllowsOnlyWhitelistedProfileFields() throws Exception {
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        JSONObject allowed = new JSONObject();
        allowed.put("setting_key", "store_name");
        allowed.put("value", "ร้านแม่ใหญ่อ่อน");
        allowed.put("updated_at_utc", "2026-11-21T00:00:00Z");
        db.runInTransaction(() -> {
            try { applier.apply(wrappedEvent("88888888-8888-4888-8888-888888888881",
                    "store_setting", "store_name", allowed, 1)); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertEquals("ร้านแม่ใหญ่อ่อน", db.appMetaDao().findValue("store_name"));

        JSONObject secret = new JSONObject();
        secret.put("setting_key", "google_refresh_token");
        secret.put("value", "must-not-sync");
        secret.put("updated_at_utc", "2026-11-21T00:00:00Z");
        try {
            db.runInTransaction(() -> {
                try { applier.apply(wrappedEvent("88888888-8888-4888-8888-888888888882",
                        "store_setting", "google_refresh_token", secret, 2)); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            fail("Non-allow-listed metadata must not be replayed");
        } catch (IllegalStateException expected) {
            assertNull(db.appMetaDao().findValue("google_refresh_token"));
        }
    }

    @Test public void customerReplayStoresPrivateIdentityAndRejectsNormalizedCollision() throws Exception {
        SyncDomainEventApplier applier = new SyncDomainEventApplier(db);
        db.runInTransaction(() -> {
            try { applier.apply(customerEvent("77777777-7777-4777-8777-777777777771", "Somchai", "somchai")); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertEquals("Somchai", db.customerDao().findById("77777777-7777-4777-8777-777777777771").name);
        try {
            db.runInTransaction(() -> {
                try { applier.apply(customerEvent("77777777-7777-4777-8777-777777777772", "SOMCHAI", "somchai")); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            fail("Normalized duplicate customer identity must be rejected");
        } catch (IllegalStateException expected) {
            assertNull(db.customerDao().findById("77777777-7777-4777-8777-777777777772"));
        }
    }

    private static SyncInboxEntity customerEvent(String id, String name, String nameKey)
            throws Exception {
        JSONObject data = new JSONObject();
        data.put("customer_id", id);
        data.put("name", name);
        data.put("name_key", nameKey);
        data.put("identity_key", "identity-remote-1");
        data.put("created_at_utc", "2026-11-21T00:00:00Z");
        data.put("updated_at_utc", "2026-11-21T00:00:00Z");
        return wrappedEvent("77777777-7777-4777-8777-777777777777",
                "customer", id, data, 1);
    }

    private static SyncInboxEntity returnEvent() throws Exception {
        JSONObject data = new JSONObject();
        data.put("return_id", "return-remote-1");
        data.put("sale_id", "sale-remote-1");
        data.put("reason", "Damaged");
        data.put("cash_refund_satang", 250);
        data.put("debt_reduction_satang", 0);
        data.put("fee_reversal_satang", 0);
        data.put("recipient_name", "Customer");
        data.put("operator_label", "Operator");
        data.put("created_at_utc", "2026-11-21T01:00:00Z");
        JSONObject line = new JSONObject();
        line.put("id", "return-line-1");
        line.put("sale_line_id", "sale-line-remote-1");
        line.put("qty_milli", 500);
        line.put("value_satang", 250);
        line.put("fee_satang", 0);
        line.put("restock", 0);
        line.put("non_restock_reason", "Damaged");
        data.put("lines", new org.json.JSONArray().put(line));
        return wrappedEvent("33333333-3333-4333-8333-333333333333",
                "sale_return", "return-remote-1", data, 3);
    }

    private static SyncInboxEntity cancelEvent() throws Exception {
        JSONObject data = new JSONObject();
        data.put("cancel_id", "cancel-remote-1");
        data.put("sale_id", "sale-remote-1");
        data.put("reason", "Cancel remaining");
        data.put("cash_refund_satang", 250);
        data.put("debt_reduction_satang", 0);
        data.put("fee_reversal_satang", 0);
        data.put("recipient_name", "Customer");
        data.put("operator_label", "Operator");
        data.put("created_at_utc", "2026-11-21T02:00:00Z");
        JSONObject line = new JSONObject();
        line.put("id", "cancel-line-1");
        line.put("sale_line_id", "sale-line-remote-1");
        line.put("qty_milli", 500);
        line.put("value_satang", 250);
        line.put("fee_satang", 0);
        line.put("restock", 0);
        line.put("non_restock_reason", "Cancel");
        data.put("lines", new org.json.JSONArray().put(line));
        return wrappedEvent("44444444-4444-4444-8444-444444444444",
                "sale_cancel", "cancel-remote-1", data, 4);
    }

    private static SyncInboxEntity creditSaleEvent() throws Exception {
        JSONObject data = new JSONObject();
        data.put("sale_id", "sale-credit-1");
        data.put("idempotency_key", "remote-credit-idem");
        data.put("type", "CREDIT");
        data.put("payment_status", "UNPAID");
        data.put("lifecycle_status", "COMPLETED");
        data.put("customer_id", "customer-credit-1");
        JSONObject customer = new JSONObject();
        customer.put("id", "customer-credit-1");
        customer.put("name", "Credit Customer");
        customer.put("name_key", "credit customer");
        customer.put("identity_key", "identity-hash-1");
        customer.put("created_at_utc", "2026-11-21T00:00:00Z");
        customer.put("updated_at_utc", "2026-11-21T00:00:00Z");
        data.put("customer", customer);
        data.put("subtotal_satang", 500);
        data.put("fee_total_satang", 0);
        data.put("total_satang", 500);
        data.put("cash_received_satang", JSONObject.NULL);
        data.put("change_satang", JSONObject.NULL);
        data.put("content_hash", "b".repeat(64));
        data.put("created_at_utc", "2026-11-21T00:00:00Z");
        data.put("paid_at_utc", JSONObject.NULL);
        JSONObject line = new JSONObject();
        line.put("id", "credit-line-1");
        line.put("line_no", 1);
        line.put("sku", "SKU-CREDIT");
        line.put("name_snap", "Credit product");
        line.put("price_snap_satang", 500);
        line.put("cost_snap_satang", 200);
        line.put("qty_milli", 1000);
        line.put("line_total_satang", 500);
        data.put("lines", new org.json.JSONArray().put(line));
        return wrappedEvent("55555555-5555-4555-8555-555555555555",
                "sale", "sale-credit-1", data, 2);
    }

    private static SyncInboxEntity debtPaymentEvent() throws Exception {
        JSONObject data = new JSONObject();
        data.put("paid_at_utc", "2026-11-22T01:00:00Z");
        JSONObject payment = new JSONObject();
        payment.put("payment_id", "debt-payment-1");
        payment.put("sale_id", "sale-credit-1");
        payment.put("amount_satang", 500);
        data.put("payments", new org.json.JSONArray().put(payment));
        return wrappedEvent("66666666-6666-4666-8666-666666666666",
                "debt_payment_batch", "debt-batch-1", data, 3);
    }

    private static SyncInboxEntity wrappedEvent(String eventId, String entityType,
            String entityId, JSONObject data, long sequence) throws Exception {
        JSONObject envelope = new JSONObject();
        envelope.put("schemaVersion", 1);
        envelope.put("data", data);
        return new SyncInboxEntity(eventId, "store-1", sequence, "upsert",
                entityType, entityId, 0, 1, envelope.toString(), "0".repeat(64),
                "2026-11-21T03:00:00Z", 0);
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
