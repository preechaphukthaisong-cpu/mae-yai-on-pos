package com.maeyaion.pos.core.sales;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.maeyaion.pos.core.db.*;
import com.maeyaion.pos.core.inventory.InventoryServiceFactory;
import com.maeyaion.pos.core.money.SaleCalculator;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public final class SaleServiceTest {
 private static final String NOW="2026-11-21T04:00:00Z";
 private Context context;private AppDatabase db;private String name;private RoomTransactionRunner tx;
 @Before public void setUp(){
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();name="sales-test-"+System.nanoTime()+".db";
  db=Room.databaseBuilder(context,AppDatabase.class,name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
   .addCallback(AppDatabase.databaseCallback()).allowMainThreadQueries().build();
  db.getOpenHelper().getWritableDatabase();tx=new RoomTransactionRunner(db);
 }
 @After public void tearDown(){if(db!=null)db.close();context.deleteDatabase(name);}

 @Test public void cashCheckoutPostsStockLedgerAndDailySummaryAtomically(){
  ProductEntity p=InventoryServiceFactory.create(db).createAndReceive(UUID.randomUUID().toString(),
   "SKU-CASH","น้ำดื่ม",1000,500,"ขวด",null,5000,NOW);
  SaleEntity sale=new SaleService(db,tx).checkout("cash-key",SaleCalculator.SaleType.CASH,null,
   Collections.singletonMap(p.sku,2000L),5000L,NOW);
  assertEquals("PAID",sale.payment_status);assertEquals(0L,sale.fee_total_satang);
  assertEquals(2000L,db.productDao().findBySku(p.sku).stockQtyMilli);
  assertEquals(1,db.saleLineDao().forSale(sale.id).size());
  assertEquals("SALE",db.stockLogDao().latest(1).get(0).reason);
  DailySummaryEntity summary=db.dailySummaryDao().find("2026-11-21");
  assertNotNull(summary);assertEquals(2000L,summary.cash_sales_satang);assertEquals(2000L,summary.net_sales_satang);
  SaleEntity replay=new SaleService(db,tx).checkout("cash-key",SaleCalculator.SaleType.CASH,null,
   Collections.singletonMap(p.sku,2000L),5000L,NOW);
  assertEquals(sale.id,replay.id);assertEquals(1,db.saleDao().latest(10).size());
 }

 @Test public void creditSaleChargesFivePercentAndFullDebtPaymentIsAtomic(){
  ProductEntity p=InventoryServiceFactory.create(db).createAndReceive(UUID.randomUUID().toString(),
   "SKU-CREDIT","ข้าวสาร",10000,8000,"ถุง",null,3000,NOW);
  CustomerEntity customer=new CustomerService(db,tx).register("ลูกค้าทดสอบ","0812345678",NOW);
  SaleEntity sale=new SaleService(db,tx).checkout("credit-key",SaleCalculator.SaleType.CREDIT,customer.id,
   Collections.singletonMap(p.sku,1000L),null,NOW);
  assertEquals(500L,sale.fee_total_satang);assertEquals(10500L,sale.total_satang);
  assertEquals("UNPAID",sale.payment_status);
  assertEquals(10500L,new DebtPaymentService(db,tx).payBills(Collections.singletonList(sale.id),NOW));
  assertEquals("PAID",db.saleDao().findById(sale.id).payment_status);
  assertNotNull(db.debtPaymentDao().findForSale(sale.id));
 }

 @Test public void partialReturnThenCancelReversesMoneyAndRestocksWithoutDuplicateQuantity(){
  ProductEntity p=InventoryServiceFactory.create(db).createAndReceive(UUID.randomUUID().toString(),
   "SKU-RETURN","สบู่",1000,400,"ก้อน",null,5000,NOW);
  SaleEntity sale=new SaleService(db,tx).checkout("return-key",SaleCalculator.SaleType.CASH,null,
   Collections.singletonMap(p.sku,3000L),5000L,NOW);
  SaleLineEntity line=db.saleLineDao().forSale(sale.id).get(0);
  SaleReturnService returns=new SaleReturnService(db,tx);
  returns.createReturn(sale.id,Collections.singletonList(new SaleReturnService.LineRequest(line.id,1000,true,null)),
   "สินค้าเสีย","ลูกค้า","ผู้ขาย",NOW);
  assertEquals(3000L,db.productDao().findBySku(p.sku).stockQtyMilli);
  Map<String,SaleCancelService.LineRequest> choices=new HashMap<>();
  choices.put(line.id,new SaleCancelService.LineRequest(line.id,true,null));
  new SaleCancelService(db,tx).cancelSale(sale.id,"ยกเลิกส่วนที่เหลือ","ลูกค้า","ผู้ขาย",choices,NOW);
  assertEquals(5000L,db.productDao().findBySku(p.sku).stockQtyMilli);
  assertEquals("CANCELLED",db.saleDao().findById(sale.id).lifecycle_status);
  assertEquals(0L,db.dailySummaryDao().find("2026-11-21").net_sales_satang);
  assertEquals(2,db.stockLogDao().historyForSku(p.sku).size());
 }
}
