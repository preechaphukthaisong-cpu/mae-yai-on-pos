package com.maeyaion.pos.core.sales;

import androidx.annotation.NonNull;
import com.maeyaion.pos.core.db.*;
import com.maeyaion.pos.core.tx.TransactionRunner;
import com.maeyaion.pos.core.sync.SyncOutboxWriter;
import com.maeyaion.pos.core.sync.SyncException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/** Cancels only quantities not already returned; all money, stock, summary and audit writes are atomic. */
public final class SaleCancelService {
 public static final class LineRequest {
  public final String saleLineId; public final boolean restock; public final String nonRestockReason;
  public LineRequest(String saleLineId,boolean restock,String reason){this.saleLineId=saleLineId;this.restock=restock;this.nonRestockReason=reason;}
 }
 private final AppDatabase db;private final TransactionRunner tx;private final SyncOutboxWriter syncOutbox;
 public SaleCancelService(@NonNull AppDatabase db,@NonNull TransactionRunner tx){this.db=Objects.requireNonNull(db);this.tx=Objects.requireNonNull(tx);this.syncOutbox=new SyncOutboxWriter(db);}
 public SaleCancelEntity cancelSale(String saleId,String reason,String recipientName,String operatorLabel,
   Map<String,LineRequest> restockChoices,String atUtc){
  require(reason,"reason");require(operatorLabel,"operator");
  String businessDate=Instant.parse(atUtc).atZone(ZoneId.of("Asia/Bangkok")).toLocalDate().toString();
  return tx.runInTransaction(()->{
   SaleEntity sale=db.saleDao().findById(saleId);
   if(sale==null||"CANCELLED".equals(sale.lifecycle_status)||db.saleCancelDao().findForSale(saleId)!=null)
    throw new IllegalStateException("ไม่พบบิลหรือบิลถูกยกเลิกแล้ว");
   List<SaleLineEntity> source=db.saleLineDao().forSale(saleId);
   long returnedGoods=db.saleReturnLineDao().returnedGoods(saleId);
   long returnedFee=db.saleReturnLineDao().returnedFee(saleId);
   long remainingGoods=0;List<SaleLineEntity> remainingLines=new ArrayList<>();List<Long> quantities=new ArrayList<>();List<Long> values=new ArrayList<>();List<LineRequest> choices=new ArrayList<>();
   for(SaleLineEntity line:source){
    long returned=db.saleReturnLineDao().returnedQty(line.id);
    long qty=Math.subtractExact(line.qty_milli,returned);
    if(qty<=0)continue;
    LineRequest choice=restockChoices==null?null:restockChoices.get(line.id);
    if(choice==null)throw new IllegalArgumentException("ต้องระบุการนำสินค้ากลับเข้าสต๊อกทุกบรรทัด");
    if(!choice.restock)require(choice.nonRestockReason,"เหตุผลที่ไม่นำกลับเข้าสต๊อก");
    long value=Math.subtractExact(line.line_total_satang,db.saleReturnLineDao().returnedValue(line.id));
    remainingLines.add(line);quantities.add(qty);values.add(value);choices.add(choice);remainingGoods=Math.addExact(remainingGoods,value);
   }
   if(quantities.isEmpty())throw new IllegalStateException("ไม่มีจำนวนคงเหลือให้ยกเลิก");
   long remainingFee=Math.subtractExact(sale.fee_total_satang,returnedFee);
   if(remainingFee<0)throw new IllegalStateException("ค่าบริการกลับรายการเกินยอดต้นฉบับ");
   boolean reduceDebt="CREDIT".equals(sale.type)&&"UNPAID".equals(sale.payment_status);
   long reversal=Math.addExact(remainingGoods,remainingFee);
   long cashRefund=reduceDebt?0:reversal;
   if(cashRefund>0)require(recipientName,"ชื่อผู้รับเงินคืน");
   String cancelId=UUID.randomUUID().toString();
   SaleCancelEntity header=new SaleCancelEntity(cancelId,saleId,reason,cashRefund,
    reduceDebt?reversal:0,remainingFee,cashRefund>0?recipientName:null,operatorLabel,atUtc);
   db.saleCancelDao().insert(header);
   List<SaleCancelLineEntity> rows=new ArrayList<>();
   long goodsCumulative=returnedGoods,feeCumulative=returnedFee;
   for(int i=0;i<quantities.size();i++){
    SaleLineEntity line=remainingLines.get(i);
    long qty=quantities.get(i),value=values.get(i);
    goodsCumulative=Math.addExact(goodsCumulative,value);
    long feeTarget=sale.subtotal_satang==0?0:roundHalfUp(sale.fee_total_satang,goodsCumulative,sale.subtotal_satang);
    long feeDelta=Math.subtractExact(feeTarget,feeCumulative);feeCumulative=feeTarget;
    LineRequest choice=choices.get(i);
    rows.add(new SaleCancelLineEntity(UUID.randomUUID().toString(),cancelId,line.id,qty,value,feeDelta,
      choice.restock?1:0,choice.restock?null:choice.nonRestockReason,null,null));
    if(choice.restock){ProductEntity p=db.productDao().findBySku(line.sku);long after=Math.addExact(p.stockQtyMilli,qty);
     db.stockLogDao().append(new StockLogEntity(UUID.randomUUID().toString(),p.sku,p.stockQtyMilli,after,qty,"CANCEL",cancelId,atUtc));
     appendProductSyncEvent(db.productDao().findBySku(p.sku),atUtc);}
    db.productDailyDao().ensure(new ProductDailyEntity(businessDate,line.sku,0,0,0,0,0,0,0,0));
    db.productDailyDao().addCancel(businessDate,line.sku,qty,value);
   }
   db.saleCancelLineDao().insertAll(rows);
   db.dailySummaryDao().ensure(new DailySummaryEntity(businessDate,0,0,0,0,0,0,0,0,0,0,0,0,atUtc));
   boolean cash="CASH".equals(sale.type);
   db.dailySummaryDao().addCancel(businessDate,cash?remainingGoods:0,cash?0:remainingGoods,
     cash?remainingFee:0,cash?0:remainingFee,reversal,atUtc);
   String settledAt=sale.settled_by_reversal_at_utc;
   SaleEntity cancelled=new SaleEntity(sale.id,sale.idem_key,sale.type,sale.payment_status,"CANCELLED",
    sale.customer_id,sale.subtotal_satang,sale.fee_total_satang,sale.total_satang,sale.cash_received_satang,
    sale.change_satang,sale.content_hash,sale.created_at_utc,sale.paid_at_utc,
    reduceDebt&&db.saleBalanceDao().outstanding(saleId)==0?atUtc:settledAt);
   db.saleDao().update(cancelled);
   String payload="{\"sale_id\":"+JSONObject.quote(saleId)+",\"cancel_id\":"+JSONObject.quote(cancelId)+",\"goods_satang\":"+remainingGoods+",\"fee_satang\":"+remainingFee+",\"reversal_satang\":"+reversal+"}";
   String op=UUID.randomUUID().toString();
   db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"SALE_CANCEL","SALE",saleId,payload,atUtc));
   db.backupJournalDao().append(new BackupJournalEntity(0,op,"SALE_CANCEL",payload,sha256(payload),5,atUtc,0));
   try {
    JSONObject event=new JSONObject();
    event.put("sale_id",saleId); event.put("cancel_id",cancelId); event.put("reason",reason);
    event.put("cash_refund_satang",cashRefund); event.put("debt_reduction_satang",reduceDebt?reversal:0);
    event.put("fee_reversal_satang",remainingFee);
    event.put("recipient_name",recipientName==null?JSONObject.NULL:recipientName);
    event.put("operator_label",operatorLabel); event.put("created_at_utc",atUtc);
    JSONArray eventLines=new JSONArray();
    for(SaleCancelLineEntity row:rows) {
     JSONObject item=new JSONObject();
     item.put("id",row.id); item.put("sale_line_id",row.sale_line_id);
     item.put("qty_milli",row.qty_milli); item.put("value_satang",row.value_satang);
     item.put("fee_satang",row.fee_satang); item.put("restock",row.restock);
     item.put("non_restock_reason",row.non_restock_reason==null?JSONObject.NULL:row.non_restock_reason);
     eventLines.put(item);
    }
    event.put("lines",eventLines);
    syncOutbox.appendUnboundInTransaction("upsert","sale_cancel",cancelId,event,atUtc);
   } catch(JSONException | SyncException error) {
    throw new IllegalStateException("Unable to append sale cancellation sync event",error);
   }
   return header;
  });
 }

 private void appendProductSyncEvent(ProductEntity product,String atUtc){
  try{
   JSONObject data=new JSONObject();
   data.put("sku",product.sku);data.put("barcode",product.barcode);
   BarcodeRegistryEntity registry=db.barcodeRegistryDao().findByBarcode(product.barcode);
   if(registry==null)throw new IllegalStateException("Product barcode registry row is missing");
   data.put("barcode_source_type",registry.sourceType);
   data.put("barcode_registry_display_name",registry.displayName);
   data.put("barcode_registry_price_satang",registry.priceSatang);
   data.put("barcode_registry_created_at_utc",registry.createdAtUtc);
   data.put("name",product.name);
   data.put("price_satang",product.priceSatang);data.put("cost_satang",product.costSatang);
   data.put("unit",product.unit);
   data.put("stock_limit_qty_milli",product.stockLimitQtyMilli==null?JSONObject.NULL:product.stockLimitQtyMilli);
   data.put("stock_qty_milli",product.stockQtyMilli);data.put("is_active",product.isActive);
   data.put("created_at_utc",product.createdAtUtc);data.put("updated_at_utc",product.updatedAtUtc);
   syncOutbox.appendUnboundInTransaction("upsert","product",product.sku,data,atUtc);
  }catch(JSONException|SyncException error){throw new IllegalStateException("Unable to append cancelled stock sync event",error);}
 }
 private static long roundHalfUp(long a,long b,long d){if(d<=0)return 0;BigInteger[] q=BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divideAndRemainder(BigInteger.valueOf(d));if(q[1].shiftLeft(1).compareTo(BigInteger.valueOf(d))>=0)q[0]=q[0].add(BigInteger.ONE);if(q[0].compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0)throw new ArithmeticException("long overflow");return q[0].longValue();}
 private static void require(String s,String f){if(s==null||s.trim().isEmpty())throw new IllegalArgumentException(f+" is required");}
 private static String sha256(String s){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte v:d)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
