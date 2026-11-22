package com.maeyaion.pos.feature.store;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.PriceHistoryEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.navigation.StoreRoute;
import com.maeyaion.pos.core.store.StoreService;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Product price maintenance and immutable change history. */
public final class StoreActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private EditText skuInput,priceInput;private LinearLayout content,productList,historyContent,screenRoot;private int historyGeneration;private String screen;
 @Override protected void onCreate(Bundle state){super.onCreate(state);screen=getIntent().getStringExtra(StoreRoute.EXTRA_SCREEN);render();}
 private void render(){
  screenRoot=new LinearLayout(this);screenRoot.setOrientation(LinearLayout.VERTICAL);
  ScrollView scroll=new ScrollView(this);scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(16),dp(18),dp(24));content.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));scroll.addView(content);screenRoot.addView(scroll);setContentView(screenRoot);
  Button backButton=new Button(this);backButton.setText("ย้อนกลับ");backButton.setOnClickListener(v->finish());LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(56));backParams.setMargins(dp(16),dp(8),dp(16),dp(12));screenRoot.addView(backButton,backParams);
  TextView title=new TextView(this);title.setText("history".equals(screen)?"ประวัติราคา":"แก้ไขราคาขาย");title.setTextSize(23);title.setTextColor(getColor(com.maeyaion.pos.R.color.pos_forest));title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(12),0,dp(18));content.addView(title);
  Button syncConflicts=new Button(this);syncConflicts.setText("ตรวจสอบรายการซิงก์ที่ขัดแย้ง");syncConflicts.setOnClickListener(v->startActivity(new Intent(this,SyncConflictReviewActivity.class)));content.addView(syncConflicts);
  skuInput=field("SKU สินค้า");priceInput=field("ราคาขายใหม่ (บาท)");
  if(!"history".equals(screen)){Button save=new Button(this);save.setText("บันทึกราคาใหม่");content.addView(save);save.setOnClickListener(v->savePrice());}
  productList=new LinearLayout(this);productList.setOrientation(LinearLayout.VERTICAL);content.addView(productList);
  historyContent=new LinearLayout(this);historyContent.setOrientation(LinearLayout.VERTICAL);content.addView(historyContent);
  loadProducts();
 }
 private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
 private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);content.addView(e);return e;}
 private void loadProducts(){worker.execute(()->{try{List<ProductEntity> rows=AppDatabase.getInstance(getApplicationContext()).productDao().listActive();runOnUiThread(()->{TextView h=new TextView(this);h.setText("เลือกสินค้าจากรายการ");productList.addView(h);for(ProductEntity p:rows){Button b=new Button(this);b.setText(p.name+" · "+p.sku+" · "+money(p.priceSatang)+" บาท");b.setOnClickListener(v->{skuInput.setText(p.sku);if("history".equals(screen))loadHistory(p.sku);});productList.addView(b);}});}catch(RuntimeException e){runOnUiThread(()->{if(!isFinishing())toast(msg(e));});}});}
 private void savePrice(){String sku=skuInput.getText().toString().trim();long price;try{if(!priceInput.getText().toString().trim().matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("รูปแบบราคาไม่ถูกต้อง");price=new BigDecimal(priceInput.getText().toString().trim()).multiply(BigDecimal.valueOf(100)).longValueExact();}catch(RuntimeException e){toast(msg(e));return;}worker.execute(()->{try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());ProductEntity changed=new StoreService(db,new RoomTransactionRunner(db)).updateSalePrice(sku,price,Instant.now().toString());runOnUiThread(()->{toast("บันทึกราคา "+money(changed.priceSatang)+" บาทแล้ว");priceInput.setText("");});}catch(RuntimeException e){runOnUiThread(()->toast(msg(e)));}});}
 private void loadHistory(String sku){final int generation=++historyGeneration;historyContent.removeAllViews();worker.execute(()->{try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());List<PriceHistoryEntity> rows=new StoreService(db,new RoomTransactionRunner(db)).priceHistory(sku);runOnUiThread(()->{if(generation!=historyGeneration||isFinishing())return;for(PriceHistoryEntity r:rows){TextView t=new TextView(this);t.setText(money(r.old_price_satang)+" → "+money(r.new_price_satang)+" บาท\n"+r.changed_at_utc);t.setPadding(4,8,4,8);historyContent.addView(t);}if(rows.isEmpty())toast("ยังไม่มีประวัติราคา");});}catch(RuntimeException e){runOnUiThread(()->{if(generation==historyGeneration&&!isFinishing())toast(msg(e));});}});}
 private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
 private static String msg(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 private static String money(long n){return String.format(Locale.ROOT,"%d.%02d",n/100,n%100);}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
