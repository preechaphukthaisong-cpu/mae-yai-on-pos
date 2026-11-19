package com.maeyaion.pos.core.sales;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class CashSaleAtomicityTest {
    private static final String TIME = "2026-11-19T00:00:00Z";
    private Context context;
    private AppDatabase database;
    private String databaseName;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        databaseName = "cash-sale-" + System.nanoTime() + ".db";
        database = Room.databaseBuilder(context, AppDatabase.class, databaseName)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries()
                .build();
        database.getOpenHelper().getWritableDatabase();
        seedProduct(2_000L);
    }

    @After public void tearDown() {
        if (database != null) database.close();
        context.deleteDatabase(databaseName);
    }

    @Test public void cashSaleCommitsSaleLinesStockAuditAndBackupJournalTogether() {
        CashSaleService service = service();
        String key = UUID.randomUUID().toString();
        CashSaleService.LineInput line = new CashSaleService.LineInput("SKU-1", 1_000L);
        com.maeyaion.pos.core.db.SaleEntity sale =
                service.createCashSale(key, Collections.singletonList(line), 2_000L, TIME);

        assertEquals(1_990L, sale.totalSatang);
        assertEquals(Long.valueOf(2_000L), sale.cashReceivedSatang);
        assertEquals(Long.valueOf(10L), sale.changeSatang);
        assertEquals(1_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
        assertEquals(1, database.saleDao().linesForSale(sale.id).size());
        assertEquals(1L, database.backupJournalDao().pendingCount());
        assertEquals(1, database.opLogDao().latest(10).size());
        assertSqlCount("sale", 1L);
        assertSqlCount("sale_line", 1L);
        assertSqlCount("stock_log", 2L);
    }

    @Test public void insufficientStockLeavesNoPartialSaleOrEvidence() {
        try {
            service().createCashSale(UUID.randomUUID().toString(),
                    Collections.singletonList(new CashSaleService.LineInput("SKU-1", 3_000L)),
                    10_000L, TIME);
            fail("Expected insufficient stock");
        } catch (CashSaleService.InsufficientStockException expected) {
            assertEquals(2_000L, expected.availableQtyMilli);
        }
        assertNull(database.saleDao().findByIdempotencyKey(
                "00000000-0000-0000-0000-000000000099"));
        assertEquals(2_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
        assertEquals(0L, database.backupJournalDao().pendingCount());
        assertEquals(0, database.opLogDao().latest(10).size());
        assertSqlCount("sale", 0L);
        assertSqlCount("sale_line", 0L);
        assertSqlCount("stock_log", 1L);
    }

    @Test public void retryWithSameIdempotencyKeyDoesNotSellOrDecrementStockTwice() {
        CashSaleService service = service();
        String key = UUID.randomUUID().toString();
        java.util.List<CashSaleService.LineInput> lines = Collections.singletonList(
                new CashSaleService.LineInput("SKU-1", 1_000L));
        com.maeyaion.pos.core.db.SaleEntity first = service.createCashSale(key, lines, 2_000L, TIME);
        com.maeyaion.pos.core.db.SaleEntity retry = service.createCashSale(key, lines, 2_000L, TIME);
        assertEquals(first.id, retry.id);
        assertEquals(1_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
        assertEquals(1L, database.backupJournalDao().pendingCount());
        assertSqlCount("sale", 1L);
        assertSqlCount("sale_line", 1L);
        assertSqlCount("stock_log", 2L);
    }

    @Test public void insufficientCashIsRejectedBeforeAnySaleWrite() {
        try {
            service().createCashSale(UUID.randomUUID().toString(),
                    Collections.singletonList(new CashSaleService.LineInput("SKU-1", 1_000L)),
                    1_989L, TIME);
            fail("Expected insufficient cash");
        } catch (CashSaleService.InsufficientCashException expected) {
            assertEquals(1_990L, expected.totalSatang);
        }
        assertEquals(2_000L, database.productDao().findBySku("SKU-1").stockQtyMilli);
        assertEquals(0L, database.backupJournalDao().pendingCount());
        assertSqlCount("sale", 0L);
        assertSqlCount("stock_log", 1L);
    }

    @Test public void lineAmountUsesIntegerHalfUpRounding() {
        assertEquals(51L, CashSaleService.calculateLineTotal(101L, 500L));
        assertEquals(50L, CashSaleService.calculateLineTotal(100L, 500L));
    }

    private CashSaleService service() {
        return new CashSaleService(database.productDao(), database.saleDao(),
                database.stockLogDao(), database.opLogDao(), database.backupJournalDao(),
                new RoomTransactionRunner(database));
    }

    private void seedProduct(long stockQtyMilli) {
        database.runInTransaction(() -> {
            database.barcodeRegistryDao().insert(new BarcodeRegistryEntity(
                    "000123", "Test item", 1_990L, "MANUFACTURER", TIME));
            database.productDao().insert(new ProductEntity("SKU-1", "000123", "Test item",
                    1_990L, 1_000L, "ชิ้น", null, 0L, 1, TIME, TIME));
            database.stockLogDao().append(new StockLogEntity(UUID.randomUUID().toString(),
                    "SKU-1", 0L, stockQtyMilli, stockQtyMilli, "RECEIVE", null, TIME));
        });
    }

    private void assertSqlCount(String table, long expected) {
        SupportSQLiteDatabase sql = database.getOpenHelper().getWritableDatabase();
        try (Cursor cursor = sql.query("SELECT COUNT(*) FROM " + table)) {
            assertNotNull(cursor);
            if (!cursor.moveToFirst()) fail("Count query returned no row");
            assertEquals(expected, cursor.getLong(0));
        }
    }
}
