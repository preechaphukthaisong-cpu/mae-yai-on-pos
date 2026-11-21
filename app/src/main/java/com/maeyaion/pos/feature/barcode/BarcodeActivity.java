package com.maeyaion.pos.feature.barcode;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.maeyaion.pos.core.barcode.BarcodeAllocator;
import com.maeyaion.pos.core.barcode.BarcodeImageGenerator;
import com.maeyaion.pos.core.barcode.ProductRegistrationService;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.BarcodeRegistryEntity;
import com.maeyaion.pos.core.navigation.BarcodeRoute;
import com.maeyaion.pos.core.tx.RoomTransactionRunner;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline barcode creation and immutable registry view. */
public final class BarcodeActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private EditText nameInput;
    private EditText priceInput;
    private EditText costInput;
    private Button createButton;
    private TextView resultText;
    private ImageView barcodeImage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        loadCatalog();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 20, 24, 24);
        scroll.addView(content);
        setContentView(scroll);

        boolean catalogOnly = "catalog".equals(getIntent().getStringExtra(BarcodeRoute.EXTRA_SCREEN));
        TextView title = new TextView(this);
        title.setText(catalogOnly ? "คลังบาร์โค้ด" : "สร้างบาร์โค้ดสินค้า");
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        nameInput = field("ชื่อสินค้า");
        priceInput = field("ราคาขาย (บาท)");
        costInput = field("ต้นทุน (บาท)");
        createButton = new Button(this);
        createButton.setText("สร้างบาร์โค้ดอัตโนมัติ");
        createButton.setOnClickListener(v -> createProduct());
        content.addView(createButton);

        resultText = new TextView(this);
        resultText.setTextSize(16);
        resultText.setPadding(0, 16, 0, 8);
        content.addView(resultText);
        barcodeImage = new ImageView(this);
        barcodeImage.setAdjustViewBounds(true);
        content.addView(barcodeImage);

        TextView catalogTitle = new TextView(this);
        catalogTitle.setText("รายการบาร์โค้ดที่ลงทะเบียน");
        catalogTitle.setTextSize(20);
        catalogTitle.setPadding(0, 24, 0, 8);
        content.addView(catalogTitle);

        if (catalogOnly) {
            nameInput.setVisibility(View.GONE);
            priceInput.setVisibility(View.GONE);
            costInput.setVisibility(View.GONE);
            createButton.setVisibility(View.GONE);
            resultText.setVisibility(View.GONE);
            barcodeImage.setVisibility(View.GONE);
        }
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        content.addView(input);
        return input;
    }

    private void createProduct() {
        final String name = nameInput.getText().toString().trim();
        final long price;
        final long cost;
        try {
            price = parseBaht(priceInput.getText().toString());
            cost = parseBaht(costInput.getText().toString());
            if (name.isEmpty()) throw new IllegalArgumentException("กรอกชื่อสินค้า");
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, invalid.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        createButton.setEnabled(false);
        worker.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                RoomTransactionRunner tx = new RoomTransactionRunner(db);
                BarcodeAllocator allocator = new BarcodeAllocator(
                        db.barcodeRegistryDao(), db.barcodeCounterDao(), tx);
                ProductRegistrationService service = new ProductRegistrationService(
                        allocator, db.productDao(), db.opLogDao(), db.backupJournalDao(), tx);
                String now = Instant.now().toString();
                String sku = "SKU-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
                com.maeyaion.pos.core.db.ProductEntity product = service.createWithSystemBarcode(
                        UUID.randomUUID().toString(), sku, name, price, cost, "ชิ้น", null, now, now);
                Bitmap image = BarcodeImageGenerator.renderCode128(product.barcode);
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    resultText.setText("สินค้า: " + product.name + "\nบาร์โค้ด: " + product.barcode
                            + "\nราคาขาย: " + formatBaht(product.priceSatang) + " บาท"
                            + "\nสถานะ: เพิ่มเข้าคลังแล้ว (สต๊อกเริ่มต้น 0)");
                    barcodeImage.setImageBitmap(image);
                    nameInput.setText("");
                    priceInput.setText("");
                    costInput.setText("");
                    loadCatalog();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    Toast.makeText(this, "สร้างบาร์โค้ดไม่สำเร็จ: "
                            + safeMessage(failure), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadCatalog() {
        worker.execute(() -> {
            try {
                List<BarcodeRegistryEntity> rows = AppDatabase.getInstance(getApplicationContext())
                        .barcodeRegistryDao().listAll();
                runOnUiThread(() -> {
                    final int firstCatalogChild = 8;
                    while (content.getChildCount() > firstCatalogChild) {
                        content.removeViewAt(firstCatalogChild);
                    }
                    for (BarcodeRegistryEntity row : rows) {
                        TextView item = new TextView(this);
                        item.setText(row.displayName + "\n" + row.barcode + " · "
                                + row.sourceType + " · " + formatBaht(row.priceSatang) + " บาท");
                        item.setTextSize(14);
                        item.setPadding(8, 10, 8, 10);
                        content.addView(item);
                    }
                    if (rows.isEmpty()) {
                        TextView empty = new TextView(this);
                        empty.setText("ยังไม่มีบาร์โค้ดในคลัง");
                        content.addView(empty);
                    }
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> Toast.makeText(this,
                        "โหลดคลังบาร์โค้ดไม่สำเร็จ: " + safeMessage(failure), Toast.LENGTH_LONG).show());
            }
        });
    }

    private static long parseBaht(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("จำนวนเงินต้องเป็นตัวเลขและทศนิยมไม่เกิน 2 ตำแหน่ง");
        }
        String[] parts = value.split("\\.", -1);
        long baht = Long.parseLong(parts[0]);
        long satang = parts.length == 1 ? 0L
                : Long.parseLong((parts[1] + "00").substring(0, 2));
        return Math.addExact(Math.multiplyExact(baht, 100L), satang);
    }

    private static String formatBaht(long satang) {
        return String.format(Locale.ROOT, "%d.%02d", satang / 100L, satang % 100L);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName() : message;
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
