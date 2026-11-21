package com.maeyaion.pos.core.inventory;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.barcode.BarcodeAllocator;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

public final class InventoryServiceFactory {
    private InventoryServiceFactory() {}
    @NonNull public static InventoryService create(@NonNull AppDatabase db) {
        RoomTransactionRunner transactions = new RoomTransactionRunner(db);
        BarcodeAllocator allocator = new BarcodeAllocator(
                db.barcodeRegistryDao(), db.barcodeCounterDao(), transactions);
        return new InventoryService(allocator, db.productDao(), db.stockLogDao(),
                db.opLogDao(), db.backupJournalDao(), transactions);
    }
}
