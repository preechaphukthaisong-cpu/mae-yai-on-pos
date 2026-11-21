package com.maeyaion.pos.feature.history;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.db.SaleLineEntity;
import com.maeyaion.pos.core.navigation.HistoryRoute;
import com.maeyaion.pos.core.sales.DebtPaymentService;
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
   try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());List<SaleEntity> rows="unpaid".equals(screen)?db.saleDao().unpaidCredit():db.saleDao().paid(200);
    runOnUiThread(()->{for(SaleEntity sale:rows){Button b=new Button(this);b.setText(sale.id+"\n"+sale.type+" · "+money(sale.total_satang)+" บาท · "+sale.created_at_utc);b.setOnClickListener(v->showDetail(sale.id));content.addView(b);}if(rows.isEmpty())addText("ไม่พบรายการ");});
   }catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}
  });
 }
 private void showDetail(String saleId){
  worker.execute(()->{
   try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());SaleEntity sale=db.saleDao().findById(saleId);List<SaleLineEntity> lines=db.saleLineDao().forSale(saleId);long balance=db.saleBalanceDao().outstanding(saleId);
    runOnUiThread(()->{
     content.removeAllViews();addText("รายละเอียดบิล");addText("เลขที่: "+sale.id);addText("ประเภท: "+sale.type);addText("สถานะชำระ: "+sale.payment_status);addText("สถานะบิล: "+sale.lifecycle_status);
     for(SaleLineEntity line:lines)addText(line.name_snap+" × "+qty(line.qty_milli)+" = "+money(line.line_total_satang)+" บาท");
     addText("ยอดสินค้า: "+money(sale.subtotal_satang)+" บาท");addText("ค่าบริการ: "+money(sale.fee_total_satang)+" บาท");addText("ยอดรวม: "+money(sale.total_satang)+" บาท");addText("ยอดค้าง: "+money(balance)+" บาท");
     if("CREDIT".equals(sale.type)&&"UNPAID".equals(sale.payment_status)&&balance>0){Button pay=new Button(this);pay.setText("รับชำระเต็มยอดค้าง");pay.setOnClickListener(v->payDebt(sale.id));content.addView(pay);}
     Button back=new Button(this);back.setText("กลับรายการบิล");back.setOnClickListener(v->renderList());content.addView(back);
    });
   }catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}
  });
 }
 private void payDebt(String saleId){worker.execute(()->{try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());long paid=new DebtPaymentService(db,new RoomTransactionRunner(db)).payBills(java.util.Collections.singletonList(saleId),Instant.now().toString());runOnUiThread(()->{toast("รับชำระแล้ว "+money(paid)+" บาท");renderList();});}catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}});}
 private void addText(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(16);t.setPadding(4,8,4,8);content.addView(t);}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private static String msg(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 private static String money(long n){return String.format(Locale.ROOT,"%d.%02d",n/100,n%100);}
 private static String qty(long n){return String.format(Locale.ROOT,"%d.%03d",n/1000,n%1000);}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
