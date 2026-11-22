package com.maeyaion.pos.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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
import com.maeyaion.pos.feature.store.SyncSettingsActivity;
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

    /**
     * Native, accessible implementation of the approved Canva home-screen design.
     * Menu tiles remain real Android controls and continue to open the existing features.
     */
    private void renderHome() {
        showingStoreManagement = false;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.pos_background));
        root.addView(new HomeBackgroundView(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(24), dp(18), dp(22));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = new TextView(this);
        brand.setText(getString(R.string.app_name));
        brand.setTextColor(getColor(R.color.pos_forest));
        brand.setTextSize(27);
        brand.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setMaxLines(2);
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(brand, brandParams);

        TextView profile = new TextView(this);
        profile.setText("👤");
        profile.setTextSize(24);
        profile.setGravity(Gravity.CENTER);
        profile.setContentDescription("จัดการร้านค้าและการตั้งค่า");
        GradientDrawable profileShape = new GradientDrawable();
        profileShape.setShape(GradientDrawable.OVAL);
        profileShape.setColor(Color.TRANSPARENT);
        profileShape.setStroke(dp(2), getColor(R.color.pos_forest));
        profile.setBackground(profileShape);
        profile.setElevation(dp(1));
        profile.setOnClickListener(view -> renderStoreManagement());
        header.addView(profile, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);

        TextView subtitle = new TextView(this);
        subtitle.setText("ระบบขายหน้าร้าน  ·  ใช้งานได้แม้ไม่มีอินเทอร์เน็ต  ☁̸");
        subtitle.setTextSize(14);
        subtitle.setTextColor(getColor(R.color.pos_text_secondary));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        page.addView(subtitle);

        addMenuRow(page,
                new String[]{"🧾", "📦"},
                new String[]{"การขาย", "คลังสินค้า"},
                new String[]{"ขายสินค้าและรับชำระ", "สินค้าและสต็อก"},
                new Runnable[]{() -> openSales("cash"), () -> openInventory("menu")});
        addMenuRow(page,
                new String[]{"🏷️", "🔎"},
                new String[]{"สร้างบาร์โค้ด", "ประวัติการขาย"},
                new String[]{"ลงทะเบียนและพิมพ์", "ค้นหาบิลและคืนสินค้า"},
                new Runnable[]{() -> openBarcode("create"), () -> openHistory("paid")});
        addMenuRow(page,
                new String[]{"⚙️", "📊"},
                new String[]{"จัดการร้านค้า", "รายงานยอดขาย"},
                new String[]{"ตั้งค่าและเครื่องมือ", "สรุปผลประกอบการ"},
                new Runnable[]{this::renderStoreManagement, () -> openReports("daily")});

        TextView footer = new TextView(this);
        footer.setText("MAEYAI-ON POS  ·  OFFLINE FIRST");
        footer.setTextSize(12);
        footer.setLetterSpacing(0.06f);
        footer.setTextColor(getColor(R.color.pos_muted));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, dp(8));
        page.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void addMenuRow(LinearLayout parent, String[] icons, String[] titles,
            String[] descriptions, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(14);
        parent.addView(row, rowParams);

        for (int i = 0; i < titles.length; i++) {
            final Runnable action = actions[i];
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(dp(8), dp(14), dp(8), dp(12));
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setContentDescription(titles[i] + ". " + descriptions[i]);
            GradientDrawable tileBackground = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF08A77B, 0xFF087D60, 0xFF075B46});
            tileBackground.setCornerRadius(dp(24));
            tileBackground.setStroke(dp(1), 0x45FFFFFF);
            tile.setBackground(tileBackground);
            tile.setElevation(dp(6));
            tile.setOnClickListener(view -> action.run());

            TextView icon = new TextView(this);
            icon.setText(icons[i]);
            icon.setTextSize(43);
            icon.setGravity(Gravity.CENTER);
            icon.setShadowLayer(dp(2), 0, dp(2), 0x66000000);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(66));
            tile.addView(icon, iconParams);

            TextView title = new TextView(this);
            title.setText(titles[i]);
            title.setTextSize(20);
            title.setTypeface(android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD);
            title.setTextColor(Color.WHITE);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(2);
            title.setPadding(0, dp(4), 0, dp(3));
            tile.addView(title);

            TextView description = new TextView(this);
            description.setText(descriptions[i]);
            description.setTextSize(14);
            description.setTextColor(0xFFE5F0EC);
            description.setGravity(Gravity.CENTER);
            description.setMaxLines(2);
            tile.addView(description);

            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                    0, dp(168), 1f);
            tileParams.setMargins(dp(5), dp(2), dp(5), dp(4));
            row.addView(tile, tileParams);
        }
    }

    private final class HomeBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HomeBackgroundView() {
            super(MainActivity.this);
            setWillNotDraw(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0x0D075B46);
            float originX = -dp(82);
            float originY = dp(36);
            for (int i = 0; i < 4; i++) {
                canvas.drawCircle(originX, originY, dp(118 + i * 28), paint);
            }
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
        addButton(page, "ตั้งค่าซิงก์หลายเครื่อง", () ->
                startActivity(new Intent(this, SyncSettingsActivity.class)));
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
