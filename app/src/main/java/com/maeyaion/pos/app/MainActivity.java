package com.maeyaion.pos.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
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
        renderFoundationStatus();
        refreshDriveStatus();
    }

    private void renderFoundationStatus() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);

        TextView status = new TextView(this);
        status.setText(R.string.foundation_status);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 16, 0, 24);

        driveStatus = new TextView(this);
        driveStatus.setTextSize(14);
        driveStatus.setGravity(Gravity.CENTER);
        driveStatus.setPadding(0, 0, 0, 12);

        Button connectDrive = new Button(this);
        connectDrive.setText("เลือกบัญชี Google Drive");
        connectDrive.setOnClickListener(view -> launchDriveAccountPicker());

        root.addView(title);
        root.addView(status);
        root.addView(driveStatus);
        root.addView(connectDrive);
        setContentView(root);
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
