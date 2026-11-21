package com.maeyaion.pos.core.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.maeyaion.pos.core.db.*;
import com.maeyaion.pos.core.inventory.InventoryServiceFactory;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class StoreServiceTest {
 private static final String NOW="2026-11-21T06:00:00Z";
 private Context context;private AppDatabase db;private String name;
 @Before public void setUp(){
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();name="store-test-"+System.nanoTime()+".db";
  db=Room.databaseBuilder(context,AppDatabase.class,name).setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
   .addCallback(AppDatabase.databaseCallback()).allowMainThreadQueries().build();
  db.getOpenHelper().getWritableDatabase();
 }
 @After public void tearDown(){if(db!=null)db.close();context.deleteDatabase(name);}
 @Test public void priceChangeAndHistoryCommitAtomicallyAndNoopDoesNotCreateHistory(){
  ProductEntity p=InventoryServiceFactory.create(db).createAndReceive(UUID.randomUUID().toString(),
   "SKU-PRICE","น้ำปลา",2500,1800,"ขวด",null,2000,NOW);
  StoreService store=new StoreService(db,new RoomTransactionRunner(db));
  ProductEntity changed=store.updateSalePrice(p.sku,2750,NOW);
  assertEquals(2750L,changed.priceSatang);
  assertEquals(2500L,store.priceHistory(p.sku).get(0).old_price_satang);
  assertEquals(2750L,store.priceHistory(p.sku).get(0).new_price_satang);
  assertEquals(1,store.priceHistory(p.sku).size());
  store.updateSalePrice(p.sku,2750,NOW);
  assertEquals(1,store.priceHistory(p.sku).size());
  assertNotNull(db.backupJournalDao().pendingBatch(20));
  assertTrue(db.opLogDao().countForEntity("PRODUCT",p.sku)>=2);
 }
 @Test public void invalidPriceDoesNotChangeProductOrHistory(){
  ProductEntity p=InventoryServiceFactory.create(db).createAndReceive(UUID.randomUUID().toString(),
   "SKU-PRICE-2","น้ำตาล",1000,700,"ถุง",null,1000,NOW);
  StoreService store=new StoreService(db,new RoomTransactionRunner(db));
  try{store.updateSalePrice(p.sku,-1,NOW);throw new AssertionError("negative price must fail");}
  catch(IllegalArgumentException expected){assertEquals(1000L,db.productDao().findBySku(p.sku).priceSatang);assertTrue(store.priceHistory(p.sku).isEmpty());}
 }
}
