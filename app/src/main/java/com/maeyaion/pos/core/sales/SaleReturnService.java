package com.maeyaion.pos.core.sales;

import androidx.annotation.NonNull;
import com.maeyaion.pos.core.db.*;
import com.maeyaion.pos.core.tx.TransactionRunner;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Partial/full merchandise returns with cumulative half-up allocation and atomic stock/debt reversal. */
public final class SaleReturnService {
 public static final class LineRequest {
  public final String saleLineId; public final long qtyMilli; public final boolean restock; public final String nonRestockReason;
  public LineRequest(String saleLineId,long qtyMilli,boolean restock,String reason){this.saleLineId=saleLineId;this.qtyMilli=qtyMilli;this.restock=restock;this.nonRestockReason=reason;}
 }
 private final AppDatabase db; private final TransactionRunner tx;
 public SaleReturnService(@NonNull AppDatabase db,@NonNull TransactionRunner tx){this.db=Objects.requireNonNull(db);this.tx=Objects.requireNonNull(tx);}
 public SaleReturnEntity createReturn(String saleId,List<LineRequest> requests,String reason,String recipientName,String operatorLabel,String atUtc){
  if(requests==null||requests.isEmpty())throw new IllegalArgumentException("เลือกรายการสินค้าที่คืน");
  require(reason,"reason");require(operatorLabel,"operator");
  String businessDate=Instant.parse(atUtc).atZone(ZoneId.of("Asia/Bangkok")).toLocalDate().toString();
  return tx.runInTransaction(()->{
   SaleEntity sale=db.saleDao().findById(saleId);
   if(sale==null||"CANCELLED".equals(sale.lifecycle_status))throw new IllegalStateException("ไม่พบบิลหรือบิลถูกยกเลิกแล้ว");
   Map<String,LineRequest> ordered=new TreeMap<>();
   for(LineRequest r:requests){if(r==null||r.qtyMilli<=0)throw new IllegalArgumentException("จำนวนคืนต้องมากกว่า 0");if(!r.restock)require(r.nonRestockReason,"เหตุผลที่ไม่นำกลับเข้าสต๊อก");if(ordered.put(r.saleLineId,r)!=null)throw new IllegalArgumentException("รายการคืนซ้ำ");}
   long previousGoods=db.saleReturnLineDao().returnedGoods(saleId);
   long previousFee=db.saleReturnLineDao().returnedFee(saleId);
   long newGoods=0;
   List<SaleLineEntity> source=new ArrayList<>();
   List<Long> values=new ArrayList<>();
   for(LineRequest r:ordered.values()){
    SaleLineEntity line=db.saleLineDao().findById(r.saleLineId);
    if(line==null||!line.sale_id.equals(saleId))throw new IllegalArgumentException("รายการสินค้าไม่อยู่ในบิลนี้");
    long already=db.saleReturnLineDao().returnedQty(line.id);
    long cancelled=db.saleCancelLineDao().cancelledQty(line.id);
    long cumulative=Math.addExact(already,r.qtyMilli);
    if(cumulative>line.qty_milli-cancelled)throw new IllegalStateException("จำนวนคืนเกินจำนวนที่ขาย");
    long target=roundHalfUp(line.line_total_satang,cumulative,line.qty_milli);
    long delta=Math.subtractExact(target,db.saleReturnLineDao().returnedValue(line.id));
    source.add(line);values.add(delta);newGoods=Math.addExact(newGoods,delta);
   }
   long targetFee;
   if(sale.subtotal_satang==0){if(sale.fee_total_satang!=0)throw new IllegalStateException("ค่าบริการของบิลไม่ถูกต้อง");targetFee=0;}
   else targetFee=roundHalfUp(sale.fee_total_satang,Math.addExact(previousGoods,newGoods),sale.subtotal_satang);
   long newFee=Math.subtractExact(targetFee,previousFee);
   if(newFee<0)throw new IllegalStateException("ค่าบริการคืนสะสมไม่ถูกต้อง");
   String returnId=UUID.randomUUID().toString();
   boolean reduceDebt="CREDIT".equals(sale.type)&&"UNPAID".equals(sale.payment_status);
   long reversal=Math.addExact(newGoods,newFee);
   long cashRefund=reduceDebt?0:reversal;
   if(cashRefund>0)require(recipientName,"ชื่อผู้รับเงินคืน");
   SaleReturnEntity header=new SaleReturnEntity(returnId,saleId,reason,cashRefund,
    reduceDebt?reversal:0,newFee,cashRefund>0?recipientName:null,operatorLabel,atUtc);
   db.saleReturnDao().insert(header);
   List<SaleReturnLineEntity> lineRows=new ArrayList<>();
   long goodsSoFar=previousGoods,feeSoFar=previousFee;
   int index=0;
   for(LineRequest r:ordered.values()){
    SaleLineEntity line=source.get(index);long value=values.get(index++);
    goodsSoFar=Math.addExact(goodsSoFar,value);
    long feeTarget=sale.subtotal_satang==0?0:roundHalfUp(sale.fee_total_satang,goodsSoFar,sale.subtotal_satang);
    long feeDelta=Math.subtractExact(feeTarget,feeSoFar);feeSoFar=feeTarget;
    lineRows.add(new SaleReturnLineEntity(UUID.randomUUID().toString(),returnId,line.id,r.qtyMilli,
      value,feeDelta,r.restock?1:0,r.restock?null:r.nonRestockReason,null,null));
    if(r.restock){ProductEntity p=db.productDao().findBySku(line.sku);long after=Math.addExact(p.stockQtyMilli,r.qtyMilli);
     db.stockLogDao().append(new StockLogEntity(UUID.randomUUID().toString(),p.sku,p.stockQtyMilli,after,r.qtyMilli,"RETURN",returnId,atUtc));}
    db.productDailyDao().ensure(new ProductDailyEntity(businessDate,line.sku,0,0,0,0,0,0,0,0));
    db.productDailyDao().addReturn(businessDate,line.sku,r.qtyMilli,value);
   }
   db.saleReturnLineDao().insertAll(lineRows);
   db.dailySummaryDao().ensure(new DailySummaryEntity(businessDate,0,0,0,0,0,0,0,0,0,0,0,0,atUtc));
   boolean cash="CASH".equals(sale.type);
   db.dailySummaryDao().addReturn(businessDate,cash?newGoods:0,cash?0:newGoods,
     cash?newFee:0,cash?0:newFee,reversal,atUtc);
   if(reduceDebt&&db.saleBalanceDao().outstanding(saleId)==0){
    SaleEntity settled=new SaleEntity(sale.id,sale.idem_key,sale.type,sale.payment_status,sale.lifecycle_status,
     sale.customer_id,sale.subtotal_satang,sale.fee_total_satang,sale.total_satang,sale.cash_received_satang,
     sale.change_satang,sale.content_hash,sale.created_at_utc,sale.paid_at_utc,atUtc);
    db.saleDao().update(settled);
   }
   String payload="{\"sale_id\":"+JSONObject.quote(saleId)+",\"return_id\":"+JSONObject.quote(returnId)+",\"goods_satang\":"+newGoods+",\"fee_satang\":"+newFee+",\"reversal_satang\":"+reversal+"}";
   String op=UUID.randomUUID().toString();
   db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"SALE_RETURN","SALE",saleId,payload,atUtc));
   db.backupJournalDao().append(new BackupJournalEntity(0,op,"SALE_RETURN",payload,sha256(payload),5,atUtc,0));
   return header;
  });
 }
 private static long roundHalfUp(long a,long b,long divisor){
  if(a<0||b<0||divisor<=0)throw new IllegalArgumentException("invalid allocation");
  BigInteger[] qr=BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divideAndRemainder(BigInteger.valueOf(divisor));
  if(qr[1].shiftLeft(1).compareTo(BigInteger.valueOf(divisor))>=0)qr[0]=qr[0].add(BigInteger.ONE);
  return qr[0].longValueExact();
 }
 private static void require(String s,String field){if(s==null||s.trim().isEmpty())throw new IllegalArgumentException(field+" is required");}
 private static String sha256(String s){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:d)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
