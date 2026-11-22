package com.maeyaion.pos.core.store;

import androidx.annotation.NonNull;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.PriceHistoryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;
import com.maeyaion.pos.core.sync.SyncOutboxWriter;
import com.maeyaion.pos.core.sync.SyncException;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Store maintenance use cases. Price changes are atomic and leave immutable history. */
public final class StoreService {
 private final AppDatabase db; private final TransactionRunner tx; private final SyncOutboxWriter syncOutbox;
 public StoreService(@NonNull AppDatabase db,@NonNull TransactionRunner tx){this.db=Objects.requireNonNull(db);this.tx=Objects.requireNonNull(tx);this.syncOutbox=new SyncOutboxWriter(db);}
 public ProductEntity updateSalePrice(@NonNull String sku,long newPriceSatang,@NonNull String changedAtUtc){
  if(sku.trim().isEmpty()||!sku.equals(sku.trim()))throw new IllegalArgumentException("SKU ไม่ถูกต้อง");
  if(newPriceSatang<0)throw new IllegalArgumentException("ราคาต้องไม่ติดลบ");
  return tx.runInTransaction(()->{
   ProductEntity old=db.productDao().findBySku(sku);
   if(old==null)throw new IllegalArgumentException("ไม่พบสินค้า");
   if(old.priceSatang==newPriceSatang)return old;
   db.priceHistoryDao().append(new PriceHistoryEntity(UUID.randomUUID().toString(),sku,
    old.priceSatang,newPriceSatang,changedAtUtc));
   ProductEntity changed=new ProductEntity(old.sku,old.barcode,old.name,newPriceSatang,
    old.costSatang,old.unit,old.stockLimitQtyMilli,old.stockQtyMilli,old.isActive,
    old.createdAtUtc,changedAtUtc);
   if(db.productDao().update(changed)!=1)throw new IllegalStateException("ไม่สามารถบันทึกราคาใหม่");
   String payload="{\"sku\":"+JSONObject.quote(sku)+",\"old_price_satang\":"+old.priceSatang+
    ",\"new_price_satang\":"+newPriceSatang+"}";
   db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"PRICE_CHANGED","PRODUCT",sku,payload,changedAtUtc));
   db.backupJournalDao().append(new BackupJournalEntity(0,UUID.randomUUID().toString(),
    "PRICE_CHANGED",payload,sha256(payload),5,changedAtUtc,0));
   try {
    JSONObject snapshot=new JSONObject();
    snapshot.put("sku",changed.sku); snapshot.put("barcode",changed.barcode);
    com.maeyaion.pos.core.db.BarcodeRegistryEntity registry=db.barcodeRegistryDao().findByBarcode(changed.barcode);
    if(registry==null)throw new IllegalStateException("Product barcode registry row is missing");
    snapshot.put("barcode_source_type",registry.sourceType);
    snapshot.put("barcode_registry_display_name",registry.displayName);
    snapshot.put("barcode_registry_price_satang",registry.priceSatang);
    snapshot.put("barcode_registry_created_at_utc",registry.createdAtUtc);
    snapshot.put("name",changed.name); snapshot.put("price_satang",changed.priceSatang);
    snapshot.put("cost_satang",changed.costSatang); snapshot.put("unit",changed.unit);
    snapshot.put("stock_limit_qty_milli",changed.stockLimitQtyMilli==null?JSONObject.NULL:changed.stockLimitQtyMilli);
    snapshot.put("stock_qty_milli",changed.stockQtyMilli); snapshot.put("is_active",changed.isActive);
    snapshot.put("created_at_utc",changed.createdAtUtc); snapshot.put("updated_at_utc",changed.updatedAtUtc);
    syncOutbox.appendUnboundInTransaction("upsert","product",changed.sku,snapshot,changedAtUtc);
   } catch(JSONException | SyncException error) {
    throw new IllegalStateException("Unable to append price sync event",error);
   }
   return changed;
  });
 }
 public List<PriceHistoryEntity> priceHistory(@NonNull String sku){return db.priceHistoryDao().historyForSku(sku);}
 private static String sha256(String s){
  try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
   StringBuilder b=new StringBuilder(64);for(byte v:bytes)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();
  }catch(Exception e){throw new IllegalStateException(e);}
 }
}
