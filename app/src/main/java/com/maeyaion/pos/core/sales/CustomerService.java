package com.maeyaion.pos.core.sales;

import androidx.annotation.NonNull;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.CustomerEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Normalized customer identity registration for credit sales. */
public final class CustomerService {
 private final AppDatabase db; private final TransactionRunner tx;
 public CustomerService(@NonNull AppDatabase db,@NonNull TransactionRunner tx){this.db=Objects.requireNonNull(db);this.tx=Objects.requireNonNull(tx);}
 public CustomerEntity register(@NonNull String name,String identity,@NonNull String atUtc){
  String display=collapse(Normalizer.normalize(name,Normalizer.Form.NFC));
  if(display.isEmpty())throw new IllegalArgumentException("กรอกชื่อลูกค้า");
  String nameKey=display.toLowerCase(Locale.ROOT);
  String identityKey=identity==null?"":collapse(Normalizer.normalize(identity,Normalizer.Form.NFC)).toLowerCase(Locale.ROOT);
  return tx.runInTransaction(()->{
   CustomerEntity old=db.customerDao().findByNormalizedIdentity(nameKey,identityKey);
   if(old!=null)return old;
   String id=UUID.randomUUID().toString();
   CustomerEntity row=new CustomerEntity(id,display,nameKey,identityKey,atUtc,atUtc);
   db.customerDao().insert(row);
   String payload="{\"customer_id\":"+JSONObject.quote(id)+",\"name\":"+JSONObject.quote(display)+",\"identity_key\":"+JSONObject.quote(identityKey)+"}";
   String op=UUID.randomUUID().toString();
   db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"CUSTOMER_REGISTERED","CUSTOMER",id,payload,atUtc));
   db.backupJournalDao().append(new BackupJournalEntity(0,op,"CUSTOMER_REGISTERED",payload,sha256(payload),5,atUtc,0));
   return row;
  });
 }
 private static String collapse(String s){return s.trim().replaceAll("\\s+"," ");}
 private static String sha256(String s){
  try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:d)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
  catch(Exception e){throw new IllegalStateException(e);}
 }
}
