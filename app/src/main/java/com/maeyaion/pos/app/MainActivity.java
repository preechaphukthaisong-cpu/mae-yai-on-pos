package com.maeyaion.pos.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.maeyaion.pos.R;

public final class MainActivity extends Activity {
    private static final int REQUEST_DRIVE_ACCOUNT = 4102;
    private static final String DRIVE_SCOPE =
            "https://www.googleapis.com/auth/drive.appdata";
    private static final String PREFS = "drive_account_preferences";
    private static final String KEY_ACCOUNT = "selected_google_account";

    private TextView driveStatus;
    private boolean showingStoreManagement;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderHome();
    }

    private LinearLayout createPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(24, 24, 24, 24);
        return page;
    }

    private TextView addHeading(LinearLayout parent, String label, float size) {
        TextView heading = new TextView(this);
        heading.setText(label);
        heading.setTextSize(size);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, 12, 0, 16);
        parent.addView(heading);
        return heading;
    }

    private Button addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }

    private void renderHome() {
        showingStoreManagement = false;
        LinearLayout page = createPage();
        addHeading(page, getString(R.string.app_name), 24);

        addButton(page, "การขาย", this::showNotImplemented);
        addButton(page, "คลังสินค้า", this::showNotImplemented);
        addButton(page, "สร้างบาร์โค้ด", this::showNotImplemented);
        addButton(page, "ประวัติการขาย", this::showNotImplemented);
        addButton(page, "จัดการร้านค้า", this::renderStoreManagement);
        addButton(page, "รายงานยอดขาย", this::showNotImplemented);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    private void renderStoreManagement() {
        showingStoreManagement = true;
        LinearLayout page = createPage();
        addButton(page, "กลับหน้าหลัก", this::renderHome);
        addHeading(page, "จัดการร้านค้า", 22);

        addMenuSection(page, "การขาย",
                "ขายเงินสด", "ขายเชื่อ", "บัญชีลูกค้าเชื่อ");
        addMenuSection(page, "คลังสินค้า",
                "เพิ่มสินค้าเข้าคลัง/รับสินค้า", "สินค้าทั้งหมด",
                "ตรวจนับสต๊อก", "ลิมิตสต๊อก");
        addMenuSection(page, "สร้างบาร์โค้ด",
                "สร้างบาร์โค้ด", "คลังบาร์โค้ด");
        addMenuSection(page, "ประวัติการขาย",
                "บิลจ่ายแล้ว", "บิลค้างชำระ", "บัญชีลูกค้าเชื่อทั้งหมด",
                "รายละเอียดบิลและการคืนสินค้า");
        addMenuSection(page, "จัดการร้านค้า",
                "แก้ไขราคาขาย", "ประวัติราคา");
        addMenuSection(page, "รายงานยอดขาย",
                "รายวัน", "รายสัปดาห์", "รายเดือน/เลือกวัน",
                "ขายดี 20 อันดับ", "ขายน้อยสุด 20 อันดับ");

        addHeading(page, "เครื่องมือและการเชื่อมต่อ", 18);
        driveStatus = new TextView(this);
        driveStatus.setTextSize(14);
        driveStatus.setGravity(Gravity.CENTER);
        driveStatus.setPadding(0, 8, 0, 8);
        page.addView(driveStatus);
        addButton(page, "เลือกบัญชี Google Drive", this::launchDriveAccountPicker);
        refreshDriveStatus();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    private void addMenuSection(LinearLayout parent, String title, String... items) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(17);
        heading.setPadding(0, 16, 0, 4);
        parent.addView(heading);
        for (String item : items) {
            addButton(parent, item, this::showNotImplemented);
        }
    }

    private void showNotImplemented() {
        Toast.makeText(this, "เมนูนี้ยังอยู่ระหว่างพัฒนา", Toast.LENGTH_SHORT).show();
    }

    @Override public void onBackPressed() {
        if (showingStoreManagement) {
            renderHome();
        } else {
            super.onBackPressed();
        }
    }

    private void launchDriveAccountPicker() {
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DRIVE_SCOPE))
                .build();
        GoogleSignInClient client = GoogleSignIn.getClient(this, options);
        startActivityForResult(client.getSignInIntent(), REQUEST_DRIVE_ACCOUNT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
            android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DRIVE_ACCOUNT) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "ยกเลิกการเลือกบัญชี Google", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException.class);
            if (account == null || account.getEmail() == null
                    || !GoogleSignIn.hasPermissions(account, new Scope(DRIVE_SCOPE))) {
                Toast.makeText(this, "ยังไม่ได้อนุญาตสิทธิ์สำรองข้อมูล Google Drive",
                        Toast.LENGTH_LONG).show();
                return;
            }
            preferences().edit().putString(KEY_ACCOUNT, account.getEmail()).apply();
            refreshDriveStatus();
            Toast.makeText(this, "เชื่อมต่อบัญชี Google Drive แล้ว", Toast.LENGTH_SHORT).show();
        } catch (ApiException failure) {
            Toast.makeText(this, "เชื่อมต่อ Google Drive ไม่สำเร็จ: "
                    + failure.getStatusCode(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshDriveStatus() {
        if (driveStatus == null) return;
        String email = preferences().getString(KEY_ACCOUNT, null);
        driveStatus.setText(email == null
                ? "Google Drive: ยังไม่ได้เลือกบัญชี"
                : "Google Drive: " + email);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
