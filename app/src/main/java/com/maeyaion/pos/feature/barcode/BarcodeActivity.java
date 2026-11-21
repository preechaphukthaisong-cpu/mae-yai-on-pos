package com.maeyaion.pos.feature.barcode;

import android.app.Activity;
import android.content.Intent;
import java.io.OutputStream;
import android.graphics.Bitmap;
import android.graphics.pdf.PdfDocument;
import android.graphics.Paint;
import android.graphics.Color;
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
    private EditText manufacturerBarcodeInput;
    private Button exportButton;
    private Button exportPdfButton;
    private static final int REQUEST_EXPORT_PDF = 5202;
    private Bitmap lastBarcodeBitmap;
    private static final int REQUEST_EXPORT_BARCODE = 5201;
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
        manufacturerBarcodeInput = field("บาร์โค้ดโรงงาน (ถ้าไม่มีให้เว้นว่าง)");
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
        exportButton = new Button(this);
        exportButton.setText("ส่งออกภาพบาร์โค้ด PNG");
        exportButton.setEnabled(false);
        exportButton.setOnClickListener(v -> exportBarcode());
        content.addView(exportButton);
        exportPdfButton = new Button(this);
        exportPdfButton.setText("ส่งออกบาร์โค้ด PDF");
        exportPdfButton.setEnabled(false);
        exportPdfButton.setOnClickListener(v -> exportBarcodePdf());
        content.addView(exportPdfButton);

        TextView catalogTitle = new TextView(this);
        catalogTitle.setText("รายการบาร์โค้ดที่ลงทะเบียน");
        catalogTitle.setTextSize(20);
        catalogTitle.setPadding(0, 24, 0, 8);
        content.addView(catalogTitle);

        if (catalogOnly) {
            nameInput.setVisibility(View.GONE);
            priceInput.setVisibility(View.GONE);
            costInput.setVisibility(View.GONE);
            manufacturerBarcodeInput.setVisibility(View.GONE);
            createButton.setVisibility(View.GONE);
            resultText.setVisibility(View.GONE);
            barcodeImage.setVisibility(View.GONE);
            exportButton.setVisibility(View.GONE);
            exportPdfButton.setVisibility(View.GONE);
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
        final String manufacturerBarcode = manufacturerBarcodeInput.getText().toString().trim();
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
                String operationId = UUID.randomUUID().toString();
                com.maeyaion.pos.core.db.ProductEntity product = manufacturerBarcode.isEmpty()
                        ? service.createWithSystemBarcode(operationId, sku, name, price, cost, "ชิ้น", null, now, now)
                        : service.createWithManufacturerBarcode(operationId, manufacturerBarcode, sku, name, price, cost, "ชิ้น", null, now, now);
                Bitmap image = BarcodeImageGenerator.renderCode128(product.barcode);
                runOnUiThread(() -> {
                    createButton.setEnabled(true);
                    resultText.setText("สินค้า: " + product.name + "\nบาร์โค้ด: " + product.barcode
                            + "\nราคาขาย: " + formatBaht(product.priceSatang) + " บาท"
                            + "\nสถานะ: เพิ่มเข้าคลังแล้ว (สต๊อกเริ่มต้น 0)");
                    barcodeImage.setImageBitmap(image);
                    lastBarcodeBitmap = image;
                    exportButton.setEnabled(true);
                    exportPdfButton.setEnabled(true);
                    nameInput.setText("");
                    priceInput.setText("");
                    costInput.setText("");
                    manufacturerBarcodeInput.setText("");
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
                    final int firstCatalogChild = 9;
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

    private void exportBarcode() {
        if (lastBarcodeBitmap == null) {
            Toast.makeText(this, "ยังไม่มีภาพบาร์โค้ด", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "maeyai-barcode.png");
        startActivityForResult(intent, REQUEST_EXPORT_BARCODE);
    }

    private void exportBarcodePdf() {
        if (lastBarcodeBitmap == null) {
            Toast.makeText(this, "ยังไม่มีภาพบาร์โค้ด", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, "maeyai-barcode.pdf");
        startActivityForResult(intent, REQUEST_EXPORT_PDF);
    }

    private void writeBarcodePdf(android.net.Uri uri) throws Exception {
        PdfDocument document = new PdfDocument();
        try {
            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(612, 792, 1).create());
            android.graphics.Canvas canvas = page.getCanvas();
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.BLACK);
            paint.setTextSize(22f);
            canvas.drawText(resultText.getText().toString().split("\\n")[0], 36, 60, paint);
            paint.setTextSize(18f);
            canvas.drawText("บาร์โค้ดสินค้า", 36, 100, paint);
            android.graphics.Rect dst = new android.graphics.Rect(36, 130, 576, 330);
            canvas.drawBitmap(lastBarcodeBitmap, null, dst, paint);
            document.finishPage(page);
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("เปิดไฟล์ปลายทางไม่ได้");
                document.writeTo(output);
            }
        } finally {
            document.close();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_EXPORT_PDF) {
            try { writeBarcodePdf(data.getData()); Toast.makeText(this, "ส่งออก PDF แล้ว", Toast.LENGTH_SHORT).show(); }
            catch (Exception failure) { Toast.makeText(this, "ส่งออก PDF ไม่สำเร็จ: " + safeMessage(failure), Toast.LENGTH_LONG).show(); }
            return;
        }
        if (requestCode != REQUEST_EXPORT_BARCODE || lastBarcodeBitmap == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null || !lastBarcodeBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("ไม่สามารถส่งออกภาพ PNG ได้");
            }
            Toast.makeText(this, "ส่งออกภาพบาร์โค้ดแล้ว", Toast.LENGTH_SHORT).show();
        } catch (Exception failure) {
            Toast.makeText(this, "ส่งออกไม่สำเร็จ: " + safeMessage(failure), Toast.LENGTH_LONG).show();
        }
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
