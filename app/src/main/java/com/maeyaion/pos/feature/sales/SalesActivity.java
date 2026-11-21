package com.maeyaion.pos.feature.sales;

import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import com.maeyaion.pos.core.db.SaleEntity;
import com.maeyaion.pos.core.money.SaleCalculator;
import com.maeyaion.pos.core.scanner.OfflineBarcodeScanner;
import com.maeyaion.pos.core.speech.ThaiSpeechService;
import com.maeyaion.pos.core.navigation.SalesRoute;
import com.maeyaion.pos.core.sales.CustomerService;
import com.maeyaion.pos.core.sales.SaleService;
import com.maeyaion.pos.core.sales.DuplicateSaleException;
import com.maeyaion.pos.core.sales.DebtPaymentService;
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
public final class SalesActivity extends ComponentActivity {
 private static final int CAMERA_PERMISSION_REQUEST=7302;
 private ExecutorService cameraExecutor;
 private OfflineBarcodeScanner scanner;
 private ThaiSpeechService speech;
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<String,Long> cart=new LinkedHashMap<>();
 private EditText quantity,cashReceived,customerName,customerIdentity;
 private TextView cartText,resultText;
 private Button checkoutButton;
 private boolean checkoutInFlight;
 private String checkoutIdempotencyKey=UUID.randomUUID().toString();
 private boolean credit;
 private String screen;
 @Override protected void onCreate(Bundle state){super.onCreate(state);cameraExecutor=Executors.newSingleThreadExecutor();speech=new ThaiSpeechService(this,null);speech.initialize();screen=getIntent().getStringExtra(SalesRoute.EXTRA_SCREEN);credit="credit".equals(screen);render();}
 private void render(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(20,16,20,20);
  ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  TextView title=new TextView(this);title.setText("customers".equals(screen)?"บัญชีลูกค้าเชื่อ":credit?"ขายเชื่อ":"ขายเงินสด");title.setTextSize(23);title.setGravity(Gravity.CENTER);root.addView(title);
  if("customers".equals(screen)){loadCustomers(root);return;}
  quantity=field(root,"จำนวนสินค้าก่อนสแกน (สูงสุด 3 ตำแหน่ง)");quantity.setText("1");
  Button scanButton=new Button(this);scanButton.setText("สแกนบาร์โค้ด");scanButton.setTextSize(20);scanButton.setMinHeight((int)(64*getResources().getDisplayMetrics().density));root.addView(scanButton);scanButton.setOnClickListener(v->startScan());
  cartText=new TextView(this);cartText.setPadding(0,12,0,12);root.addView(cartText);refreshCart();
  if(credit){customerName=field(root,"ชื่อลูกค้า");customerIdentity=field(root,"ข้อมูลแยกบุคคล เช่น เบอร์โทร");}
  else cashReceived=field(root,"รับเงิน (บาท)");
  checkoutButton=new Button(this);checkoutButton.setText(credit?"บันทึกบิลขายเชื่อ":"รับชำระและบันทึกการขาย");root.addView(checkoutButton);checkoutButton.setOnClickListener(v->checkout());
  resultText=new TextView(this);root.addView(resultText);
 }
 private EditText field(LinearLayout root,String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);root.addView(e);return e;}
 private void addScannedItem(String code){
  final long qty;
  try{qty=parseQuantity(quantity.getText().toString());if(code==null||code.trim().isEmpty())throw new IllegalArgumentException("อ่านบาร์โค้ดไม่สำเร็จ");}
  catch(RuntimeException e){toast(e.getMessage());return;}
  worker.execute(()->{
   try{AppDatabase db=AppDatabase.getInstance(getApplicationContext());ProductEntity p=db.productDao().findByBarcode(code.trim());if(p==null)p=db.productDao().findBySku(code.trim());if(p==null||p.isActive!=1)throw new IllegalArgumentException("ไม่พบสินค้าที่ใช้งานได้");
    final ProductEntity found=p;runOnUiThread(()->{try{cart.merge(found.sku,qty,Math::addExact);quantity.setText("1");refreshCart();if(speech!=null)speech.speak(found.name+" จำนวน "+formatQty(qty),"sale-item-"+UUID.randomUUID());}catch(RuntimeException invalid){toast(message(invalid));}});
   }catch(RuntimeException e){runOnUiThread(()->toast(message(e)));}
  });
 }
 private void refreshCart(){
  if(cartText==null)return;
  if(cart.isEmpty()){cartText.setText("ยังไม่มีสินค้าในบิล");return;}
  StringBuilder b=new StringBuilder("รายการสินค้า\n");for(Map.Entry<String,Long> e:cart.entrySet())b.append(e.getKey()).append(" × ").append(formatQty(e.getValue())).append('\n');cartText.setText(b.toString());
 }
 private void checkout(){checkout(false);}
 private void checkout(boolean confirmDuplicate){
  if(checkoutInFlight)return;
  if(cart.isEmpty()){toast("กรุณาเพิ่มสินค้า");return;}
  final Long received;
  try{received=credit?null:parseMoney(cashReceived.getText().toString());}
  catch(RuntimeException e){toast(e.getMessage());return;}
  final String name=credit?customerName.getText().toString():null;
  final String identity=credit?customerIdentity.getText().toString():null;
  final Map<String,Long> items=new LinkedHashMap<>(cart);
  final String idempotencyKey=checkoutIdempotencyKey;
  checkoutInFlight=true;checkoutButton.setEnabled(false);
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    RoomTransactionRunner tx=new RoomTransactionRunner(db);
    String now=Instant.now().toString();String customerId=null;
    if(credit){CustomerEntity customer=new CustomerService(db,tx).register(name,identity,now);customerId=customer.id;}
    SaleCalculator.SaleType type=credit?SaleCalculator.SaleType.CREDIT:SaleCalculator.SaleType.CASH;
    com.maeyaion.pos.core.db.SaleEntity sale=new SaleService(db,tx).checkout(idempotencyKey,type,customerId,items,received,now,confirmDuplicate);
    String summary="บันทึกบิลสำเร็จ\nเลขที่: "+sale.id+"\nยอดสินค้า: "+formatMoney(sale.subtotal_satang)+" บาท\nค่าบริการ: "+formatMoney(sale.fee_total_satang)+" บาท\nยอดรวม: "+formatMoney(sale.total_satang)+" บาท"+(sale.change_satang==null?"":"\nเงินทอน: "+formatMoney(sale.change_satang)+" บาท");
    runOnUiThread(()->{checkoutInFlight=false;checkoutButton.setEnabled(true);cart.clear();checkoutIdempotencyKey=UUID.randomUUID().toString();refreshCart();resultText.setText(summary);});
   }catch(DuplicateSaleException duplicate){runOnUiThread(()->{
    checkoutInFlight=false;checkoutButton.setEnabled(true);
    new AlertDialog.Builder(this).setTitle("ตรวจพบรายการขายซ้ำ")
     .setMessage("พบบิล "+duplicate.existingSaleId.substring(0,8)+" ที่มีรายละเอียดเหมือนกันภายใน 60 นาที ต้องการบันทึกบิลซ้ำหรือไม่")
     .setNegativeButton("ไม่บันทึกซ้ำ",null)
     .setPositiveButton("ยืนยันขายซ้ำ",(dialog,which)->checkout(true)).show();
   });}catch(RuntimeException e){runOnUiThread(()->{checkoutInFlight=false;checkoutButton.setEnabled(true);toast(message(e));});}
  });
 }
 private void loadCustomers(LinearLayout root){
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    java.util.List<CustomerEntity> customers=db.customerDao().listAll();
    java.util.Map<String,java.util.List<SaleEntity>> debts=new LinkedHashMap<>();
    java.util.Map<String,Long> balances=new LinkedHashMap<>();java.util.Map<String,Long> billBalances=new LinkedHashMap<>();
    for(CustomerEntity customer:customers){
     java.util.List<SaleEntity> bills=db.saleDao().creditForCustomer(customer.id);
     java.util.List<SaleEntity> unpaid=new java.util.ArrayList<>();long balance=0;
     for(SaleEntity sale:bills)if("UNPAID".equals(sale.payment_status)){
      long outstanding=db.saleBalanceDao().outstanding(sale.id);
      if(outstanding>0){unpaid.add(sale);billBalances.put(sale.id,outstanding);balance=Math.addExact(balance,outstanding);}
     }
     debts.put(customer.id,unpaid);balances.put(customer.id,balance);
    }
    runOnUiThread(()->{
     for(CustomerEntity customer:customers){
      TextView heading=new TextView(this);
      heading.setText(customer.name+"\nยอดค้างรวม: "+formatMoney(balances.get(customer.id))+" บาท");
      heading.setTextSize(18);heading.setPadding(4,14,4,6);root.addView(heading);
      for(SaleEntity sale:debts.get(customer.id)){
       long outstanding=billBalances.get(sale.id);
       Button bill=new Button(this);
       bill.setText("บิล "+sale.id.substring(0,8)+" · ค้าง "+formatMoney(outstanding)+" บาท · รับชำระเต็มบิล");
       bill.setOnClickListener(v->payCustomerBill(sale.id));root.addView(bill);
      }
     }
     if(customers.isEmpty())toast("ยังไม่มีบัญชีลูกค้าเชื่อ");
    });
   }catch(RuntimeException e){runOnUiThread(()->toast(message(e)));}
  });
 }
 private void payCustomerBill(String saleId){
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    long paid=new DebtPaymentService(db,new RoomTransactionRunner(db)).payBills(java.util.Collections.singletonList(saleId),Instant.now().toString());
    runOnUiThread(()->{toast("รับชำระแล้ว "+formatMoney(paid)+" บาท");render();});
   }catch(RuntimeException e){runOnUiThread(()->toast(message(e)));}
  });
 }
 private void startScan(){
  if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
   ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION_REQUEST);return;
  }
  showScanner();
 }
 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
  super.onRequestPermissionsResult(requestCode,permissions,grantResults);
  if(requestCode==CAMERA_PERMISSION_REQUEST){
   if(grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)showScanner();
   else toast("ต้องอนุญาตกล้องเพื่อสแกนบาร์โค้ด");
  }
 }
 private void showScanner(){
  LinearLayout scanPage=new LinearLayout(this);scanPage.setOrientation(LinearLayout.VERTICAL);scanPage.setPadding(20,20,20,20);
  TextView title=new TextView(this);title.setText("สแกนบาร์โค้ดสินค้า");title.setTextSize(22);scanPage.addView(title);
  PreviewView preview=new PreviewView(this);preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));scanPage.addView(preview);
  Button cancel=new Button(this);cancel.setText("ยกเลิก");cancel.setOnClickListener(v->{closeScanner();render();});scanPage.addView(cancel);setContentView(scanPage);
  scanner=new OfflineBarcodeScanner(this,this,preview,cameraExecutor,ContextCompat.getMainExecutor(this),new OfflineBarcodeScanner.Listener(){
   private boolean delivered;
   @Override public void onBarcode(String rawValue,int format){
    if(delivered)return;delivered=true;closeScanner();render();addScannedItem(rawValue);
   }
   @Override public void onFailure(Exception error){runOnUiThread(()->toast("สแกนไม่สำเร็จ: "+message(error)));}
  });
 }
 private void closeScanner(){if(scanner!=null){scanner.close();scanner=null;}}
 private static long parseQuantity(String raw){String s=raw==null?"":raw.trim();if(!s.matches("[0-9]{1,9}(\\.[0-9]{1,3})?"))throw new IllegalArgumentException("จำนวนไม่ถูกต้อง");long qty=new BigDecimal(s).multiply(BigDecimal.valueOf(1000)).longValueExact();if(qty<=0)throw new IllegalArgumentException("จำนวนสินค้าต้องมากกว่า 0");return qty;}
 private static String formatQty(long milli){return String.format(Locale.ROOT,"%d.%03d",milli/1000,milli%1000);}
 private static long parseMoney(String raw){String s=raw==null?"":raw.trim();if(!s.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("จำนวนเงินไม่ถูกต้อง");return new BigDecimal(s).multiply(BigDecimal.valueOf(100)).longValueExact();}
 private static String formatMoney(long satang){return String.format(Locale.ROOT,"%d.%02d",satang/100,satang%100);}
 private void toast(String s){Toast.makeText(this,s==null?"เกิดข้อผิดพลาด":s,Toast.LENGTH_LONG).show();}
 private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 @Override protected void onDestroy(){closeScanner();if(speech!=null)speech.close();if(cameraExecutor!=null)cameraExecutor.shutdown();worker.shutdown();super.onDestroy();}
}
