package com.maeyaion.pos.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
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
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes;
import com.maeyaion.pos.R;
import com.maeyaion.pos.core.backup.AndroidKeystoreCredentialVault;
import com.maeyaion.pos.core.backup.BackupCrypto;
import com.maeyaion.pos.core.backup.UnattendedBackupScheduler;

import java.util.Arrays;

public final class MainActivity extends Activity {
    private static final int REQUEST_DRIVE_ACCOUNT = 4102;
    private static final int REQUEST_BACKUP_NOTIFICATIONS = 7302;
    private static final String DRIVE_SCOPE =
            "https://www.googleapis.com/auth/drive.appdata";
    private static final String PREFS = "drive_account_preferences";
    private static final String KEY_ACCOUNT = "selected_google_account";

    private TextView driveStatus;
    private char[] pendingSetupPassword;
    private String pendingSetupRecoveryKey;
    private String pendingSetupAccount;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderFoundationStatus();
        refreshDriveStatus();
    }

    @Override protected void onDestroy() {
        wipePendingSetup();
        super.onDestroy();
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

        Button enableBackup = new Button(this);
        enableBackup.setText("ตั้งค่าสำรองข้อมูลอัตโนมัติ");
        enableBackup.setOnClickListener(view -> showUnattendedBackupSetup());

        Button disableBackup = new Button(this);
        disableBackup.setText("ปิดการสำรองข้อมูลอัตโนมัติ");
        disableBackup.setOnClickListener(view -> confirmDisableUnattendedBackup());

        root.addView(title);
        root.addView(status);
        root.addView(driveStatus);
        root.addView(connectDrive);
        root.addView(enableBackup);
        root.addView(disableBackup);
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
        if (data == null) {
            Toast.makeText(this, "Google Sign-In ไม่ส่งผลลัพธ์กลับ (resultCode="
                    + resultCode + ")", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException.class);
            if (account == null || account.getEmail() == null
                    || !GoogleSignIn.hasPermissions(account, new Scope(DRIVE_SCOPE))) {
                Toast.makeText(this, "บัญชี Google ยังไม่ได้รับสิทธิ์ Google Drive appData",
                        Toast.LENGTH_LONG).show();
                return;
            }
            String selected = account.getEmail();
            String previous = preferences().getString(KEY_ACCOUNT, null);
            if (previous != null && !previous.equalsIgnoreCase(selected)
                    && UnattendedBackupScheduler.isEnabled(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("ยืนยันเปลี่ยนบัญชีสำรองข้อมูล")
                        .setMessage("การเปลี่ยนบัญชีจะย้ายงานสำรองอัตโนมัติไปยังบัญชี Google ใหม่")
                        .setNegativeButton("ยกเลิก", (dialog, which) -> refreshDriveStatus())
                        .setPositiveButton("ยืนยันเปลี่ยน", (dialog, which) -> {
                            preferences().edit().putString(KEY_ACCOUNT, selected).apply();
                            UnattendedBackupScheduler.changeAccount(this, selected, true);
                            refreshDriveStatus();
                        }).show();
            } else {
                preferences().edit().putString(KEY_ACCOUNT, selected).apply();
                refreshDriveStatus();
            }
            Toast.makeText(this, "เชื่อมต่อบัญชี Google Drive แล้ว", Toast.LENGTH_SHORT).show();
        } catch (ApiException failure) {
            int statusCode = failure.getStatusCode();
            String statusText = GoogleSignInStatusCodes.getStatusCodeString(statusCode);
            String message = statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED
                    ? "ยกเลิกการเลือกบัญชี Google"
                    : "Google Sign-In ล้มเหลว";
            Toast.makeText(this, message + " (code=" + statusCode + ": "
                    + statusText + ")", Toast.LENGTH_LONG).show();
        }
    }

    private void showUnattendedBackupSetup() {
        String account = preferences().getString(KEY_ACCOUNT, null);
        if (account == null) {
            Toast.makeText(this, "กรุณาเลือกบัญชี Google Drive ก่อน", Toast.LENGTH_LONG).show();
            launchDriveAccountPicker();
            return;
        }
        if (UnattendedBackupScheduler.isEnabled(this)) {
            Toast.makeText(this, "เปิดการสำรองข้อมูลอัตโนมัติอยู่แล้ว", Toast.LENGTH_SHORT).show();
            return;
        }

        String recoveryKey = BackupCrypto.generateRecoveryKey();
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(40, 16, 40, 8);

        TextView warning = new TextView(this);
        warning.setText("ระบบจะเข้าถึง Recovery Password และ Recovery Key ในเครื่องนี้โดยอัตโนมัติ เพื่อสร้างไฟล์สำรองวันละครั้ง แม้คุณไม่ได้เปิดแอป การเปิดใช้เพิ่มความสะดวก แต่ผู้ที่เข้าถึงเครื่องและแอปได้อาจสั่งให้ระบบใช้ข้อมูลกู้คืนได้");
        fields.addView(warning);

        EditText password = new EditText(this);
        password.setHint("Recovery Password (อย่างน้อย 12 ตัวอักษร)");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(password);

        EditText confirmation = new EditText(this);
        confirmation.setHint("ยืนยัน Recovery Password");
        confirmation.setSingleLine(true);
        confirmation.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(confirmation);

        TextView keyLabel = new TextView(this);
        keyLabel.setText("Recovery Key — จดหรือเก็บสำเนาไว้นอกโทรศัพท์ก่อนดำเนินการ");
        keyLabel.setPadding(0, 16, 0, 4);
        fields.addView(keyLabel);

        TextView keyValue = new TextView(this);
        keyValue.setText(recoveryKey);
        keyValue.setTextIsSelectable(true);
        keyValue.setTextSize(14);
        fields.addView(keyValue);

        CheckBox unattendedConsent = new CheckBox(this);
        unattendedConsent.setText("ยินยอมให้แอปใช้ข้อมูลกู้คืนโดยอัตโนมัติวันละครั้ง");
        fields.addView(unattendedConsent);

        CheckBox offlineCopy = new CheckBox(this);
        offlineCopy.setText("ฉันบันทึก Recovery Key ไว้ในที่เก็บแยกต่างหากนอกโทรศัพท์แล้ว");
        fields.addView(offlineCopy);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ตั้งค่าสำรองข้อมูลอัตโนมัติ")
                .setView(scroll)
                .setNegativeButton("ยกเลิก", (d, which) -> { })
                .setPositiveButton("บันทึกและเปิดใช้", null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (pendingSetupPassword == null) {
                // Recovery Key is generated for this setup attempt only.
            }
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String first = password.getText().toString();
                    String second = confirmation.getText().toString();
                    if (first.length() < 12 || !first.equals(second)) {
                        Toast.makeText(this, "Recovery Password ต้องมีอย่างน้อย 12 ตัวและต้องตรงกัน",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!unattendedConsent.isChecked() || !offlineCopy.isChecked()) {
                        Toast.makeText(this, "ต้องยืนยันความยินยอมและการเก็บ Recovery Key แยกต่างหาก",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    pendingSetupPassword = first.toCharArray();
                    pendingSetupRecoveryKey = recoveryKey;
                    pendingSetupAccount = account;
                    dialog.dismiss();
                    requestNotificationPermissionOrEnable();
                }));
        dialog.show();
    }

    private void requestNotificationPermissionOrEnable() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_BACKUP_NOTIFICATIONS);
            return;
        }
        finishUnattendedBackupSetup();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BACKUP_NOTIFICATIONS) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            finishUnattendedBackupSetup();
        } else {
            wipePendingSetup();
            Toast.makeText(this, "ต้องอนุญาตการแจ้งเตือนเพื่อรับผลการสำรองข้อมูลอัตโนมัติ",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void finishUnattendedBackupSetup() {
        if (pendingSetupPassword == null || pendingSetupRecoveryKey == null
                || pendingSetupAccount == null) return;
        try {
            AndroidKeystoreCredentialVault vault = new AndroidKeystoreCredentialVault(this);
            vault.save(pendingSetupPassword, pendingSetupRecoveryKey.toCharArray());
            UnattendedBackupScheduler.enable(this, pendingSetupAccount, true, true);
            Toast.makeText(this, "เปิดการสำรองข้อมูลอัตโนมัติแล้ว", Toast.LENGTH_LONG).show();
        } catch (Exception failure) {
            Toast.makeText(this, "ตั้งค่าสำรองข้อมูลไม่สำเร็จ: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
            try {
                new AndroidKeystoreCredentialVault(this).clear();
            } catch (Exception ignored) { }
        } finally {
            wipePendingSetup();
        }
    }

    private void confirmDisableUnattendedBackup() {
        if (!UnattendedBackupScheduler.isEnabled(this)) {
            Toast.makeText(this, "ยังไม่ได้เปิดการสำรองข้อมูลอัตโนมัติ", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ปิดการสำรองข้อมูลอัตโนมัติ")
                .setMessage("ระบบจะยกเลิกงานตามกำหนดและลบข้อมูลกู้คืนที่เก็บไว้ในเครื่อง ไฟล์ที่อัปโหลดไป Google Drive จะไม่ถูกลบ")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("ปิดและลบข้อมูลกู้คืน", (dialog, which) -> {
                    try {
                        UnattendedBackupScheduler.disable(this);
                        Toast.makeText(this, "ปิดการสำรองข้อมูลอัตโนมัติแล้ว", Toast.LENGTH_LONG).show();
                    } catch (Exception failure) {
                        Toast.makeText(this, "ปิดระบบไม่สมบูรณ์: " + failure.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void wipePendingSetup() {
        if (pendingSetupPassword != null) Arrays.fill(pendingSetupPassword, '\0');
        pendingSetupPassword = null;
        pendingSetupRecoveryKey = null;
        pendingSetupAccount = null;
    }

    private void refreshDriveStatus() {
        if (driveStatus == null) return;
        String email = preferences().getString(KEY_ACCOUNT, null);
        String state = email == null ? "Google Drive: ยังไม่ได้เลือกบัญชี"
                : "Google Drive: " + email;
        if (UnattendedBackupScheduler.isEnabled(this)) state += "\nสำรองอัตโนมัติ: เปิดใช้งาน";
        driveStatus.setText(state);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
