package com.maeyaion.pos.feature.store;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.SyncOutboxEntity;
import com.maeyaion.pos.core.sync.SyncQueueDao;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only conflict review. Financial events are never auto-merged or discarded here. */
public final class SyncConflictReviewActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("รายการซิงก์ที่ขัดแย้ง");
        title.setTextSize(22);
        title.setPadding(20, 24, 20, 12);
        root.addView(title);
        TextView warning = new TextView(this);
        warning.setText("ระบบจะไม่รวมยอดขาย หนี้ หรือสต็อกให้อัตโนมัติ ตรวจสอบรายการก่อนดำเนินการแก้ไข");
        warning.setPadding(20, 8, 20, 16);
        root.addView(warning);
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(16, 8, 16, 16);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Button back = new Button(this);
        back.setText("ย้อนกลับ");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        setContentView(root);
        load();
    }

    private void load() {
        worker.execute(() -> {
            try {
                AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                String storeId = db.syncQueueDao().boundStoreId();
                List<SyncOutboxEntity> conflicts = storeId == null
                        ? java.util.Collections.emptyList()
                        : db.syncQueueDao().conflictedOutbox(storeId);
                runOnUiThread(() -> render(conflicts));
            } catch (RuntimeException error) {
                runOnUiThread(() -> Toast.makeText(this, message(error), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void render(List<SyncOutboxEntity> conflicts) {
        list.removeAllViews();
        if (conflicts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("ไม่มีรายการที่รอการตรวจสอบ");
            empty.setPadding(8, 16, 8, 16);
            list.addView(empty);
            return;
        }
        for (SyncOutboxEntity event : conflicts) {
            TextView detail = new TextView(this);
            detail.setText("ประเภท: " + event.entity_type
                    + "\nรหัสข้อมูล: " + event.entity_id
                    + "\nเหตุการณ์: " + event.event_id
                    + "\nเวอร์ชันก่อนส่ง: " + event.base_version
                    + "\nสาเหตุ: " + (event.last_error == null ? "ข้อมูลเวอร์ชันไม่ตรงกัน" : event.last_error)
                    + "\nเวลาสร้าง: " + event.created_at_utc);
            detail.setTextIsSelectable(true);
            detail.setPadding(8, 12, 8, 4);
            list.addView(detail);
            Button copy = new Button(this);
            copy.setText("คัดลอกรหัสเหตุการณ์");
            copy.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Sync event ID", event.event_id));
                Toast.makeText(this, "คัดลอกรหัสแล้ว", Toast.LENGTH_SHORT).show();
            });
            list.addView(copy);
            TextView divider = new TextView(this);
            divider.setText("────────────────────────");
            list.addView(divider);
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
