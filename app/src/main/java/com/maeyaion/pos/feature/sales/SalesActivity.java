package com.maeyaion.pos.feature.sales;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.CustomerEntity;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.money.SaleCalculator;
import com.maeyaion.pos.core.navigation.SalesRoute;
import com.maeyaion.pos.core.sales.CustomerService;
import com.maeyaion.pos.core.sales.SaleService;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline checkout screen for cash and credit sales, plus customer-account overview. */
public final class SalesActivity extends Activity {
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<String,Long> cart=new LinkedHashMap<>();
 private EditText productCode,quantity,cashReceived,customerName,customerIdentity;
 private TextView cartText,resultText;
 private boolean credit;
 private String screen;
 @Override protected void onCreate(Bundle state){super.onCreate(state);screen=getIntent().getStringExtra(SalesRoute.EXTRA_SCREEN);credit="credit".equals(screen);render();}
 private void render(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,16,20,20);
  ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  TextView title=new TextView(this);title.setText("customers".equals(screen)?"บัญชีลูกค้าเชื่อ":credit?"ขายเชื่อ":"ขายเงินสด");title.setTextSize(23);title.setGravity(Gravity.CENTER);root.addView(title);
  if("customers".equals(screen)){loadCustomers(root);return;}
  productCode=field(root,"บาร์โค้ดหรือ SKU");quantity=field(root,"จำนวน (สูงสุด 3 ตำแหน่ง)");quantity.setText("1");
  Button add=new Button(this);add.setText("เพิ่มสินค้าในบิล");root.addView(add);add.setOnClickListener(v->addItem());
  cartText=new TextView(this);cartText.setPadding(0,12,0,12);root.addView(cartText);refreshCart();
  if(credit){customerName=field(root,"ชื่อลูกค้า");customerIdentity=field(root,"ข้อมูลแยกบุคคล เช่น เบอร์โทร");}
  else cashReceived=field(root,"รับเงิน (บาท)");
  Button checkout=new Button(this);checkout.setText(credit?"บันทึกบิลขายเชื่อ":"รับชำระและบันทึกการขาย");root.addView(checkout);checkout.setOnClickListener(v->checkout());
  resultText=new TextView(this);root.addView(resultText);
 }
 private EditText field(LinearLayout root,String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);root.addView(e);return e;}
 private void addItem(){
  String code=productCode.getText().toString().trim();long qty;
  try{qty=parseQuantity(quantity.getText().toString());if(code.isEmpty())throw new IllegalArgumentException("กรอกรหัสสินค้า");}
  catch(RuntimeException e){toast(e.getMessage());return;}
  worker.execute(()->{
   try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());ProductEntity p=db.productDao().findByBarcode(code);if(p==null)p=db.productDao().findBySku(code);if(p==null||p.isActive!=1)throw new IllegalArgumentException("ไม่พบสินค้าที่ใช้งานได้");
    final ProductEntity found=p;runOnUiThread(()->{cart.merge(found.sku,qty,Math::addExact);productCode.setText("");refreshCart();});
   }catch(RuntimeException e){runOnUiThread(()->toast(message(e)));}
  });
 }
 private void refreshCart(){
  if(cartText==null)return;
  if(cart.isEmpty()){cartText.setText("ยังไม่มีสินค้าในบิล");return;}
  StringBuilder b=new StringBuilder("รายการสินค้า\n");for(Map.Entry<String,Long> e:cart.entrySet())b.append(e.getKey()).append(" × ").append(formatQty(e.getValue())).append('\n');cartText.setText(b.toString());
 }
 private void checkout(){
  if(cart.isEmpty()){toast("กรุณาเพิ่มสินค้า");return;}
  final Long received;
  try{received=credit?null:parseMoney(cashReceived.getText().toString());}
  catch(RuntimeException e){toast(e.getMessage());return;}
  final String name=credit?customerName.getText().toString():null;
  final String identity=credit?customerIdentity.getText().toString():null;
  final Map<String,Long> items=new LinkedHashMap<>(cart);
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    RoomTransactionRunner tx=new RoomTransactionRunner(db);
    String now=Instant.now().toString();String customerId=null;
    if(credit){CustomerEntity customer=new CustomerService(db,tx).register(name,identity,now);customerId=customer.id;}
    SaleCalculator.SaleType type=credit?SaleCalculator.SaleType.CREDIT:SaleCalculator.SaleType.CASH;
    com.maeyaion.pos.core.db.SaleEntity sale=new SaleService(db,tx).checkout(UUID.randomUUID().toString(),type,customerId,items,received,now);
    String summary="บันทึกบิลสำเร็จ\nเลขที่: "+sale.id+"\nยอดสินค้า: "+formatMoney(sale.subtotal_satang)+" บาท\nค่าบริการ: "+formatMoney(sale.fee_total_satang)+" บาท\nยอดรวม: "+formatMoney(sale.total_satang)+" บาท"+(sale.change_satang==null?"":"\nเงินทอน: "+formatMoney(sale.change_satang)+" บาท");
    runOnUiThread(()->{cart.clear();refreshCart();resultText.setText(summary);});
   }catch(RuntimeException e){runOnUiThread(()->toast(message(e)));}
  });
 }
 private void loadCustomers(LinearLayout root){
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    java.util.List<CustomerEntity> customers=db.customerDao().listAll();
    java.util.List<String> labels=new java.util.ArrayList<>();
    for(CustomerEntity customer:customers){
     long balance=0;
     for(com.maeyaion.pos.core.db.SaleEntity sale:db.saleDao().creditForCustomer(customer.id))
      if("UNPAID".equals(sale.payment_status))balance=Math.addExact(balance,db.saleBalanceDao().outstanding(sale.id));
     labels.add(customer.name+"\\nยอดค้าง: "+formatMoney(balance)+" บาท");
    }
    runOnUiThread(()->{for(String label:labels){TextView row=new TextView(this);row.setText(label);row.setPadding(4,12,4,12);root.addView(row);}});
   }catch(RuntimeException e){runOnUiThread(()->toast(message(e)));}
  });
 }
 private static long parseQuantity(String raw){String s=raw==null?"":raw.trim();if(!s.matches("[0-9]{1,9}(\\.[0-9]{1,3})?"))throw new IllegalArgumentException("จำนวนไม่ถูกต้อง");return new BigDecimal(s).multiply(BigDecimal.valueOf(1000)).longValueExact();}
 private static String formatQty(long milli){return String.format(Locale.ROOT,"%d.%03d",milli/1000,milli%1000);}
 private static long parseMoney(String raw){String s=raw==null?"":raw.trim();if(!s.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("จำนวนเงินไม่ถูกต้อง");return new BigDecimal(s).multiply(BigDecimal.valueOf(100)).longValueExact();}
 private static String formatMoney(long satang){return String.format(Locale.ROOT,"%d.%02d",satang/100,satang%100);}
 private void toast(String s){Toast.makeText(this,s==null?"เกิดข้อผิดพลาด":s,Toast.LENGTH_LONG).show();}
 private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 @Override protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
