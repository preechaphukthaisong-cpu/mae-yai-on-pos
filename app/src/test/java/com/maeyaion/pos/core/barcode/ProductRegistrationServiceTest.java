package com.maeyaion.pos.core.barcode;

import com.maeyaion.pos.core.db.BackupJournalDao;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.OpLogDao;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.BarcodeCounterDao;
import com.maeyaion.pos.core.db.BarcodeCounterEntity;
import com.maeyaion.pos.core.db.BarcodeRegistryDao;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.ProductDao;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

public final class ProductRegistrationServiceTest {
    private static final String TIME = "2026-11-18T00:00:00Z";

    @Test public void createsProductWithSystemBarcodeAtZeroStock() {
        Store store = new Store();
        ProductEntity product = service(store).createWithSystemBarcode(
                "SKU-1", "Rice", 2500, 1800, "ถุง", 5000L, TIME, TIME);
        assertEquals("2000001", product.barcode);
        assertEquals(0L, product.stockQtyMilli);
        assertEquals(2000002L, store.counter.value.nextValue);
        assertNotNull(store.registry.findByBarcode("2000001"));
        assertNotNull(store.products.findBySku("SKU-1"));
        assertEquals(1, store.opLogs.entries.size());
        assertEquals(1, store.journal.entries.size());
        assertEquals(64, store.journal.entries.get(0).payload_sha256.length());
    }

    @Test public void productInsertFailureRollsBackBarcodeAndCounter() {
        Store store = new Store();
        store.products.failInsert = true;
        try {
            service(store).createWithSystemBarcode(
                    "SKU-FAIL", "Rice", 2500, 1800, "ถุง", null, TIME, TIME);
            fail("Expected product insert failure");
        } catch (IllegalStateException expected) {
            assertEquals("simulated_product_insert_failure", expected.getMessage());
        }
        assertNull(store.registry.findByBarcode("2000001"));
        assertEquals(2000001L, store.counter.value.nextValue);
        assertNull(store.products.findBySku("SKU-FAIL"));
        assertTrue(store.opLogs.entries.isEmpty());
        assertTrue(store.journal.entries.isEmpty());
    }

    @Test public void manufacturerBarcodeAndProductAreCreatedTogether() {
        Store store = new Store();
        ProductEntity product = service(store).createWithManufacturerBarcode(
                "0001234567890", "SKU-2", "Soap", 9900, 5000,
                "ก้อน", null, TIME, TIME);
        assertEquals("0001234567890", product.barcode);
        assertEquals("MANUFACTURER", store.registry.findByBarcode(product.barcode).sourceType);
        assertEquals(2000001L, store.counter.value.nextValue);
        assertEquals(1, store.opLogs.entries.size());
        assertEquals(1, store.journal.entries.size());
    }

    private static ProductRegistrationService service(Store store) {
        BarcodeAllocator allocator = new BarcodeAllocator(store.registry, store.counter, store);
        return new ProductRegistrationService(allocator, store.products,
                store.opLogs, store.journal, store);
    }

    private static final class Store implements TransactionRunner {
        final FakeRegistry registry = new FakeRegistry();
        final FakeCounter counter = new FakeCounter();
        final FakeProducts products = new FakeProducts();
        final FakeOpLogs opLogs = new FakeOpLogs();
        final FakeJournal journal = new FakeJournal();

        @Override public <T> T runInTransaction(Callable<T> operation) {
            Map<String, BarcodeRegistryEntity> oldRegistry = new HashMap<>(registry.values);
            Map<String, ProductEntity> oldProducts = new HashMap<>(products.values);
            List<OpLogEntity> oldOpLogs = new ArrayList<>(opLogs.entries);
            List<BackupJournalEntity> oldJournal = new ArrayList<>(journal.entries);
            long oldJournalSeq = journal.nextSeq;
            BarcodeCounterEntity oldCounter = counter.value;
            try {
                return operation.call();
            } catch (RuntimeException error) {
                registry.values.clear();
                registry.values.putAll(oldRegistry);
                products.values.clear();
                products.values.putAll(oldProducts);
                opLogs.entries.clear();
                opLogs.entries.addAll(oldOpLogs);
                journal.entries.clear();
                journal.entries.addAll(oldJournal);
                journal.nextSeq = oldJournalSeq;
                counter.value = oldCounter;
                throw error;
            } catch (Exception error) {
                registry.values.clear();
                registry.values.putAll(oldRegistry);
                products.values.clear();
                products.values.putAll(oldProducts);
                opLogs.entries.clear();
                opLogs.entries.addAll(oldOpLogs);
                journal.entries.clear();
                journal.entries.addAll(oldJournal);
                journal.nextSeq = oldJournalSeq;
                counter.value = oldCounter;
                throw new RuntimeException(error);
            }
        }
    }

    private static final class FakeRegistry implements BarcodeRegistryDao {
        final Map<String, BarcodeRegistryEntity> values = new HashMap<>();
        @Override public BarcodeRegistryEntity findByBarcode(String barcode) { return values.get(barcode); }
        @Override public void insert(BarcodeRegistryEntity entry) {
            if (values.putIfAbsent(entry.barcode, entry) != null) {
                throw new IllegalStateException("duplicate_barcode");
            }
        }
    }

    private static final class FakeCounter implements BarcodeCounterDao {
        BarcodeCounterEntity value = new BarcodeCounterEntity(1, 2000001L);
        @Override public BarcodeCounterEntity get() { return value; }
        @Override public int update(BarcodeCounterEntity next) { value = next; return 1; }
    }

    private static final class FakeOpLogs implements OpLogDao {
        final List<OpLogEntity> entries = new ArrayList<>();
        @Override public void append(OpLogEntity entry) { entries.add(entry); }
        @Override public List<OpLogEntity> latest(int limit) {
            return new ArrayList<>(entries.subList(Math.max(0, entries.size() - limit), entries.size()));
        }
    }

    private static final class FakeJournal implements BackupJournalDao {
        final List<BackupJournalEntity> entries = new ArrayList<>();
        long nextSeq = 1L;
        @Override public long append(BackupJournalEntity entry) {
            entries.add(new BackupJournalEntity(nextSeq, entry.operation_id, entry.op,
                    entry.payload, entry.payload_sha256, entry.schema_version,
                    entry.created_at_utc, entry.uploaded));
            return nextSeq++;
        }
        @Override public List<BackupJournalEntity> pendingBatch(int limit) {
            return new ArrayList<>(entries.subList(0, Math.min(limit, entries.size())));
        }
        @Override public int markUploaded(long seq) { return 0; }
        @Override public long pendingCount() { return entries.size(); }
    }

    private static final class FakeProducts implements ProductDao {
        final Map<String, ProductEntity> values = new HashMap<>();
        boolean failInsert;
        @Override public ProductEntity findBySku(String sku) { return values.get(sku); }
        @Override public ProductEntity findByBarcode(String barcode) {
            for (ProductEntity product : values.values()) {
                if (product.barcode.equals(barcode)) return product;
            }
            return null;
        }
        @Override public List<ProductEntity> listActive() { return new ArrayList<>(values.values()); }
        @Override public void insert(ProductEntity product) {
            if (failInsert) throw new IllegalStateException("simulated_product_insert_failure");
            if (values.putIfAbsent(product.sku, product) != null) {
                throw new IllegalStateException("duplicate_sku");
            }
        }
    }
}
