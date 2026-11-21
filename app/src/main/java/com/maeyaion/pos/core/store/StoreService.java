package com.maeyaion.pos.core.store;

import androidx.annotation.NonNull;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.PriceHistoryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Store maintenance use cases. Price changes are atomic and leave immutable history. */
public final class StoreService {
 private final AppDatabase db; private final TransactionRunner tx;
 public StoreService(@NonNull AppDatabase db,@NonNull TransactionRunner tx){this.db=Objects.requireNonNull(db);this.tx=Objects.requireNonNull(tx);}
 public ProductEntity updateSalePrice(@NonNull String sku,long newPriceSatang,@NonNull String changedAtUtc){
  if(sku.trim().isEmpty()||!sku.equals(sku.trim()))throw new IllegalArgumentException("SKU ไม่ถูกต้อง");
  if(newPriceSatang<0)throw new IllegalArgumentException("ราคาต้องไม่ติดลบ");
  return tx.runInTransaction(()->{
   ProductEntity old=db.productDao().findBySku(sku);
   if(old==null)throw new IllegalArgumentException("ไม่พบสินค้า");
   if(old.priceSatang==newPriceSatang)return old;
   ProductEntity changed=new ProductEntity(old.sku,old.barcode,old.name,newPriceSatang,
    old.costSatang,old.unit,old.stockLimitQtyMilli,old.stockQtyMilli,old.isActive,
    old.createdAtUtc,changedAtUtc);
   if(db.productDao().update(changed)!=1)throw new IllegalStateException("ไม่สามารถบันทึกราคาใหม่");
   db.priceHistoryDao().append(new PriceHistoryEntity(UUID.randomUUID().toString(),sku,
    old.priceSatang,newPriceSatang,changedAtUtc));
   String payload="{\"sku\":"+JSONObject.quote(sku)+",\"old_price_satang\":"+old.priceSatang+
    ",\"new_price_satang\":"+newPriceSatang+"}";
   db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"PRICE_CHANGED","PRODUCT",sku,payload,changedAtUtc));
   db.backupJournalDao().append(new BackupJournalEntity(0,UUID.randomUUID().toString(),
    "PRICE_CHANGED",payload,sha256(payload),5,changedAtUtc,0));
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
