package com.maeyaion.pos.core.barcode;

import com.maeyaion.pos.core.db.BarcodeCounterDao;
import com.maeyaion.pos.core.db.BarcodeCounterEntity;
import com.maeyaion.pos.core.db.BarcodeRegistryDao;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import static org.junit.Assert.*;

public class BarcodeAllocatorTest {
    @Test public void allocatesFromReservedRangeAndSkipsExistingCodes() {
        FakeRegistry registry = new FakeRegistry();
        registry.insert(new BarcodeRegistryEntity("2000001", "Factory", 100, "MANUFACTURER", "2026-11-18T00:00:00Z"));
        FakeCounter counter = new FakeCounter();
        BarcodeAllocator allocator = new BarcodeAllocator(registry, counter, new DirectTransactions());
        BarcodeRegistryEntity allocated = allocator.allocateSystem("System item", 250, "2026-11-18T00:00:00Z");
        assertEquals("2000002", allocated.barcode);
        assertEquals("SYSTEM", allocated.sourceType);
        assertEquals(2000003L, counter.value.nextValue);
    }

    @Test public void preservesLeadingZeroInManufacturerBarcode() {
        FakeRegistry registry = new FakeRegistry();
        BarcodeAllocator allocator = new BarcodeAllocator(registry, new FakeCounter(), new DirectTransactions());
        BarcodeRegistryEntity saved = allocator.registerManufacturer("0001234567890", "Item", 0, "2026-11-18T00:00:00Z");
        assertEquals("0001234567890", saved.barcode);
        assertEquals("MANUFACTURER", saved.sourceType);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsPreviouslyRegisteredManufacturerBarcode() {
        FakeRegistry registry = new FakeRegistry();
        registry.insert(new BarcodeRegistryEntity("0123", "Existing", 0, "MANUFACTURER", "2026-11-18T00:00:00Z"));
        new BarcodeAllocator(registry, new FakeCounter(), new DirectTransactions())
                .registerManufacturer("0123", "Duplicate", 0, "2026-11-18T00:00:00Z");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWhitespaceAroundBarcodeInsteadOfSilentlyChangingIt() {
        new BarcodeAllocator(new FakeRegistry(), new FakeCounter(), new DirectTransactions())
                .registerManufacturer(" 0123", "Item", 0, "2026-11-18T00:00:00Z");
    }

    private static final class DirectTransactions implements TransactionRunner {
        @Override public <T> T runInTransaction(Callable<T> operation) {
            try { return operation.call(); }
            catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    private static final class FakeCounter implements BarcodeCounterDao {
        BarcodeCounterEntity value = new BarcodeCounterEntity(1, 2000001L);
        @Override public BarcodeCounterEntity get() { return value; }
        @Override public int update(BarcodeCounterEntity next) { value = next; return 1; }
    }
    private static final class FakeRegistry implements BarcodeRegistryDao {
        final Map<String, BarcodeRegistryEntity> values = new HashMap<>();
        @Override public BarcodeRegistryEntity findByBarcode(String barcode) { return values.get(barcode); }
        @Override public java.util.List<BarcodeRegistryEntity> listAll() { return new java.util.ArrayList<>(values.values()); }
        @Override public void insert(BarcodeRegistryEntity entry) {
            if (values.putIfAbsent(entry.barcode, entry) != null) throw new IllegalStateException("duplicate");
        }
    }
}