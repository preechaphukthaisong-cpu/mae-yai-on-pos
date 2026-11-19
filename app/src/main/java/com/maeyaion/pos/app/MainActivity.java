package com.maeyaion.pos.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderMainMenu();
        refreshDriveStatus();
    }

    private void renderMainMenu() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("ระบบขายหน้าร้าน — ใช้งานออฟไลน์");
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle, matchWrap());

        for (String menuLabel : MainMenuDefinition.items()) {
            addMenuButton(root, menuLabel);
        }

        TextView backupHeading = new TextView(this);
        backupHeading.setText("สำรองข้อมูล");
        backupHeading.setTextSize(18);
        backupHeading.setPadding(0, dp(24), 0, dp(8));
        root.addView(backupHeading, matchWrap());

        driveStatus = new TextView(this);
        driveStatus.setTextSize(14);
        root.addView(driveStatus, matchWrap());

        Button connectDrive = new Button(this);
        connectDrive.setText("เลือกบัญชี Google สำหรับสำรองข้อมูล");
        connectDrive.setOnClickListener(view -> launchDriveAccountPicker());
        root.addView(connectDrive, matchWrap());

        scroll.addView(root);
        setContentView(scroll);
    }

    private void addMenuButton(LinearLayout root, String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(view -> {
            if ("การขาย".equals(label) || "คลังสินค้า".equals(label)
                    || "สร้างบาร์โค้ด".equals(label)) {
                startActivity(new Intent(this, PosWorkflowActivity.class).putExtra("screen", label));
            } else {
                Toast.makeText(this, label + ": อยู่ในลำดับพัฒนาถัดไป",
                        Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        root.addView(button, params);
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
            Toast.makeText(this, "เลือกบัญชีสำรองข้อมูลแล้ว", Toast.LENGTH_SHORT).show();
        } catch (ApiException failure) {
            Toast.makeText(this, "เลือกบัญชี Google ไม่สำเร็จ: "
                    + failure.getStatusCode(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshDriveStatus() {
        if (driveStatus == null) return;
        String email = preferences().getString(KEY_ACCOUNT, null);
        driveStatus.setText(email == null
                ? "Google Drive: ยังไม่ได้เลือกบัญชี (ไม่จำเป็นต่อการขาย)"
                : "Google Drive: " + email);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
