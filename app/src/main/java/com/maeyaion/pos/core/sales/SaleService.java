package com.maeyaion.pos.core.sales;

import android.util.Base64;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.CustomerEntity;
import com.maeyaion.pos.core.db.DailySummaryEntity;
import com.maeyaion.pos.core.db.ProductDailyEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.db.SaleLineEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.money.SaleCalculator;
import com.maeyaion.pos.core.tx.TransactionRunner;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Atomic offline checkout. All stock, bill, audit and backup-journal writes share one transaction. */
public final class SaleService {
    private final AppDatabase db;
    private final TransactionRunner transactions;

    public SaleService(@NonNull AppDatabase db, @NonNull TransactionRunner transactions) {
        this.db=Objects.requireNonNull(db,"db");
        this.transactions=Objects.requireNonNull(transactions,"transactions");
    }

    @NonNull
    public SaleEntity checkout(@NonNull String idempotencyKey,
            @NonNull SaleCalculator.SaleType type, String customerId,
            @NonNull Map<String, Long> requestedQuantities,
            Long cashReceivedSatang, @NonNull String createdAtUtc) {
        requireText(idempotencyKey,"idempotencyKey");
        requireText(createdAtUtc,"createdAtUtc");
        final String businessDate = Instant.parse(createdAtUtc)
                .atZone(ZoneId.of("Asia/Bangkok")).toLocalDate().toString();
        if (requestedQuantities.isEmpty()) throw new IllegalArgumentException("ตะกร้าสินค้าว่าง");
        final Map<String,Long> quantities=new TreeMap<>();
        for(Map.Entry<String,Long> entry:requestedQuantities.entrySet()){
            String sku=requireText(entry.getKey(),"sku");
            Long qty=Objects.requireNonNull(entry.getValue(),"quantity");
            if(qty<=0) throw new IllegalArgumentException("จำนวนสินค้าต้องมากกว่า 0");
            if(quantities.put(sku,qty)!=null) throw new IllegalArgumentException("SKU ซ้ำในคำขอ");
        }
        return transactions.runInTransaction(() -> {
            SaleEntity prior=db.saleDao().findByIdempotencyKey(idempotencyKey);
            if(prior!=null) return validateReplay(prior,type,customerId,quantities,cashReceivedSatang);

            if(type==SaleCalculator.SaleType.CREDIT){
                if(customerId==null || db.customerDao().findById(customerId)==null)
                    throw new IllegalArgumentException("ต้องเลือกลูกค้าที่มีอยู่ก่อนขายเชื่อ");
            } else if(customerId!=null) {
                throw new IllegalArgumentException("บิลเงินสดห้ามผูกบัญชีลูกหนี้");
            }

            List<SaleCalculator.CartLine> cart=new ArrayList<>();
            Map<String,ProductEntity> products=new LinkedHashMap<>();
            for(Map.Entry<String,Long> entry:quantities.entrySet()){
                ProductEntity p=db.productDao().findBySku(entry.getKey());
                if(p==null || p.isActive!=1) throw new IllegalArgumentException("ไม่พบสินค้าหรือสินค้าถูกปิดใช้งาน: "+entry.getKey());
                if(p.stockQtyMilli<entry.getValue()) throw new IllegalStateException("สินค้าไม่พอ: "+p.name);
                products.put(p.sku,p);
                cart.add(new SaleCalculator.CartLine(p.sku,p.name,p.priceSatang,p.costSatang,entry.getValue()));
            }
            SaleCalculator.Result bill=SaleCalculator.calculate(cart,type,cashReceivedSatang);
            String saleId=UUID.randomUUID().toString();
            String contentHash=contentHash(type,customerId,bill);
            String paidAt=type==SaleCalculator.SaleType.CASH?createdAtUtc:null;
            SaleEntity sale=new SaleEntity(saleId,idempotencyKey,type.name(),
                    type==SaleCalculator.SaleType.CASH?"PAID":"UNPAID","COMPLETED",customerId,
                    bill.subtotalSatang,bill.feeSatang,bill.totalSatang,
                    bill.cashReceivedSatang,bill.changeSatang,contentHash,createdAtUtc,paidAt,null);
            db.saleDao().insert(sale);

            List<SaleLineEntity> lines=new ArrayList<>();
            int lineNo=1;
            for(SaleCalculator.LineTotal line:bill.lines){
                lines.add(new SaleLineEntity(UUID.randomUUID().toString(),saleId,lineNo++,
                        line.sku,line.nameSnapshot,line.priceSnapshotSatang,line.costSnapshotSatang,
                        line.quantityMilli,line.lineTotalSatang));
            }
            db.saleLineDao().insertAll(lines);

            db.dailySummaryDao().ensure(new DailySummaryEntity(businessDate,0,0,0,0,0,0,0,0,0,0,0,0,createdAtUtc));
            long cashGoods = type==SaleCalculator.SaleType.CASH ? bill.subtotalSatang : 0L;
            long creditGoods = type==SaleCalculator.SaleType.CREDIT ? bill.subtotalSatang : 0L;
            db.dailySummaryDao().addSale(businessDate,cashGoods,creditGoods,bill.feeSatang,
                    bill.totalSatang,createdAtUtc);
            for(SaleCalculator.LineTotal line:bill.lines){
                db.productDailyDao().ensure(new ProductDailyEntity(businessDate,line.sku,0,0,0,0,0,0,0,0));
                db.productDailyDao().addSale(businessDate,line.sku,line.quantityMilli,line.lineTotalSatang);
            }

            for(SaleCalculator.LineTotal line:bill.lines){
                ProductEntity product=products.get(line.sku);
                long after=Math.subtractExact(product.stockQtyMilli,line.quantityMilli);
                db.stockLogDao().append(new StockLogEntity(UUID.randomUUID().toString(),
                        line.sku,product.stockQtyMilli,after,-line.quantityMilli,"SALE",
                        saleId,createdAtUtc));
            }

            String payload=journalPayload(sale,lines);
            db.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),"SALE_CHECKOUT",
                    "SALE",saleId,payload,createdAtUtc));
            db.backupJournalDao().append(new BackupJournalEntity(0L,saleId,"SALE_CHECKOUT",
                    payload,sha256(payload),5,createdAtUtc,0));
            return sale;
        });
    }

    private SaleEntity validateReplay(SaleEntity prior, SaleCalculator.SaleType type,
            String customerId, Map<String,Long> requested, Long cashReceived) {
        if(!prior.type.equals(type.name()) || !Objects.equals(prior.customer_id,customerId)
                || !Objects.equals(prior.cash_received_satang,cashReceived))
            throw new IllegalStateException("Idempotency key ถูกใช้กับคำสั่งขายคนละรายการ");
        List<SaleLineEntity> old=db.saleLineDao().forSale(prior.id);
        if(old.size()!=requested.size()) throw new IllegalStateException("Idempotency key payload ไม่ตรงกัน");
        for(SaleLineEntity line:old){
            if(!Objects.equals(requested.get(line.sku),line.qty_milli))
                throw new IllegalStateException("Idempotency key payload ไม่ตรงกัน");
        }
        return prior;
    }

    private static String contentHash(SaleCalculator.SaleType type,String customerId,
            SaleCalculator.Result bill) {
        StringBuilder b=new StringBuilder("{\"type\":").append(JSONObject.quote(type.name()))
                .append(",\"customer\":").append(customerId==null?"null":JSONObject.quote(customerId))
                .append(",\"lines\":[");
        for(int i=0;i<bill.lines.size();i++){
            SaleCalculator.LineTotal l=bill.lines.get(i);
            if(i>0)b.append(',');
            b.append("{\"sku\":").append(JSONObject.quote(l.sku))
                    .append(",\"qty_milli\":").append(l.quantityMilli)
                    .append(",\"price_satang\":").append(l.priceSnapshotSatang).append('}');
        }
        return sha256(b.append("]}").toString());
    }

    private static String journalPayload(SaleEntity sale,List<SaleLineEntity> lines) {
        StringBuilder b=new StringBuilder("{\"sale_id\":").append(JSONObject.quote(sale.id))
                .append(",\"type\":").append(JSONObject.quote(sale.type))
                .append(",\"subtotal_satang\":").append(sale.subtotal_satang)
                .append(",\"fee_total_satang\":").append(sale.fee_total_satang)
                .append(",\"total_satang\":").append(sale.total_satang)
                .append(",\"content_hash\":").append(JSONObject.quote(sale.content_hash))
                .append(",\"lines\":[");
        for(int i=0;i<lines.size();i++){
            SaleLineEntity l=lines.get(i); if(i>0)b.append(',');
            b.append("{\"sku\":").append(JSONObject.quote(l.sku))
                    .append(",\"name\":").append(JSONObject.quote(l.name_snap))
                    .append(",\"price_satang\":").append(l.price_snap_satang)
                    .append(",\"cost_satang\":").append(l.cost_snap_satang)
                    .append(",\"qty_milli\":").append(l.qty_milli)
                    .append(",\"line_total_satang\":").append(l.line_total_satang).append('}');
        }
        return b.append("]}").toString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(64);
            for(byte x:digest) out.append(String.format(Locale.ROOT,"%02x",x&0xff));
            return out.toString();
        } catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
    private static String requireText(String value,String field){
        if(value==null || value.trim().isEmpty() || !value.equals(value.trim()))
            throw new IllegalArgumentException(field+" is required");
        return value;
    }
}
