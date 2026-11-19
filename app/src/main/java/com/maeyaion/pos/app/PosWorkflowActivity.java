package com.maeyaion.pos.app;

import android.Manifest;
import androidx.activity.ComponentActivity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.maeyaion.pos.core.barcode.BarcodeAllocator;
import com.maeyaion.pos.core.barcode.ProductRegistrationService;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.scanner.OfflineBarcodeScanner;
import com.maeyaion.pos.core.sales.CashSaleService;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PosWorkflowActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION = 7101;
    private final ExecutorService databaseExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Long> cart = new LinkedHashMap<>();
    private AppDatabase database;
    private OfflineBarcodeScanner scanner;
    private PreviewView preview;
    private TextView status;
    private TextView cartSummary;
    private EditText cashInput;
    private String screen;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = ((PosApplication) getApplication()).database();
        screen = getIntent().getStringExtra("screen");
        if ("การขาย".equals(screen)) renderSale();
        else if ("คลังสินค้า".equals(screen)) renderInventory();
        else renderBarcode();
    }

    private void renderInventory() {
        LinearLayout root = base("คลังสินค้า — เพิ่มสินค้า");
        EditText name = field("ชื่อสินค้า");
        EditText price = field("ราคาขาย (บาท)");
        EditText cost = field("ต้นทุน (บาท)");
        EditText unit = field("หน่วย เช่น ชิ้น");
        EditText stock = field("จำนวนเริ่มต้น (หน่วย)");
        Button save = button("บันทึกสินค้าและสร้างบาร์โค้ด");
        status = text("บาร์โค้ดเริ่มต้นอัตโนมัติ 2000001");
        save.setOnClickListener(v -> {
            final String productName = name.getText().toString().trim();
            final String productUnit = unit.getText().toString().trim();
            final long priceSatang, costSatang, stockMilli;
            try {
                if (productName.isEmpty() || productUnit.isEmpty()) throw new IllegalArgumentException("กรอกชื่อสินค้าและหน่วย");
                priceSatang = toSatang(price.getText().toString());
                costSatang = toSatang(cost.getText().toString());
                stockMilli = toMilli(stock.getText().toString());
            } catch (RuntimeException invalid) {
                toast(invalid.getMessage()); return;
            }
            save.setEnabled(false);
            databaseExecutor.execute(() -> {
                String now = Instant.now().toString();
                RoomTransactionRunner tx = new RoomTransactionRunner(database);
                BarcodeAllocator allocator = new BarcodeAllocator(database.barcodeRegistryDao(),
                        database.barcodeCounterDao(), tx);
                ProductRegistrationService service = new ProductRegistrationService(allocator,
                        database.productDao(), database.opLogDao(), database.backupJournalDao(), tx);
                try {
                    String sku = "SKU-" + UUID.randomUUID().toString();
                    ProductEntity product = tx.runInTransaction(() -> {
                        ProductEntity created = service.createWithSystemBarcode(UUID.randomUUID().toString(),
                                sku, productName, priceSatang, costSatang, productUnit, null, now, now);
                        if (stockMilli > 0) {
                            database.stockLogDao().append(new StockLogEntity(UUID.randomUUID().toString(),
                                    created.sku, 0L, stockMilli, stockMilli, "RECEIVE", null, now));
                        }
                        return created;
                    });
                    runOnUiThread(() -> {
                        save.setEnabled(true);
                        status.setText("บันทึกแล้ว\nสินค้า: " + product.name + "\nบาร์โค้ด: " + product.barcode
                                + "\nรหัสสินค้า: " + product.sku);
                    });
                } catch (Exception failure) {
                    runOnUiThread(() -> { save.setEnabled(true); toast("บันทึกไม่สำเร็จ: " + failure.getMessage()); });
                }
            });
        });
        root.addView(name); root.addView(price); root.addView(cost); root.addView(unit);
        root.addView(stock); root.addView(save); root.addView(status);
        root.addView(button("กลับหน้าหลัก", v -> finish()));
        setContentView(root);
        loadProducts(root);
    }

    private void loadProducts(LinearLayout root) {
        TextView heading = text("รายการสินค้า");
        root.addView(heading);
        databaseExecutor.execute(() -> {
            List<ProductEntity> products = database.productDao().listActive();
            runOnUiThread(() -> {
                for (ProductEntity p : products) {
                    root.addView(text(p.name + " | " + p.barcode + " | คงเหลือ "
                            + formatMilli(p.stockQtyMilli) + " " + p.unit));
                }
            });
        });
    }

    private void renderSale() {
        LinearLayout root = base("การขาย — เงินสด");
        status = text("สแกนบาร์โค้ดสินค้าเพื่อเพิ่มลงรายการ");
        preview = new PreviewView(this);
        preview.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(220)));
        root.addView(preview);
        root.addView(status);
        Button scan = button("เปิดกล้องสแกนบาร์โค้ด");
        scan.setOnClickListener(v -> startScanner());
        root.addView(scan);
        EditText manualBarcode = field("หรือกรอกบาร์โค้ด");
        root.addView(manualBarcode);
        root.addView(button("เพิ่มสินค้าจากบาร์โค้ด", v -> addBarcode(manualBarcode.getText().toString().trim())));
        cartSummary = text("ยังไม่มีสินค้า");
        root.addView(cartSummary);
        cashInput = field("รับเงิน (บาท)");
        root.addView(cashInput);
        root.addView(button("ยืนยันขายเงินสด", v -> completeSale()));
        root.addView(button("ล้างรายการ", v -> { cart.clear(); updateCart(); }));
        root.addView(button("กลับหน้าหลัก", v -> finish()));
        setContentView(root);
    }

    private void startScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            return;
        }
        closeScanner();
        scanner = new OfflineBarcodeScanner(this, this, preview, databaseExecutor,
                ContextCompat.getMainExecutor(this), new OfflineBarcodeScanner.Listener() {
            @Override public void onBarcode(String raw, int format) {
                addBarcode(raw);
            }
            @Override public void onFailure(Exception failure) {
                runOnUiThread(() -> status.setText("สแกนไม่สำเร็จ: " + failure.getMessage()));
            }
        });
        status.setText("กล้องพร้อมสแกน — ประมวลผลบนเครื่อง");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) startScanner();
        else if (requestCode == CAMERA_PERMISSION) toast("ต้องอนุญาตกล้องเพื่อสแกนบาร์โค้ด");
    }

    private void addBarcode(String barcode) {
        if (barcode == null || barcode.trim().isEmpty()) return;
        databaseExecutor.execute(() -> {
            ProductEntity product = database.productDao().findByBarcode(barcode.trim());
            runOnUiThread(() -> {
                if (product == null || product.isActive != 1) {
                    status.setText("ไม่พบสินค้า: " + barcode);
                    return;
                }
                cart.put(product.sku, cart.getOrDefault(product.sku, 0L) + 1000L);
                status.setText("เพิ่มแล้ว: " + product.name);
                updateCart();
            });
        });
    }

    private void updateCart() {
        if (cart.isEmpty()) { cartSummary.setText("ยังไม่มีสินค้า"); return; }
        databaseExecutor.execute(() -> {
            StringBuilder out = new StringBuilder();
            long total = 0L;
            for (Map.Entry<String, Long> item : cart.entrySet()) {
                ProductEntity p = database.productDao().findBySku(item.getKey());
                if (p == null) continue;
                long line = calculateLineTotal(p.priceSatang, item.getValue());
                total = Math.addExact(total, line);
                out.append(p.name).append(" x ").append(formatMilli(item.getValue()))
                        .append(" = ").append(formatBaht(line)).append(" บาท\n");
            }
            final String summary = out + "รวม " + formatBaht(total) + " บาท";
            runOnUiThread(() -> cartSummary.setText(summary));
        });
    }

    private void completeSale() {
        if (cart.isEmpty()) { toast("ยังไม่มีสินค้า"); return; }
        final long received;
        try { received = toSatang(cashInput.getText().toString()); }
        catch (RuntimeException invalid) { toast(invalid.getMessage()); return; }
        final List<CashSaleService.LineInput> lines = new ArrayList<>();
        for (Map.Entry<String, Long> item : cart.entrySet()) {
            lines.add(new CashSaleService.LineInput(item.getKey(), item.getValue()));
        }
        databaseExecutor.execute(() -> {
            try {
                CashSaleService service = new CashSaleService(database.productDao(), database.saleDao(),
                        database.stockLogDao(), database.opLogDao(), database.backupJournalDao(),
                        new RoomTransactionRunner(database));
                com.maeyaion.pos.core.db.SaleEntity sale = service.createCashSale(
                        UUID.randomUUID().toString(), lines, received, Instant.now().toString());
                runOnUiThread(() -> {
                    cart.clear(); updateCart();
                    status.setText("ขายสำเร็จ\nยอด " + formatBaht(sale.totalSatang)
                            + " บาท\nรับ " + formatBaht(sale.cashReceivedSatang)
                            + " บาท\nทอน " + formatBaht(sale.changeSatang) + " บาท");
                    cashInput.setText("");
                });
            } catch (Exception failure) {
                runOnUiThread(() -> toast("ขายไม่สำเร็จ (ไม่มีการตัดสต๊อก): " + failure.getMessage()));
            }
        });
    }

    private void renderBarcode() {
        LinearLayout root = base("สร้างบาร์โค้ด");
        status = text("ระบบสร้างบาร์โค้ดอัตโนมัติเมื่อเพิ่มสินค้าในเมนูคลังสินค้า\nเริ่มต้น 2000001 และเพิ่มทีละ 1");
        root.addView(status);
        root.addView(button("ไปคลังสินค้าเพื่อเพิ่มสินค้า", v -> {
            finish();
            startActivity(new android.content.Intent(this, PosWorkflowActivity.class).putExtra("screen", "คลังสินค้า"));
        }));
        root.addView(button("กลับหน้าหลัก", v -> finish()));
        setContentView(root);
    }

    private LinearLayout base(String title) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView heading = text(title);
        heading.setTextSize(22);
        root.addView(heading);
        return root;
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setPadding(dp(10), dp(8), dp(10), dp(8));
        return input;
    }
    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(16);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }
    private Button button(String label) { return button(label, v -> {}); }
    private Button button(String label, android.view.View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(listener); return b;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private void closeScanner() { if (scanner != null) { scanner.close(); scanner = null; } }
    @Override protected void onDestroy() {
        closeScanner();
        databaseExecutor.shutdown();
        super.onDestroy();
    }
    private static long toSatang(String baht) {
        return new BigDecimal(baht.trim()).setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact();
    }
    private static long toMilli(String quantity) {
        return new BigDecimal(quantity.trim()).setScale(3, RoundingMode.UNNECESSARY)
                .movePointRight(3).longValueExact();
    }
    private static long calculateLineTotal(long priceSatang, long quantityMilli) {
        BigInteger numerator = BigInteger.valueOf(priceSatang).multiply(BigInteger.valueOf(quantityMilli));
        BigInteger[] parts = numerator.divideAndRemainder(BigInteger.valueOf(1000L));
        if (parts[1].multiply(BigInteger.valueOf(2L)).compareTo(BigInteger.valueOf(1000L)) >= 0) {
            parts[0] = parts[0].add(BigInteger.ONE);
        }
        return parts[0].longValueExact();
    }
    private static String formatBaht(long satang) {
        return BigDecimal.valueOf(satang, 2).setScale(2).toPlainString();
    }
    private static String formatMilli(long milli) {
        return BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString();
    }
}
