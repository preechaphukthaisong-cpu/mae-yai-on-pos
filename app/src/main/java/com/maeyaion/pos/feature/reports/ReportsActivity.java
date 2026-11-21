package com.maeyaion.pos.feature.reports;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.DailySummaryEntity;
import com.maeyaion.pos.core.db.ProductRankRow;
import com.maeyaion.pos.core.navigation.ReportsRoute;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Daily, weekly, monthly and product ranking reports from persisted summary ledgers. */
public final class ReportsActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();private LinearLayout content,results;private int loadGeneration;private String screen;private android.widget.EditText fromInput,toInput;
 @Override protected void onCreate(Bundle state){super.onCreate(state);screen=getIntent().getStringExtra(ReportsRoute.EXTRA_SCREEN);render();}
 private void render(){
  ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(20,16,20,24);scroll.addView(content);setContentView(scroll);
  TextView title=new TextView(this);title.setText("รายงานยอดขาย · "+label(screen));title.setTextSize(22);title.setGravity(Gravity.CENTER);content.addView(title);
  results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);content.addView(results);
  if("monthly".equals(screen)){
   LocalDate today=LocalDate.now(ZoneId.of("Asia/Bangkok"));
   fromInput=new android.widget.EditText(this);fromInput.setSingleLine(true);fromInput.setHint("วันที่เริ่ม YYYY-MM-DD");fromInput.setText(today.withDayOfMonth(1).toString());content.addView(fromInput);
   toInput=new android.widget.EditText(this);toInput.setSingleLine(true);toInput.setHint("วันที่สิ้นสุด YYYY-MM-DD");toInput.setText(today.toString());content.addView(toInput);
   android.widget.Button custom=new android.widget.Button(this);custom.setText("ดูรายงานตามช่วงวันที่");content.addView(custom);custom.setOnClickListener(v->loadCustomRange());
  }
  load();
 }

 private void loadCustomRange(){
  final LocalDate from,to;
  try{from=LocalDate.parse(fromInput.getText().toString().trim());to=LocalDate.parse(toInput.getText().toString().trim());if(from.isAfter(to))throw new IllegalArgumentException("วันที่เริ่มต้องไม่เกินวันที่สิ้นสุด");}
  catch(RuntimeException e){Toast.makeText(this,"รูปแบบวันที่ไม่ถูกต้อง ใช้ YYYY-MM-DD",Toast.LENGTH_LONG).show();return;}
  final int generation=++loadGeneration;clearResults();
  worker.execute(()->{try{List<DailySummaryEntity> rows=AppDatabase.getInstance(getApplicationContext()).dailySummaryDao().range(from.toString(),to.toString());runOnUiThread(()->{if(generation!=loadGeneration||isFinishing())return;
   long net=0;for(DailySummaryEntity r:rows){net=Math.addExact(net,r.net_sales_satang);addText(r.business_date+" · "+money(r.net_sales_satang)+" บาท");}
   addText("ยอดสุทธิรวม "+from+" ถึง "+to+" = "+money(net)+" บาท");
   if(rows.isEmpty())addText("ยังไม่มีข้อมูลยอดขาย");
  });}catch(RuntimeException e){runOnUiThread(()->{if(generation==loadGeneration&&!isFinishing())Toast.makeText(this,e.getMessage()==null?"โหลดรายงานไม่สำเร็จ":e.getMessage(),Toast.LENGTH_LONG).show();});}});
 }
 private void load(){
  final int generation=++loadGeneration;clearResults();
  worker.execute(()->{try{
   AppDatabase db=AppDatabase.getInstance(getApplicationContext());LocalDate today=LocalDate.now(ZoneId.of("Asia/Bangkok"));
   if("top".equals(screen)||"bottom".equals(screen)){
    LocalDate from=today.minusDays(29);List<ProductRankRow> rows="top".equals(screen)?db.productDailyDao().topSellers(from.toString(),today.toString()):db.productDailyDao().leastSellers(from.toString(),today.toString());
    runOnUiThread(()->{if(generation!=loadGeneration||isFinishing())return;int rank=1;for(ProductRankRow r:rows)addText(rank++ + ". "+r.name+" · "+qty(r.net_qty_milli)+" · "+money(r.net_goods_satang)+" บาท");if(rows.isEmpty())addText("ยังไม่มีข้อมูลยอดขายสุทธิในช่วงนี้");});
   }else{
    LocalDate from="daily".equals(screen)?today:"weekly".equals(screen)?today.minusDays(6):today.withDayOfMonth(1);
    List<DailySummaryEntity> rows=db.dailySummaryDao().range(from.toString(),today.toString());
    runOnUiThread(()->{if(generation!=loadGeneration||isFinishing())return;long net=0,cash=0,credit=0,fee=0;for(DailySummaryEntity r:rows){net=Math.addExact(net,r.net_sales_satang);cash=Math.addExact(cash,r.cash_sales_satang);credit=Math.addExact(credit,r.credit_sales_satang);long netFee=Math.subtractExact(r.fees_satang,Math.addExact(Math.addExact(r.cash_returned_fee_satang,r.credit_returned_fee_satang),Math.addExact(r.cash_cancelled_fee_satang,r.credit_cancelled_fee_satang)));fee=Math.addExact(fee,netFee);addText(r.business_date+"\nเงินสด: "+money(r.cash_sales_satang)+" · ขายเชื่อ: "+money(r.credit_sales_satang)+" · ค่าบริการสุทธิ: "+money(netFee)+"\nยอดสุทธิ: "+money(r.net_sales_satang)+" บาท");}addText("รวมช่วงวันที่ "+from+" ถึง "+today+"\nเงินสด "+money(cash)+" บาท\nขายเชื่อ "+money(credit)+" บาท\nค่าบริการ "+money(fee)+" บาท\nยอดสุทธิ "+money(net)+" บาท");if(rows.isEmpty())addText("ยังไม่มีข้อมูลยอดขาย");});
   }
  }catch(RuntimeException e){runOnUiThread(()->{if(generation==loadGeneration&&!isFinishing())Toast.makeText(this,e.getMessage()==null?"โหลดรายงานไม่สำเร็จ":e.getMessage(),Toast.LENGTH_LONG).show();});}});
 }
 private void clearResults(){if(results!=null)results.removeAllViews();}
 private void addText(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(16);t.setPadding(4,10,4,10);results.addView(t);}
 private static String label(String s){if("weekly".equals(s))return "รายสัปดาห์";if("monthly".equals(s))return "รายเดือน";if("top".equals(s))return "ขายดี 20 อันดับ";if("bottom".equals(s))return "ขายน้อยสุด 20 อันดับ";return "รายวัน";}
 private static String money(long n){return String.format(Locale.ROOT,"%d.%02d",n/100,n%100);}
 private static String qty(long n){return String.format(Locale.ROOT,"%d.%03d",n/1000,n%1000);}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
