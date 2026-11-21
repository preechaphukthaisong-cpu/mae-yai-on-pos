package com.maeyaion.pos.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
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
import com.maeyaion.pos.feature.inventory.InventoryActivity;
import com.maeyaion.pos.feature.barcode.BarcodeActivity;
import com.maeyaion.pos.feature.sales.SalesActivity;
import com.maeyaion.pos.feature.history.HistoryActivity;
import com.maeyaion.pos.feature.store.StoreActivity;
import com.maeyaion.pos.feature.reports.ReportsActivity;
import com.maeyaion.pos.core.navigation.BarcodeRoute;
import com.maeyaion.pos.core.navigation.InventoryRoute;
import com.maeyaion.pos.core.navigation.SalesRoute;
import com.maeyaion.pos.core.navigation.HistoryRoute;
import com.maeyaion.pos.core.navigation.StoreRoute;
import com.maeyaion.pos.core.navigation.ReportsRoute;

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
        page.setPadding(dp(18), dp(12), dp(18), dp(20));
        page.setBackgroundColor(getColor(R.color.pos_background));

        TextView account = new TextView(this);
        account.setText("👤");
        account.setTextSize(21);
        account.setGravity(Gravity.CENTER);
        account.setContentDescription("บัญชี Google Drive");
        account.setBackgroundResource(R.drawable.bg_pos_profile);
        account.setOnClickListener(view -> launchDriveAccountPicker());
        LinearLayout.LayoutParams accountParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        accountParams.gravity = Gravity.END;
        page.addView(account, accountParams);

        TextView brand = addHeading(page, getString(R.string.app_name), 31);
        brand.setTextColor(getColor(R.color.pos_forest));
        brand.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        brand.setPadding(0, dp(2), 0, dp(2));
        TextView subtitle = new TextView(this);
        subtitle.setText("ระบบขายหน้าร้าน · ใช้งานได้แม้ไม่มีอินเทอร์เน็ต  ◉");
        subtitle.setTextSize(15);
        subtitle.setTextColor(getColor(R.color.pos_text_secondary));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(20));
        page.addView(subtitle);

        addMenuRow(page,
                new String[]{"🧾|การขาย|ขายสินค้าและรับชำระ", "📦|คลังสินค้า|สินค้าและสต๊อก"},
                new Runnable[]{() -> openSales("cash"), () -> openInventory("menu")});
        addMenuRow(page,
                new String[]{"▥|สร้างบาร์โค้ด|ลงทะเบียนและพิมพ์", "🧾🔍|ประวัติการขาย|ค้นหาบิลและคืนสินค้า"},
                new Runnable[]{() -> openBarcode("create"), () -> openHistory("paid")});
        addMenuRow(page,
                new String[]{"⚙️🔧|จัดการร้านค้า|ตั้งค่าและเครื่องมือ", "📊|รายงานยอดขาย|สรุปผลประกอบการ"},
                new Runnable[]{this::renderStoreManagement, () -> openReports("daily")});

        TextView footer = new TextView(this);
        footer.setText("MAEYAI-ON POS  ·  OFFLINE FIRST");
        footer.setTextSize(12);
        footer.setLetterSpacing(0.06f);
        footer.setTextColor(getColor(R.color.pos_muted));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(12), 0, dp(8));
        page.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.pos_background));
        scroll.addView(page);
        setContentView(scroll);
    }

    private void addMenuRow(LinearLayout parent, String[] cards, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(12);
        parent.addView(row, rowParams);

        for (int i = 0; i < cards.length; i++) {
            String[] parts = cards[i].split("\\|", 3);
            final Runnable action = actions[i];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(7), dp(12), dp(7), dp(12));
            tile.setBackgroundResource(R.drawable.bg_pos_menu_tile);
            tile.setElevation(dp(5));
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(parts[1] + " " + parts[2]);
            tile.setOnClickListener(view -> action.run());

            TextView icon = new TextView(this);
            icon.setText(parts[0]);
            icon.setTextSize(39);
            icon.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
            tile.addView(icon, iconParams);

            TextView title = new TextView(this);
            title.setText(parts[1]);
            title.setTextSize(21);
            title.setTextColor(getColor(R.color.pos_white));
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(2), 0, dp(2));
            tile.addView(title);

            TextView description = new TextView(this);
            description.setText(parts[2]);
            description.setTextSize(15);
            description.setTextColor(0xFFD7F0E8);
            description.setGravity(Gravity.CENTER);
            description.setMaxLines(2);
            tile.addView(description);

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                    0, dp(164), 1f);
            tileParams.setMargins(dp(5), 0, dp(5), 0);
            row.addView(tile, tileParams);
        }
    }

    private void renderStoreManagement() {
        showingStoreManagement = true;
        LinearLayout page = createPage();
        addButton(page, "กลับหน้าหลัก", this::renderHome);
        addHeading(page, "จัดการร้านค้า", 22);

        addMenuSection(page, "การขาย",
                "ขายเงินสด", "ขายเชื่อ", "บัญชีลูกค้าเชื่อ");
        addInventorySection(page);
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

    private void addInventorySection(LinearLayout parent) {
        TextView heading = new TextView(this);
        heading.setText("คลังสินค้า");
        heading.setTextSize(17);
        heading.setPadding(0, 16, 0, 4);
        parent.addView(heading);
        addButton(parent, "เพิ่มสินค้าเข้าคลัง/รับสินค้า", () -> openInventory("receive"));
        addButton(parent, "สินค้าทั้งหมด", () -> openInventory("all"));
        addButton(parent, "ตรวจนับสต๊อก", () -> openInventory("count"));
        addButton(parent, "ลิมิตสต๊อก", () -> openInventory("limit"));
    }

    // Always use explicit in-app intents. Package-scoped implicit intents do not
    // reliably resolve non-exported activities on modern Android releases.
    private void openInventory(String screen) {
        Intent intent = new Intent(this, InventoryActivity.class);
        intent.putExtra(InventoryRoute.EXTRA_SCREEN, screen);
        startActivity(intent);
    }

    private void openBarcode(String screen) {
        Intent intent = new Intent(this, BarcodeActivity.class);
        intent.putExtra(BarcodeRoute.EXTRA_SCREEN, screen);
        startActivity(intent);
    }

    private void openSales(String screen) {
        openRoute(SalesActivity.class, SalesRoute.EXTRA_SCREEN, screen);
    }

    private void openHistory(String screen) {
        openRoute(HistoryActivity.class, HistoryRoute.EXTRA_SCREEN, screen);
    }

    private void openStore(String screen) {
        openRoute(StoreActivity.class, StoreRoute.EXTRA_SCREEN, screen);
    }

    private void openReports(String screen) {
        openRoute(ReportsActivity.class, ReportsRoute.EXTRA_SCREEN, screen);
    }

    private void openRoute(Class<? extends Activity> destination, String extra, String screen) {
        Intent intent = new Intent(this, destination);
        intent.putExtra(extra, screen);
        startActivity(intent);
    }

    private void addMenuSection(LinearLayout parent, String title, String... items) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(17);
        heading.setPadding(0, 16, 0, 4);
        parent.addView(heading);
        for (String item : items) {
            Runnable action = this::showNotImplemented;
            switch (item) {
                case "ขายเงินสด": action = () -> openSales("cash"); break;
                case "ขายเชื่อ": action = () -> openSales("credit"); break;
                case "บัญชีลูกค้าเชื่อ":
                case "บัญชีลูกค้าเชื่อทั้งหมด": action = () -> openSales("customers"); break;
                case "สร้างบาร์โค้ด": action = () -> openBarcode("create"); break;
                case "คลังบาร์โค้ด": action = () -> openBarcode("catalog"); break;
                case "บิลจ่ายแล้ว": action = () -> openHistory("paid"); break;
                case "บิลค้างชำระ": action = () -> openHistory("unpaid"); break;
                case "รายละเอียดบิลและการคืนสินค้า": action = () -> openHistory("all"); break;
                case "แก้ไขราคาขาย": action = () -> openStore("price"); break;
                case "ประวัติราคา": action = () -> openStore("history"); break;
                case "รายวัน": action = () -> openReports("daily"); break;
                case "รายสัปดาห์": action = () -> openReports("weekly"); break;
                case "รายเดือน/เลือกวัน": action = () -> openReports("monthly"); break;
                case "ขายดี 20 อันดับ": action = () -> openReports("top"); break;
                case "ขายน้อยสุด 20 อันดับ": action = () -> openReports("bottom"); break;
                default: break;
            }
            addButton(parent, item, action);
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
