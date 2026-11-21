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
 private final Map<String,ProductEntity> cartProducts=new LinkedHashMap<>();
 private EditText customerFirstName,customerLastName,customerPhone;
 private TextView cartText,resultText,scannerCartText;
 private Button checkoutButton,finishScanButton;
 private boolean scanningItemBusy;
 private long cartSubtotalSatang;
 private boolean checkoutInFlight;
 private String checkoutIdempotencyKey=UUID.randomUUID().toString();
 private boolean credit;
 private String screen;
 @Override protected void onCreate(Bundle state){super.onCreate(state);cameraExecutor=Executors.newSingleThreadExecutor();speech=new ThaiSpeechService(this,null);speech.initialize();screen=getIntent().getStringExtra(SalesRoute.EXTRA_SCREEN);credit="credit".equals(screen);render();}
 private void render(){
  if("customers".equals(screen)){
   LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(24));root.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));
   LinearLayout screenRoot=new LinearLayout(this);screenRoot.setOrientation(LinearLayout.VERTICAL);
   ScrollView scroll=new ScrollView(this);scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));scroll.addView(root);screenRoot.addView(scroll);setContentView(screenRoot);
   Button backButton=new Button(this);backButton.setText("ย้อนกลับ");backButton.setOnClickListener(v->finish());LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(56));backParams.setMargins(dp(16),dp(8),dp(16),dp(12));screenRoot.addView(backButton,backParams);
   TextView title=new TextView(this);title.setText("บัญชีลูกค้าเชื่อ");title.setTextSize(23);title.setTextColor(getColor(com.maeyaion.pos.R.color.pos_forest));title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);title.setGravity(Gravity.CENTER);title.setPadding(0,dp(12),0,dp(18));root.addView(title);
   loadCustomers(root);return;
  }
  renderScanLanding();
 }
 private void renderScanLanding(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(24),dp(24),dp(24),dp(24));root.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));
  Button scanButton=new Button(this);scanButton.setText("สแกนบาร์โค้ด");scanButton.setTextSize(20);scanButton.setMinHeight(dp(72));
  LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(76));params.setMargins(dp(24),0,dp(24),0);root.addView(scanButton,params);
  scanButton.setOnClickListener(v->startScan());setContentView(root);
 }
 private void renderCartReviewPage(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(24));root.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));
  LinearLayout screenRoot=new LinearLayout(this);screenRoot.setOrientation(LinearLayout.VERTICAL);
  ScrollView scroll=new ScrollView(this);scroll.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));scroll.addView(root);screenRoot.addView(scroll);setContentView(screenRoot);
  TextView title=new TextView(this);title.setText("ตรวจสอบตะกร้าสินค้า");title.setTextSize(23);title.setTextColor(getColor(com.maeyaion.pos.R.color.pos_forest));title.setGravity(Gravity.CENTER);root.addView(title);
  cartText=new TextView(this);cartText.setPadding(dp(14),dp(14),dp(14),dp(14));cartText.setBackgroundResource(com.maeyaion.pos.R.drawable.bg_pos_card);root.addView(cartText);cartText.setText("กำลังจัดเตรียมรายการสินค้า...");
  resultText=new TextView(this);resultText.setPadding(dp(14),dp(14),dp(14),dp(14));root.addView(resultText);
  checkoutButton=new Button(this);checkoutButton.setText("ยืนยันรายการสินค้า");checkoutButton.setEnabled(false);root.addView(checkoutButton);
  checkoutButton.setOnClickListener(v->renderPaymentPage());
  Button back=new Button(this);back.setText("กลับไปสแกนสินค้า");root.addView(back);back.setOnClickListener(v->showScanner());
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    StringBuilder lines=new StringBuilder();long subtotal=0L;
    for(Map.Entry<String,Long> entry:new LinkedHashMap<>(cart).entrySet()){
     ProductEntity p=db.productDao().findBySku(entry.getKey());
     if(p==null||p.isActive!=1)throw new IllegalStateException("สินค้าไม่พร้อมขาย: "+entry.getKey());
     cartProducts.put(p.sku,p);
     long line=java.math.BigInteger.valueOf(p.priceSatang).multiply(java.math.BigInteger.valueOf(entry.getValue())).add(java.math.BigInteger.valueOf(500)).divide(java.math.BigInteger.valueOf(1000)).longValueExact();
     subtotal=Math.addExact(subtotal,line);
     lines.append(p.name).append(" จำนวน ").append(formatQuantity(entry.getValue())).append(" ราคา ").append(formatMoney(line)).append(" บาท\n");
    }
    final long total=subtotal;cartSubtotalSatang=total;
    String speechText=buildSpokenCartSummary(lines.toString(),total);
    runOnUiThread(()->{
     cartText.setText(lines.toString()+"\nยอดรวมสินค้า "+formatMoney(total)+" บาท");
     resultText.setText("กำลังอ่านสรุปรายการด้วยเสียง...");
     boolean queued=speech!=null&&speech.speak(speechText,"sale-cart-"+UUID.randomUUID(),()->runOnUiThread(()->{
      if(!isFinishing()){resultText.setText("อ่านสรุปสินค้าเสร็จแล้ว กรุณายืนยันรายการ");checkoutButton.setEnabled(true);}
     }));
     if(!queued){resultText.setText("อุปกรณ์ยังไม่พร้อมอ่านเสียง กรุณาตรวจสอบรายการบนหน้าจอ");checkoutButton.setEnabled(true);}
    });
   }catch(RuntimeException e){runOnUiThread(()->{cartText.setText(message(e));checkoutButton.setEnabled(false);});}
  });
 }
 private String buildSpokenCartSummary(String lines,long total){
  StringBuilder b=new StringBuilder("สรุปรายการสินค้า ");
  for(Map.Entry<String,Long> entry:cart.entrySet()){
   ProductEntity p=cartProducts.get(entry.getKey());
   if(p!=null)b.append(p.name).append(" จำนวน ").append(formatQuantity(entry.getValue())).append(" ");
  }
  return b.append("ยอดรวมทั้งหมด ").append(formatMoney(total)).append(" บาท").toString();
 }
 private void renderPaymentPage(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(20),dp(20),dp(20),dp(20));root.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));setContentView(root);
  TextView title=new TextView(this);title.setText("เลือกวิธีชำระเงิน");title.setTextSize(24);title.setGravity(Gravity.CENTER);root.addView(title);
  TextView total=new TextView(this);total.setText("ยอดรวมสินค้า "+formatMoney(cartSubtotalSatang)+" บาท");total.setTextSize(20);total.setPadding(0,dp(24),0,dp(24));root.addView(total);
  Button cash=new Button(this);cash.setText("เงินสด");cash.setTextSize(20);root.addView(cash,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(68)));
  Button creditButton=new Button(this);creditButton.setText("เงินเชื่อ");creditButton.setTextSize(20);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(68));cp.topMargin=dp(16);root.addView(creditButton,cp);
  cash.setOnClickListener(v->checkout(false,null,null,null,false));
  creditButton.setOnClickListener(v->renderCreditForm());
 }
 private void renderCreditForm(){
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(16),dp(18),dp(24));root.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));
  ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
  TextView title=new TextView(this);title.setText("ข้อมูลลูกค้าเงินเชื่อ");title.setTextSize(23);title.setGravity(Gravity.CENTER);root.addView(title);
  long fee=com.maeyaion.pos.core.money.FeeCalculator.fivePercentHalfUp(com.maeyaion.pos.core.money.Satang.of(cartSubtotalSatang)).value();
  TextView total=new TextView(this);total.setText("ยอดสินค้า "+formatMoney(cartSubtotalSatang)+" บาท\nค่าบริการ 5% "+formatMoney(fee)+" บาท\nยอดเงินเชื่อ "+formatMoney(Math.addExact(cartSubtotalSatang,fee))+" บาท");total.setPadding(0,dp(12),0,dp(12));root.addView(total);
  customerFirstName=field(root,"ชื่อ");customerLastName=field(root,"นามสกุล");customerPhone=field(root,"เบอร์โทรศัพท์");
  customerPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
  Button confirm=new Button(this);confirm.setText("ยืนยันขายเชื่อ");root.addView(confirm);
  confirm.setOnClickListener(v->{
   String first=customerFirstName.getText().toString().trim(),last=customerLastName.getText().toString().trim(),phone=customerPhone.getText().toString().trim();
   if(first.isEmpty()||last.isEmpty()||phone.isEmpty()){toast("กรุณากรอกชื่อ นามสกุล และเบอร์โทรศัพท์ให้ครบ");return;}
   if(!phone.matches("[0-9+() -]{8,20}")){toast("รูปแบบเบอร์โทรศัพท์ไม่ถูกต้อง");return;}
   checkout(true,first,last,phone,false);
  });
 }

  private int dp(float value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
 private EditText field(LinearLayout root,String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);root.addView(e);return e;}
 private void addScannedItem(String code){
  if(code==null||code.trim().isEmpty()){finishItemSpeech("อ่านบาร์โค้ดไม่สำเร็จ");return;}
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());
    ProductEntity p=db.productDao().findByBarcode(code.trim());if(p==null)p=db.productDao().findBySku(code.trim());
    if(p==null||p.isActive!=1)throw new IllegalArgumentException("ไม่พบสินค้า");
    final ProductEntity found=p;
    runOnUiThread(()->{
     try{
      cart.merge(found.sku,1000L,Math::addExact);cartProducts.put(found.sku,found);refreshCart();
      boolean queued=speech!=null&&speech.speak(found.name,"sale-item-"+UUID.randomUUID(),()->runOnUiThread(()->resumeScanningAfterSpeech()));
      if(!queued){toast("ระบบเสียงยังไม่พร้อม");resumeScanningAfterSpeech();}
     }catch(RuntimeException invalid){toast(message(invalid));resumeScanningAfterSpeech();}
    });
   }catch(RuntimeException e){
    runOnUiThread(()->{
     boolean queued=speech!=null&&speech.speak("ไม่พบสินค้า", "sale-missing-"+UUID.randomUUID(),()->runOnUiThread(()->resumeScanningAfterSpeech()));
     if(!queued){toast(message(e));resumeScanningAfterSpeech();}
    });
   }
  });
 }
 private void resumeScanningAfterSpeech(){
  scanningItemBusy=false;
  if(scanner!=null)scanner.setScanningEnabled(true);
  if(finishScanButton!=null)finishScanButton.setEnabled(true);
 }
 private void finishItemSpeech(String text){
  boolean queued=speech!=null&&speech.speak(text,"sale-error-"+UUID.randomUUID(),()->runOnUiThread(()->resumeScanningAfterSpeech()));
  if(!queued)resumeScanningAfterSpeech();
 }

  private void refreshCart(){
  StringBuilder b=new StringBuilder();
  if(cart.isEmpty())b.append("ยังไม่มีสินค้าในตะกร้า");
  else{
   b.append("สินค้าในตะกร้า (").append(cart.size()).append(" รายการ)\n");
   for(Map.Entry<String,Long> e:cart.entrySet()){
    ProductEntity p=cartProducts.get(e.getKey());
    b.append(p==null?e.getKey():p.name).append(" × ").append(formatQuantity(e.getValue())).append('\n');
   }
  }
  if(cartText!=null)cartText.setText(b.toString());
  if(scannerCartText!=null)scannerCartText.setText(b.toString());
 }

  private void checkout(boolean creditSale,String firstName,String lastName,String phone,boolean confirmDuplicate){
  if(checkoutInFlight)return;
  if(cart.isEmpty()){toast("กรุณาสแกนสินค้า");return;}
  final Map<String,Long> items=new LinkedHashMap<>(cart);
  final long cashReceived=creditSale?0L:cartSubtotalSatang;
  checkoutInFlight=true;if(checkoutButton!=null)checkoutButton.setEnabled(false);
  worker.execute(()->{
   try{
    AppDatabase db=AppDatabase.getInstance(getApplicationContext());RoomTransactionRunner tx=new RoomTransactionRunner(db);
    String now=Instant.now().toString();String customerId=null;
    if(creditSale){CustomerEntity customer=new CustomerService(db,tx).register(firstName+" "+lastName,phone,now);customerId=customer.id;}
    SaleCalculator.SaleType type=creditSale?SaleCalculator.SaleType.CREDIT:SaleCalculator.SaleType.CASH;
    SaleEntity sale=new SaleService(db,tx).checkout(checkoutIdempotencyKey,type,customerId,items,creditSale?null:cashReceived,now,confirmDuplicate);
    String summary="บันทึกบิลสำเร็จ เลขที่ "+sale.id+" ยอดสินค้า "+formatMoney(sale.subtotal_satang)+" บาท ค่าบริการ "+formatMoney(sale.fee_total_satang)+" บาท ยอดรวม "+formatMoney(sale.total_satang)+" บาท";
    runOnUiThread(()->{
     checkoutInFlight=false;cart.clear();cartProducts.clear();cartSubtotalSatang=0;checkoutIdempotencyKey=UUID.randomUUID().toString();closeScanner();renderScanLanding();toast(summary);
    });
   }catch(DuplicateSaleException duplicate){runOnUiThread(()->{
    checkoutInFlight=false;
    new AlertDialog.Builder(this).setTitle("ตรวจพบรายการขายซ้ำ")
     .setMessage("พบบิล "+duplicate.existingSaleId.substring(0,8)+" ที่มีรายละเอียดเหมือนกันภายใน 60 นาที ต้องการบันทึกบิลซ้ำหรือไม่")
     .setNegativeButton("ไม่บันทึกซ้ำ",null)
     .setPositiveButton("ยืนยันขายซ้ำ",(dialog,which)->checkout(creditSale,firstName,lastName,phone,true)).show();
   });}catch(RuntimeException e){runOnUiThread(()->{checkoutInFlight=false;toast(message(e));});}
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
  LinearLayout scanPage=new LinearLayout(this);scanPage.setOrientation(LinearLayout.VERTICAL);scanPage.setPadding(dp(16),dp(12),dp(16),dp(12));scanPage.setBackgroundColor(getColor(com.maeyaion.pos.R.color.pos_background));
  TextView title=new TextView(this);title.setText("สแกนบาร์โค้ดสินค้า");title.setTextSize(22);title.setGravity(Gravity.CENTER);scanPage.addView(title,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(48)));
  PreviewView preview=new PreviewView(this);preview.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f));scanPage.addView(preview);
  scannerCartText=new TextView(this);scannerCartText.setPadding(dp(12),dp(10),dp(12),dp(10));scannerCartText.setBackgroundResource(com.maeyaion.pos.R.drawable.bg_pos_card);scanPage.addView(scannerCartText,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(112)));refreshCart();
  finishScanButton=new Button(this);finishScanButton.setText("เสร็จสิ้น — ตรวจสอบตะกร้า");finishScanButton.setOnClickListener(v->{if(scanningItemBusy){toast("รอเสียงพูดจบก่อน");return;}closeScanner();scannerCartText=null;finishScanButton=null;renderCartReviewPage();});scanPage.addView(finishScanButton,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(56)));
  setContentView(scanPage);
  scanner=new OfflineBarcodeScanner(this,this,preview,cameraExecutor,ContextCompat.getMainExecutor(this),new OfflineBarcodeScanner.Listener(){
   @Override public void onBarcode(String rawValue,int format){if(scanningItemBusy)return;scanningItemBusy=true;if(finishScanButton!=null)finishScanButton.setEnabled(false);if(scanner!=null)scanner.setScanningEnabled(false);addScannedItem(rawValue);}
   @Override public void onFailure(Exception error){runOnUiThread(()->toast("สแกนไม่สำเร็จ: "+message(error)));}
  });
 }
 private void closeScanner(){if(scanner!=null){scanner.close();scanner=null;}}
 private static String formatQty(long milli){return String.format(Locale.ROOT,"%d.%03d",milli/1000,milli%1000);}
 private static String formatQuantity(long milli){return milli%1000==0?Long.toString(milli/1000):formatQty(milli);}
 private static long parseMoney(String raw){String s=raw==null?"":raw.trim();if(!s.matches("[0-9]{1,12}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("จำนวนเงินไม่ถูกต้อง");return new BigDecimal(s).multiply(BigDecimal.valueOf(100)).longValueExact();}
 private static String formatMoney(long satang){return String.format(Locale.ROOT,"%d.%02d",satang/100,satang%100);}
 private void toast(String s){Toast.makeText(this,s==null?"เกิดข้อผิดพลาด":s,Toast.LENGTH_LONG).show();}
 private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
 @Override protected void onDestroy(){closeScanner();if(speech!=null)speech.close();if(cameraExecutor!=null)cameraExecutor.shutdown();worker.shutdown();super.onDestroy();}
}
