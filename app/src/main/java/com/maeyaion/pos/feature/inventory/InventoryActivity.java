package com.maeyaion.pos.feature.inventory;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.maeyaion.pos.app.PosApplication;
import com.maeyaion.pos.core.db.ProductEntity;
import com.maeyaion.pos.core.db.StockLogEntity;
import com.maeyaion.pos.core.inventory.InventoryService;
import com.maeyaion.pos.core.inventory.InventoryServiceFactory;
import com.maeyaion.pos.core.scanner.OfflineBarcodeScanner;
import com.maeyaion.pos.core.speech.ThaiSpeechService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Complete offline inventory UI: receive, read-only product list, stock count and low-stock list. */
public final class InventoryActivity extends ComponentActivity {
    private static final int CAMERA_PERMISSION_REQUEST = 7301;
    private InventoryService inventory;
    private LinearLayout page;
    private ExecutorService cameraExecutor;
    private OfflineBarcodeScanner scanner;
    private Runnable afterScan;
    private ThaiSpeechService speech;
    private String initialScreen = "menu";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        inventory = InventoryServiceFactory.create(((PosApplication) getApplication()).database());
        cameraExecutor = Executors.newSingleThreadExecutor();
        speech = new ThaiSpeechService(this, null);
        speech.initialize();
        initialScreen = getIntent().getStringExtra("inventory_screen");
        if (initialScreen == null) initialScreen = "menu";
        render(initialScreen);
    }

    private void render(String screen) {
        closeScanner();
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 20, 24, 24);
        addButton("กลับเมนูคลังสินค้า", () -> render("menu"));
        switch (screen) {
            case "receive": renderReceive(); break;
            case "all": renderAll(); break;
            case "count": renderCount(); break;
            case "limit": renderLimits(); break;
            case "history": renderHistory(); break;
            default: renderMenu(); break;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        setContentView(scroll);
    }

    private void renderMenu() {
        heading("คลังสินค้า");
        addButton("เพิ่มสินค้าเข้าคลัง/รับสินค้า", () -> render("receive"));
        addButton("สินค้าทั้งหมด", () -> render("all"));
        addButton("ตรวจนับสต๊อก", () -> render("count"));
        addButton("ลิมิตสต๊อก", () -> render("limit"));
    }

    private void renderReceive() {
        heading("เพิ่มสินค้าเข้าคลัง/รับสินค้า");
        EditText quantity = field("จำนวนรับ (หน่วยย่อยได้ เช่น 1.5)", "1");
        TextView scanned = text("สแกนบาร์โค้ดสินค้าเดิมเพื่อรับเข้า หากไม่พบจะเปิดแบบฟอร์มสินค้าใหม่");
        page.addView(scanned);
        addButton("สแกนบาร์โค้ด", () -> startScan(barcode -> {
            ProductEntity product = inventory.findByBarcode(barcode);
            if (product != null) {
                try {
                    long qty = quantityMilli(quantity.getText().toString());
                    ProductEntity updated = inventory.receive(UUID.randomUUID().toString(),
                            barcode, qty, now());
                    toast("รับสินค้าเพิ่มแล้ว: " + updated.name + " คงเหลือ " + quantityText(updated.stockQtyMilli));
                    render("receive");
                } catch (RuntimeException error) { toast(message(error)); }
            } else {
                toast("ไม่พบสินค้า เปิดแบบฟอร์มเพิ่มสินค้าใหม่");
                renderNewProduct(quantity.getText().toString());
            }
        }));
    }

    private void renderNewProduct(String defaultQty) {
        heading("ลงทะเบียนสินค้าใหม่");
        addText("ระบบจะสร้างบาร์โค้ด Code 128 ให้อัตโนมัติ เริ่มที่ 2000001");
        EditText name = field("ชื่อสินค้า *", "");
        EditText price = field("ราคาขาย (บาท) *", "");
        EditText cost = field("ต้นทุน (บาท) *", "");
        EditText unit = field("หน่วยนับ *", "ชิ้น");
        EditText limit = field("ลิมิตสต๊อก (เว้นว่าง = ไม่กำหนด)", "");
        EditText qty = field("จำนวนรับเข้า *", defaultQty);
        addButton("บันทึกสินค้าและรับเข้าคลัง", () -> {
            try {
                String productName = required(name, "กรอกชื่อสินค้า");
                String productUnit = required(unit, "กรอกหน่วยนับ");
                long priceSatang = moneySatang(price.getText().toString());
                long costSatang = moneySatang(cost.getText().toString());
                Long limitMilli = limit.getText().toString().trim().isEmpty()
                        ? null : quantityMilli(limit.getText().toString());
                long receivedMilli = quantityMilli(qty.getText().toString());
                String sku = "SKU-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
                ProductEntity created = inventory.createAndReceive(UUID.randomUUID().toString(),
                        sku, productName, priceSatang, costSatang, productUnit,
                        limitMilli, receivedMilli, now());
                speakNewProduct();
                toast("บันทึกสินค้าแล้ว บาร์โค้ด " + created.barcode);
                render("all");
            } catch (RuntimeException error) { toast(message(error)); }
        });
    }

    private void renderAll() {
        heading("สินค้าทั้งหมด");
        addText("เรียงตามจำนวนคงเหลือจากน้อยไปมาก · หน้านี้ดูข้อมูลอย่างเดียว");
        addButton("สแกนค้นหาสินค้า", () -> startScan(barcode -> {
            ProductEntity found = inventory.findByBarcode(barcode);
            if (found == null) toast("ไม่พบสินค้าในคลัง");
            else showProduct(found, false);
        }));
        List<ProductEntity> products = inventory.allProducts();
        if (products.isEmpty()) addText("ยังไม่มีสินค้า");
        for (ProductEntity product : products) addProductCard(product, false);
    }

    private void renderCount() {
        heading("ตรวจนับสต๊อก");
        EditText counted = field("จำนวนที่นับได้", "0");
        addButton("สแกนสินค้าเพื่อตรวจนับ", () -> startScan(barcode -> {
            ProductEntity product = inventory.findByBarcode(barcode);
            if (product == null) {
                toast("ไม่พบสินค้าในคลัง");
                return;
            }
            showProduct(product, true);
            addText("กรอกยอดนับจริงของ " + product.name + " (เดิม " + quantityText(product.stockQtyMilli) + " " + product.unit + ")");
            EditText actual = field("ยอดนับจริง", counted.getText().toString());
            addButton("ยืนยันแทนที่ยอดคงเหลือทันที", () -> {
                try {
                    long qty = quantityMilli(actual.getText().toString());
                    ProductEntity updated = inventory.replaceCount(UUID.randomUUID().toString(),
                            barcode, qty, now());
                    toast("บันทึกผลตรวจนับแล้ว คงเหลือ " + quantityText(updated.stockQtyMilli));
                    render("count");
                } catch (RuntimeException error) { toast(message(error)); }
            });
            addButton("ดูประวัติการตรวจนับ", () -> showHistory(product));
        }));
        addText("การตรวจนับจะแทนที่ยอดคงเหลือทันที และบันทึกประวัติทุกครั้ง");
    }

    private void renderLimits() {
        heading("ลิมิตสต๊อก");
        addText("แจ้งเตือนเฉพาะสินค้าที่จำนวนคงเหลือต่ำกว่าลิมิตที่กำหนด");
        List<ProductEntity> low = inventory.belowStockLimit();
        if (low.isEmpty()) addText("ไม่มีสินค้าต่ำกว่าลิมิต");
        for (ProductEntity product : low) {
            addProductCard(product, true);
            addButton("ปรับลิมิต: " + product.name, () -> editLimit(product));
        }
    }

    private void renderHistory() {
        heading("ประวัติการตรวจนับ");
        addButton("สแกนสินค้าเพื่อดูประวัติ", () -> startScan(barcode -> {
            ProductEntity product = inventory.findByBarcode(barcode);
            if (product == null) toast("ไม่พบสินค้าในคลัง");
            else showHistory(product);
        }));
        addText("เลือกประวัติได้จากการสแกนสินค้าในหน้าตรวจนับ");
    }

    private void showHistory(ProductEntity product) {
        render("history");
        addText("สินค้า: " + product.name + " · " + product.barcode);
        List<StockLogEntity> history = inventory.history(product.sku);
        if (history.isEmpty()) addText("ยังไม่มีประวัติสต๊อก");
        for (StockLogEntity log : history) {
            String label = reasonLabel(log.reason);
            addText(label + " | ก่อน " + quantityText(log.before_qty_milli)
                    + " → หลัง " + quantityText(log.after_qty_milli)
                    + " | เปลี่ยน " + signedQuantity(log.diff_qty_milli)
                    + " | " + log.created_at_utc);
        }
    }

    private void showProduct(ProductEntity product, boolean allowCount) {
        addText("ชื่อ: " + product.name);
        addText("บาร์โค้ด: " + product.barcode);
        addText("คงเหลือ: " + quantityText(product.stockQtyMilli) + " " + product.unit);
        addText("ลิมิต: " + (product.stockLimitQtyMilli == null
                ? "ไม่กำหนด" : quantityText(product.stockLimitQtyMilli)));
        addText("ราคาขาย: " + moneyText(product.priceSatang) + " บาท");
        if (allowCount) addButton("ดูประวัติสต๊อก", () -> showHistory(product));
    }

    private void addProductCard(ProductEntity product, boolean lowStock) {
        TextView card = text(product.name + " · " + product.barcode
                + "\nคงเหลือ " + quantityText(product.stockQtyMilli) + " " + product.unit
                + (product.stockLimitQtyMilli == null ? "" :
                " · ลิมิต " + quantityText(product.stockLimitQtyMilli)));
        card.setPadding(8, 14, 8, 14);
        page.addView(card);
    }

    private void editLimit(ProductEntity product) {
        render("limit");
        heading("ตั้งค่าลิมิตสต๊อก");
        addText(product.name + " · คงเหลือ " + quantityText(product.stockQtyMilli));
        EditText value = field("ลิมิตใหม่ (เว้นว่างเพื่อล้างลิมิต)", product.stockLimitQtyMilli == null
                ? "" : quantityText(product.stockLimitQtyMilli));
        addButton("บันทึกลิมิต", () -> {
            try {
                String raw = value.getText().toString().trim();
                Long limit = raw.isEmpty() ? null : quantityMilli(raw);
                inventory.updateStockLimit(UUID.randomUUID().toString(), product.sku, limit, now());
                toast("บันทึกลิมิตแล้ว");
                render("limit");
            } catch (RuntimeException error) { toast(message(error)); }
        });
    }

    private void startScan(java.util.function.Consumer<String> onResult) {
        afterScan = () -> {};
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            pendingScan = onResult;
            return;
        }
        showScanner(onResult);
    }

    private java.util.function.Consumer<String> pendingScan;

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && pendingScan != null) {
                java.util.function.Consumer<String> callback = pendingScan;
                pendingScan = null;
                showScanner(callback);
            } else {
                pendingScan = null;
                toast("ต้องอนุญาตกล้องเพื่อสแกนบาร์โค้ด");
            }
        }
    }

    private void showScanner(java.util.function.Consumer<String> onResult) {
        closeScanner();
        LinearLayout scanPage = new LinearLayout(this);
        scanPage.setOrientation(LinearLayout.VERTICAL);
        scanPage.setPadding(20, 20, 20, 20);
        TextView title = text("สแกนบาร์โค้ด");
        title.setTextSize(22);
        scanPage.addView(title);
        PreviewView preview = new PreviewView(this);
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        scanPage.addView(preview);
        Button cancel = new Button(this);
        cancel.setText("ยกเลิก");
        cancel.setOnClickListener(v -> render(initialScreen));
        scanPage.addView(cancel);
        setContentView(scanPage);
        scanner = new OfflineBarcodeScanner(this, this, preview, cameraExecutor,
                ContextCompat.getMainExecutor(this), new OfflineBarcodeScanner.Listener() {
            private boolean delivered;
            @Override public void onBarcode(String rawValue, int format) {
                if (delivered) return;
                delivered = true;
                closeScanner();
                onResult.accept(rawValue);
            }
            @Override public void onFailure(Exception exception) {
                runOnUiThread(() -> toast("สแกนไม่สำเร็จ: " + message(exception)));
            }
        });
    }

    private void closeScanner() {
        if (scanner != null) {
            scanner.close();
            scanner = null;
        }
    }

    private void speakNewProduct() {
        speech.speak("สินค้าใหม่ค่ะ", "new-product-" + System.nanoTime());
    }

    private void heading(String value) {
        TextView title = text(value);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 12, 0, 18);
        page.addView(title);
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(16);
        return view;
    }

    private void addText(String value) { page.addView(text(value)); }

    private EditText field(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        page.addView(input);
        return input;
    }

    private void addButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        page.addView(button);
    }

    private static String required(EditText input, String error) {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) throw new IllegalArgumentException(error);
        return value;
    }

    private static long moneySatang(String input) {
        try {
            BigDecimal value = new BigDecimal(input.trim()).setScale(2, RoundingMode.UNNECESSARY);
            long satang = value.movePointRight(2).longValueExact();
            if (satang < 0) throw new IllegalArgumentException("จำนวนเงินห้ามติดลบ");
            return satang;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("กรอกราคาเป็นตัวเลขไม่เกิน 2 ตำแหน่ง");
        }
    }

    private static long quantityMilli(String input) {
        try {
            BigDecimal value = new BigDecimal(input.trim()).setScale(3, RoundingMode.UNNECESSARY);
            long milli = value.movePointRight(3).longValueExact();
            if (milli < 0) throw new IllegalArgumentException("จำนวนห้ามติดลบ");
            return milli;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalArgumentException("กรอกจำนวนเป็นตัวเลขไม่เกิน 3 ตำแหน่ง");
        }
    }

    private static String quantityText(long milli) {
        return BigDecimal.valueOf(milli, 3).stripTrailingZeros().toPlainString();
    }
    private static String signedQuantity(long milli) {
        return (milli > 0 ? "+" : "") + quantityText(milli);
    }
    private static String moneyText(long satang) {
        return BigDecimal.valueOf(satang, 2).setScale(2).toPlainString();
    }
    private static String now() { return Instant.now().toString(); }
    private static String reasonLabel(String reason) {
        switch (reason) {
            case "RECEIVE": return "รับสินค้า";
            case "COUNT_REPLACE": return "ตรวจนับ";
            case "SALE": return "ขาย";
            case "RETURN": return "คืนสินค้า";
            case "CANCEL": return "ยกเลิกบิล";
            default: return reason;
        }
    }
    private static String message(Throwable error) {
        String value = error.getMessage();
        if ("PRODUCT_NOT_FOUND".equals(value)) return "ไม่พบสินค้าในคลัง";
        return value == null ? "เกิดข้อผิดพลาด" : value;
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() {
        closeScanner();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (speech != null) speech.close();
        super.onDestroy();
    }
}
