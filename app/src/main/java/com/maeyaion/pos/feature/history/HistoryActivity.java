package com.maeyaion.pos.feature.history;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.db.SaleLineEntity;
import com.maeyaion.pos.core.db.SaleReturnEntity;
import com.maeyaion.pos.core.db.SaleCancelEntity;
import com.maeyaion.pos.core.navigation.HistoryRoute;
import com.maeyaion.pos.core.sales.DebtPaymentService;
import com.maeyaion.pos.core.sales.SaleReturnService;
import com.maeyaion.pos.core.sales.SaleCancelService;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searchable paid/unpaid bill lists and immutable bill detail snapshots. */
public final class HistoryActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private LinearLayout content; private String screen;
 @Override protected void onCreate(Bundle state){super.onCreate(state);screen=getIntent().getStringExtra(HistoryRoute.EXTRA_SCREEN);renderList();}
 private void renderList(){
  ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(20,16,20,24);scroll.addView(content);setContentView(scroll);
  TextView title=new TextView(this);title.setText("unpaid".equals(screen)?"บิลค้างชำระ":"บิลจ่ายแล้ว");title.setTextSize(22);title.setGravity(Gravity.CENTER);content.addView(title);
  worker.execute(()->{
   try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());List<SaleEntity> rows="unpaid".equals(screen)?db.saleDao().unpaidCredit():"all".equals(screen)?db.saleDao().latest(200):db.saleDao().paid(200);
    runOnUiThread(()->{for(SaleEntity sale:rows){Button b=new Button(this);b.setText(sale.id+"\n"+sale.type+" · "+money(sale.total_satang)+" บาท · "+sale.created_at_utc);b.setOnClickListener(v->showDetail(sale.id));content.addView(b);}if(rows.isEmpty())addText("ไม่พบรายการ");});
   }catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}
  });
 }
 private void showDetail(String saleId){
  worker.execute(()->{
   try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());SaleEntity sale=db.saleDao().findById(saleId);List<SaleLineEntity> lines=db.saleLineDao().forSale(saleId);long balance=db.saleBalanceDao().outstanding(saleId);List<SaleReturnEntity> returns=db.saleReturnDao().forSale(saleId);SaleCancelEntity cancellation=db.saleCancelDao().findForSale(saleId);java.util.Map<String,Long> remaining=new java.util.HashMap<>();for(SaleLineEntity line:lines){long returned=db.saleReturnLineDao().returnedQty(line.id);long cancelled=db.saleCancelLineDao().cancelledQty(line.id);remaining.put(line.id,Math.max(0,line.qty_milli-returned-cancelled));}
    runOnUiThread(()->{
     content.removeAllViews();addText("รายละเอียดบิล");addText("เลขที่: "+sale.id);addText("ประเภท: "+sale.type);addText("สถานะชำระ: "+sale.payment_status);addText("สถานะบิล: "+sale.lifecycle_status);
     for(SaleReturnEntity r:returns)addText("คืนสินค้า · "+r.reason+" · คืนเงิน "+money(r.cash_refund_satang)+" บาท · ลดหนี้ "+money(r.debt_reduction_satang)+" บาท · "+r.created_at_utc);
     if(cancellation!=null)addText("ยกเลิกบิล · "+cancellation.reason+" · คืนเงิน "+money(cancellation.cash_refund_satang)+" บาท · ลดหนี้ "+money(cancellation.debt_reduction_satang)+" บาท · "+cancellation.created_at_utc);
     for(SaleLineEntity line:lines){addText(line.name_snap+" × "+qty(line.qty_milli)+" = "+money(line.line_total_satang)+" บาท");if(!"CANCELLED".equals(sale.lifecycle_status)&&remaining.get(line.id)>0){Button ret=new Button(this);ret.setText("คืนสินค้า: "+line.name_snap+" (คงเหลือ "+qty(remaining.get(line.id))+")");ret.setOnClickListener(v->showReturnDialog(sale,line));content.addView(ret);}}
     addText("ยอดสินค้า: "+money(sale.subtotal_satang)+" บาท");addText("ค่าบริการ: "+money(sale.fee_total_satang)+" บาท");addText("ยอดรวม: "+money(sale.total_satang)+" บาท");addText("ยอดค้าง: "+money(balance)+" บาท");
     if("CREDIT".equals(sale.type)&&"UNPAID".equals(sale.payment_status)&&balance>0){Button pay=new Button(this);pay.setText("รับชำระเต็มยอดค้าง");pay.setOnClickListener(v->payDebt(sale.id));content.addView(pay);}
     if(!"CANCELLED".equals(sale.lifecycle_status)){Button cancel=new Button(this);cancel.setText("ยกเลิกบิลคงเหลือ");cancel.setOnClickListener(v->showCancelDialog(sale,lines));content.addView(cancel);}
     Button back=new Button(this);back.setText("กลับรายการบิล");back.setOnClickListener(v->renderList());content.addView(back);
    });
   }catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}
  });
 }

 private void showReturnDialog(SaleEntity sale,SaleLineEntity line){
  LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,8,24,8);
  EditText qtyInput=new EditText(this);qtyInput.setHint("จำนวนคืน เช่น 1 หรือ 0.500");qtyInput.setText("1");form.addView(qtyInput);
  EditText reason=new EditText(this);reason.setHint("เหตุผลการคืน");form.addView(reason);
  EditText recipient=new EditText(this);recipient.setHint("ชื่อผู้รับเงินคืน (ถ้ามี)");form.addView(recipient);
  EditText operator=new EditText(this);operator.setHint("ผู้ดำเนินการ");form.addView(operator);
  CheckBox restock=new CheckBox(this);restock.setText("นำกลับเข้าสต๊อก");restock.setChecked(true);form.addView(restock);
  EditText noRestock=new EditText(this);noRestock.setHint("เหตุผลที่ไม่นำกลับเข้าสต๊อก");form.addView(noRestock);
  new AlertDialog.Builder(this).setTitle("คืนสินค้า: "+line.name_snap).setView(form).setNegativeButton("ยกเลิก",null)
   .setPositiveButton("บันทึกคืน",(dialog,which)->{
    try{
     long milli=new java.math.BigDecimal(qtyInput.getText().toString().trim()).multiply(java.math.BigDecimal.valueOf(1000)).longValueExact();
     SaleReturnService.LineRequest request=new SaleReturnService.LineRequest(line.id,milli,restock.isChecked(),noRestock.getText().toString());
     worker.execute(()->{try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());new SaleReturnService(db,new RoomTransactionRunner(db)).createReturn(sale.id,java.util.Collections.singletonList(request),reason.getText().toString(),recipient.getText().toString(),operator.getText().toString(),Instant.now().toString());runOnUiThread(()->showDetail(sale.id));}catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}});
    }catch(RuntimeException e){toast(msg(e));}
   }).show();
 }
 private void showCancelDialog(SaleEntity sale,List<SaleLineEntity> lines){
  LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(24,8,24,8);
  EditText reason=new EditText(this);reason.setHint("เหตุผลการยกเลิก");form.addView(reason);
  EditText recipient=new EditText(this);recipient.setHint("ชื่อผู้รับเงินคืน (ถ้ามี)");form.addView(recipient);
  EditText operator=new EditText(this);operator.setHint("ผู้ดำเนินการ");form.addView(operator);
  CheckBox restock=new CheckBox(this);restock.setText("นำสินค้าคงเหลือกลับเข้าสต๊อก");restock.setChecked(true);form.addView(restock);
  EditText noRestock=new EditText(this);noRestock.setHint("เหตุผลที่ไม่นำกลับเข้าสต๊อก");form.addView(noRestock);
  new AlertDialog.Builder(this).setTitle("ยืนยันยกเลิกบิล").setView(form).setNegativeButton("กลับ",null)
   .setPositiveButton("ยืนยัน",(dialog,which)->{
    java.util.Map<String,SaleCancelService.LineRequest> choices=new java.util.HashMap<>();
    for(SaleLineEntity line:lines)choices.put(line.id,new SaleCancelService.LineRequest(line.id,restock.isChecked(),noRestock.getText().toString()));
    worker.execute(()->{try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());new SaleCancelService(db,new RoomTransactionRunner(db)).cancelSale(sale.id,reason.getText().toString(),recipient.getText().toString(),operator.getText().toString(),choices,Instant.now().toString());runOnUiThread(()->renderList());}catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}});
   }).show();
 }
 private void payDebt(String saleId){worker.execute(()->{try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());long paid=new DebtPaymentService(db,new RoomTransactionRunner(db)).payBills(java.util.Collections.singletonList(saleId),Instant.now().toString());runOnUiThread(()->{toast("รับชำระแล้ว "+money(paid)+" บาท");renderList();});}catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}});}
 private void addText(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(16);t.setPadding(4,8,4,8);content.addView(t);}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private static String msg(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 private static String money(long n){return String.format(Locale.ROOT,"%d.%02d",n/100,n%100);}
 private static String qty(long n){return String.format(Locale.ROOT,"%d.%03d",n/1000,n%1000);}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
