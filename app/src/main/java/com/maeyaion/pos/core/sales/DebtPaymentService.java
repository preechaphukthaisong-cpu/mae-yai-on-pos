package com.maeyaion.pos.core.sales;
import androidx.annotation.NonNull;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.DebtPaymentEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.tx.TransactionRunner;
import com.maeyaion.pos.core.sync.SyncOutboxWriter;
import com.maeyaion.pos.core.sync.SyncException;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Full-bill debt settlement; selected bills commit or roll back as one operation. */
public final class DebtPaymentService {
 private final AppDatabase db; private final TransactionRunner tx; private final SyncOutboxWriter syncOutbox;
 public DebtPaymentService(@NonNull AppDatabase db,@NonNull TransactionRunner tx){this.db=Objects.requireNonNull(db);this.tx=Objects.requireNonNull(tx);this.syncOutbox=new SyncOutboxWriter(db);}
 public long payBills(@NonNull List<String> saleIds,@NonNull String paidAtUtc){
  if(saleIds.isEmpty())throw new IllegalArgumentException("กรุณาเลือกบิลค้างชำระ");
  Set<String> unique=new HashSet<>(saleIds);
  if(unique.size()!=saleIds.size())throw new IllegalArgumentException("มีเลขบิลซ้ำ");
  return tx.runInTransaction(()->{
   long totalPaid=0;StringBuilder payload=new StringBuilder("{\"payments\":[");
   int index=0;
   for(String id: saleIds){
    SaleEntity sale=db.saleDao().findById(id);
    if(sale==null||!"CREDIT".equals(sale.type)||!"UNPAID".equals(sale.payment_status))
     throw new IllegalStateException("บิลไม่อยู่ในสถานะค้างชำระ: "+id);
    long balance=db.saleBalanceDao().outstanding(id);
    if(balance<=0)throw new IllegalStateException("ยอดค้างชำระไม่ถูกต้อง: "+id);
    String paymentId=UUID.randomUUID().toString();
    db.debtPaymentDao().insert(new DebtPaymentEntity(paymentId,id,balance,paidAtUtc));
    SaleEntity paid=new SaleEntity(sale.id,sale.idem_key,sale.type,"PAID",sale.lifecycle_status,
     sale.customer_id,sale.subtotal_satang,sale.fee_total_satang,sale.total_satang,
     null,null,sale.content_hash,sale.created_at_utc,paidAtUtc,sale.settled_by_reversal_at_utc);
    if(db.saleDao().update(paid)!=1)throw new IllegalStateException("อัปเดตสถานะชำระเงินไม่สำเร็จ");
    totalPaid=Math.addExact(totalPaid,balance);
    if(index++>0)payload.append(',');
    payload.append("{\"payment_id\":").append(JSONObject.quote(paymentId))
     .append(",\"sale_id\":").append(JSONObject.quote(id))
     .append(",\"amount_satang\":").append(balance).append('}');
   }
   payload.append("]}");String body=payload.toString();String operation=UUID.randomUUID().toString();
   db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"DEBT_PAID","DEBT_BATCH",operation,body,paidAtUtc));
   db.backupJournalDao().append(new BackupJournalEntity(0,operation,"DEBT_PAID",body,sha256(body),5,paidAtUtc,0));
   try {
    JSONObject syncPayload = new JSONObject(body);
    syncPayload.put("paid_at_utc", paidAtUtc);
    syncOutbox.appendUnboundInTransaction("upsert","debt_payment_batch",operation,
      syncPayload,paidAtUtc);
   } catch(JSONException | SyncException error) {
    throw new IllegalStateException("Unable to append debt payment sync event",error);
   }
   return totalPaid;
  });
 }
 private static String sha256(String value){
  try{byte[] a=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:a)b.append(String.format(Locale.ROOT,"%02x",x&255));return b.toString();}
  catch(Exception e){throw new IllegalStateException(e);}
 }
}
