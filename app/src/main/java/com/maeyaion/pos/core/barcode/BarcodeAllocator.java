package com.maeyaion.pos.core.barcode;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.BarcodeCounterDao;
import com.maeyaion.pos.core.db.BarcodeCounterEntity;
import com.maeyaion.pos.core.db.BarcodeRegistryDao;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;
import com.maeyaion.pos.core.tx.TransactionRunner;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Allocates permanent barcode identifiers. Callers must execute on a worker thread. */
public final class BarcodeAllocator {
    private static final long FIRST_SYSTEM_VALUE = 2_000_001L;
    private static final int MAX_BARCODE_LENGTH = 128;

    private final BarcodeRegistryDao registryDao;
    private final BarcodeCounterDao counterDao;
    private final TransactionRunner transactions;

    /** Production construction is restricted to the shared Room transaction/maintenance boundary. */
    public BarcodeAllocator(@NonNull BarcodeRegistryDao registryDao,
            @NonNull BarcodeCounterDao counterDao, @NonNull RoomTransactionRunner transactions) {
        this(registryDao, counterDao, (TransactionRunner) transactions);
    }

    /** Package-private transaction seam for deterministic unit tests only. */
    BarcodeAllocator(@NonNull BarcodeRegistryDao registryDao,
            @NonNull BarcodeCounterDao counterDao, @NonNull TransactionRunner transactions) {
        this.registryDao = Objects.requireNonNull(registryDao, "registryDao");
        this.counterDao = Objects.requireNonNull(counterDao, "counterDao");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @NonNull
    public BarcodeRegistryEntity registerManufacturer(@NonNull String barcode,
            @NonNull String displayName, long priceSatang, @NonNull String createdAtUtc) {
        return transactions.runInTransaction(
                () -> registerManufacturerInTransaction(barcode, displayName, priceSatang, createdAtUtc));
    }

    @NonNull
    BarcodeRegistryEntity registerManufacturerInTransaction(@NonNull String barcode,
            @NonNull String displayName, long priceSatang, @NonNull String createdAtUtc) {
        validate(barcode, displayName, priceSatang, createdAtUtc);
        if (registryDao.findByBarcode(barcode) != null) {
            throw new IllegalStateException("Barcode is already registered");
        }
        BarcodeRegistryEntity entity = new BarcodeRegistryEntity(
                barcode, displayName, priceSatang, "MANUFACTURER", createdAtUtc);
        registryDao.insert(entity);
        return entity;
    }

    @NonNull
    public BarcodeRegistryEntity allocateSystem(@NonNull String displayName,
            long priceSatang, @NonNull String createdAtUtc) {
        return transactions.runInTransaction(
                () -> allocateSystemInTransaction(displayName, priceSatang, createdAtUtc));
    }

    @NonNull
    BarcodeRegistryEntity allocateSystemInTransaction(@NonNull String displayName,
            long priceSatang, @NonNull String createdAtUtc) {
        validate("2000001", displayName, priceSatang, createdAtUtc);
        BarcodeCounterEntity counter = counterDao.get();
        if (counter == null || counter.id != 1 || counter.nextValue < FIRST_SYSTEM_VALUE) {
            throw new IllegalStateException("Barcode counter is missing or invalid");
        }
        long candidate = counter.nextValue;
        while (true) {
            String barcode = Long.toString(candidate);
            long next = Math.addExact(candidate, 1L);
            if (registryDao.findByBarcode(barcode) == null) {
                BarcodeRegistryEntity entity = new BarcodeRegistryEntity(
                        barcode, displayName, priceSatang, "SYSTEM", createdAtUtc);
                registryDao.insert(entity);
                if (counterDao.update(new BarcodeCounterEntity(1, next)) != 1) {
                    throw new IllegalStateException("Barcode counter update failed");
                }
                return entity;
            }
            if (counterDao.update(new BarcodeCounterEntity(1, next)) != 1) {
                throw new IllegalStateException("Barcode counter update failed");
            }
            candidate = next;
        }
    }

    /** Returns the immutable registry row so idempotent replay can verify all linked evidence. */
    public BarcodeRegistryEntity findRegistered(@NonNull String barcode) {
        return registryDao.findByBarcode(barcode);
    }

    private static void validate(String barcode, String displayName, long priceSatang,
            String createdAtUtc) {
        if (barcode == null || barcode.isEmpty() || barcode.length() > MAX_BARCODE_LENGTH
                || !barcode.equals(barcode.trim())) {
            throw new IllegalArgumentException("Barcode is empty, too long, or has surrounding whitespace");
        }
        for (int i = 0; i < barcode.length(); i++) {
            if (Character.isISOControl(barcode.charAt(i))) {
                throw new IllegalArgumentException("Barcode contains a control character");
            }
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new IllegalArgumentException("Display name is required");
        }
        if (priceSatang < 0) throw new IllegalArgumentException("Price cannot be negative");
        if (createdAtUtc == null || createdAtUtc.trim().isEmpty()) {
            throw new IllegalArgumentException("Creation timestamp is required");
        }
        try {
            Instant.parse(createdAtUtc);
        } catch (DateTimeParseException invalidTimestamp) {
            throw new IllegalArgumentException("Creation timestamp must be ISO-8601 UTC", invalidTimestamp);
        }
    }
}
