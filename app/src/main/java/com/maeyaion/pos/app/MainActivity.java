package com.maeyaion.pos.app;

import androidx.activity.ComponentActivity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
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
import com.maeyaion.pos.R;
import com.maeyaion.pos.core.backup.BackupCrypto;
import com.maeyaion.pos.core.backup.BackupRestoreCoordinator;
import com.maeyaion.pos.core.backup.FullBackupCoordinator;
import com.maeyaion.pos.core.backup.DriveBackupWorkScheduler;
import com.maeyaion.pos.core.backup.GoogleDriveBackupListWorker;
import com.maeyaion.pos.core.backup.GoogleDriveBackupDownloadWorker;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import org.json.JSONArray;
import org.json.JSONObject;
import com.maeyaion.pos.core.db.AppDatabase;

import java.io.File;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MainActivity extends ComponentActivity {
    private static final int REQUEST_DRIVE_ACCOUNT = 4102;
    private static final String DRIVE_SCOPE =
            "https://www.googleapis.com/auth/drive.appdata";
    private static final String PREFS = "drive_account_preferences";
    private static final String KEY_ACCOUNT = "selected_google_account";

    private TextView driveStatus;
    private EditText recoveryPassword;
    private EditText recoveryKey;
    private Button localBackupButton;
    private Button driveBackupButton;
    private Button restoreButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderMainMenu();
        refreshDriveStatus();
        refreshBackupButtons();
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

        TextView credentialHelp = new TextView(this);
        credentialHelp.setText("กำหนดรหัสผ่านอย่างน้อย 12 ตัวอักษร และสร้าง Recovery Key เก็บแยกไว้ "
                + "ใช้รหัสผ่านหรือ Recovery Key เพื่อกู้คืนข้อมูล");
        root.addView(credentialHelp, matchWrap());

        recoveryPassword = new EditText(this);
        recoveryPassword.setSingleLine(true);
        recoveryPassword.setHint("Recovery Password (อย่างน้อย 12 ตัวอักษร)");
        recoveryPassword.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(recoveryPassword, matchWrap());

        recoveryKey = new EditText(this);
        recoveryKey.setSingleLine(true);
        recoveryKey.setHint("Recovery Key — สร้างใหม่หรือกรอก key เดิม");
        recoveryKey.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(recoveryKey, matchWrap());

        Button generateKey = new Button(this);
        generateKey.setText("สร้าง Recovery Key ใหม่");
        generateKey.setOnClickListener(view -> {
            String generated = BackupCrypto.generateRecoveryKey();
            recoveryKey.setText(generated);
            recoveryKey.setSelection(generated.length());
            new AlertDialog.Builder(this)
                    .setTitle("Recovery Key")
                    .setMessage(generated + "\n\nจดเก็บไว้นอกโทรศัพท์ก่อนดำเนินการต่อ")
                    .setPositiveButton("รับทราบ", null)
                    .show();
        });
        root.addView(generateKey, matchWrap());

        localBackupButton = new Button(this);
        localBackupButton.setText("สำรองข้อมูลไว้ในเครื่อง");
        localBackupButton.setOnClickListener(view -> createBackup(false));
        root.addView(localBackupButton, matchWrap());

        driveStatus = new TextView(this);
        driveStatus.setTextSize(14);
        driveStatus.setPadding(0, dp(12), 0, 0);
        root.addView(driveStatus, matchWrap());

        Button connectDrive = new Button(this);
        connectDrive.setText("เลือกบัญชี Google สำหรับสำรองข้อมูล");
        connectDrive.setOnClickListener(view -> launchDriveAccountPicker());
        root.addView(connectDrive, matchWrap());

        driveBackupButton = new Button(this);
        driveBackupButton.setText("สำรองข้อมูลไป Google Drive");
        driveBackupButton.setOnClickListener(view -> createBackup(true));
        root.addView(driveBackupButton, matchWrap());

        restoreButton = new Button(this);
        restoreButton.setText("กู้คืนจากข้อมูลสำรองในเครื่อง");
        restoreButton.setOnClickListener(view -> chooseLocalBackupToRestore());
        root.addView(restoreButton, matchWrap());

        Button driveRestore = new Button(this);
        driveRestore.setText("กู้คืนจาก Google Drive");
        driveRestore.setOnClickListener(view -> promptDriveRestorePassword());
        root.addView(driveRestore, matchWrap());

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

    private void createBackup(boolean uploadToDrive) {
        String password = recoveryPassword.getText().toString();
        String key = recoveryKey.getText().toString().trim();
        if (password.length() < 12 || key.isEmpty()) {
            Toast.makeText(this, "กรอกรหัสผ่านอย่างน้อย 12 ตัวอักษรและ Recovery Key",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String account = preferences().getString(KEY_ACCOUNT, null);
        if (uploadToDrive && (account == null || account.trim().isEmpty())) {
            Toast.makeText(this, "เลือกบัญชี Google ก่อนสำรองขึ้น Drive", Toast.LENGTH_LONG).show();
            launchDriveAccountPicker();
            return;
        }

        final char[] passwordChars = password.toCharArray();
        recoveryPassword.setText("");
        setBackupActionsEnabled(false);
        Toast.makeText(this, "กำลังสร้างข้อมูลสำรองแบบเข้ารหัส…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                FullBackupCoordinator coordinator = new FullBackupCoordinator();
                File backup = uploadToDrive
                        ? coordinator.createAndQueue(this, AppDatabase.getInstance(this),
                                account, passwordChars, key)
                        : coordinator.createLocalBackup(this, AppDatabase.getInstance(this),
                                passwordChars, key);
                runOnUiThread(() -> Toast.makeText(this,
                        uploadToDrive
                                ? "สร้างไฟล์สำรองแล้วและเข้าคิวอัปโหลด Drive: " + backup.getName()
                                : "สำรองข้อมูลในเครื่องสำเร็จ: " + backup.getName(),
                        Toast.LENGTH_LONG).show());
            } catch (Exception failure) {
                runOnUiThread(() -> Toast.makeText(this,
                        "สำรองข้อมูลไม่สำเร็จ: " + failure.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                Arrays.fill(passwordChars, '\0');
                runOnUiThread(() -> {
                    setBackupActionsEnabled(true);
                    refreshBackupButtons();
                });
            }
        }, "pos-backup-create").start();
    }

    private void chooseLocalBackupToRestore() {
        File directory = new File(getFilesDir(), "backups");
        File[] backups = directory.listFiles((dir, name) -> name.endsWith(".myobak"));
        if (backups == null || backups.length == 0) {
            Toast.makeText(this, "ยังไม่มีข้อมูลสำรองในเครื่อง", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());
        String[] labels = new String[backups.length];
        for (int i = 0; i < backups.length; i++) {
            labels[i] = backups[i].getName();
        }
        new AlertDialog.Builder(this)
                .setTitle("เลือกข้อมูลสำรองที่จะกู้คืน")
                .setItems(labels, (dialog, which) -> confirmRestore(backups[which]))
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void promptDriveRestorePassword() {
        String account = preferences().getString(KEY_ACCOUNT, null);
        if (account == null || account.trim().isEmpty()) {
            Toast.makeText(this, "เลือกบัญชี Google ก่อนกู้คืน", Toast.LENGTH_LONG).show();
            launchDriveAccountPicker();
            return;
        }
        promptRecoveryCredentials("กู้คืนจาก Google Drive",
                "กรอก Recovery Password หรือ Recovery Key แล้วเลือกไฟล์ที่จะดาวน์โหลด",
                (password, key) -> loadDriveBackupList(account, password, key));
    }

    private void loadDriveBackupList(String account, char[] password, String recoveryKey) {
        final UUID workId;
        try {
            workId = DriveBackupWorkScheduler.enqueueList(this, account);
        } catch (RuntimeException failure) {
            if (password != null) Arrays.fill(password, '\0');
            Toast.makeText(this, "เริ่มค้นหา Drive ไม่สำเร็จ: " + failure.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "กำลังค้นหาข้อมูลสำรองใน Google Drive…", Toast.LENGTH_SHORT).show();
        LiveData<WorkInfo> workInfo = WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId);
        workInfo.observe(this, new Observer<WorkInfo>() {
                    @Override public void onChanged(WorkInfo info) {
                        if (info == null || !info.getState().isFinished()) return;
                        workInfo.removeObserver(this);
                        if (info.getState() != WorkInfo.State.SUCCEEDED) {
                            if (password != null) Arrays.fill(password, '\0');
                            boolean auth = info.getOutputData().getBoolean(
                                    GoogleDriveBackupListWorker.OUTPUT_AUTH_REQUIRED, false);
                            Toast.makeText(MainActivity.this, auth
                                    ? "Google ต้องการอนุญาตสิทธิ์ Drive ใหม่ กรุณาเลือกบัญชีอีกครั้ง"
                                    : "ค้นหาข้อมูลสำรองใน Drive ไม่สำเร็จ",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        try {
                            String json = info.getOutputData().getString(
                                    GoogleDriveBackupListWorker.OUTPUT_FILE_LIST_JSON);
                            JSONArray files = new JSONObject(json == null ? "{}" : json)
                                    .optJSONArray("files");
                            List<String> ids = new ArrayList<>();
                            List<String> names = new ArrayList<>();
                            if (files != null) {
                                for (int i = 0; i < files.length(); i++) {
                                    JSONObject file = files.optJSONObject(i);
                                    if (file == null) continue;
                                    String name = file.optString("name", "");
                                    String id = file.optString("id", "");
                                    if (name.endsWith(".myobak")
                                            && GoogleDriveBackupDownloadWorker.isValidDriveFileId(id)) {
                                        names.add(name);
                                        ids.add(id);
                                    }
                                }
                            }
                            if (ids.isEmpty()) {
                                if (password != null) Arrays.fill(password, '\0');
                                Toast.makeText(MainActivity.this,
                                        "ไม่พบไฟล์สำรอง .myobak ใน Google Drive",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            String[] labels = names.toArray(new String[0]);
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("เลือกไฟล์สำรองจาก Drive")
                                    .setItems(labels, (dialog, selected) ->
                                            downloadDriveBackup(account, ids.get(selected), password, recoveryKey))
                                    .setNegativeButton("ยกเลิก", (dialog, which) ->
                                            if (password != null) Arrays.fill(password, '\0'))
                                    .setOnCancelListener(dialog -> if (password != null) Arrays.fill(password, '\0'))
                                    .show();
                        } catch (Exception malformed) {
                            if (password != null) Arrays.fill(password, '\0');
                            Toast.makeText(MainActivity.this,
                                    "อ่านรายการข้อมูลสำรองจาก Drive ไม่สำเร็จ",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    private void downloadDriveBackup(String account, String driveFileId, char[] password,
            String recoveryKey) {
        final UUID workId;
        try {
            workId = DriveBackupWorkScheduler.enqueueDownload(this, account, driveFileId);
        } catch (RuntimeException failure) {
            if (password != null) Arrays.fill(password, '\0');
            Toast.makeText(this, "เริ่มดาวน์โหลดไม่สำเร็จ", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "กำลังดาวน์โหลดไฟล์สำรองแบบเข้ารหัส…", Toast.LENGTH_SHORT).show();
        LiveData<WorkInfo> workInfo = WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId);
        workInfo.observe(this, new Observer<WorkInfo>() {
            @Override public void onChanged(WorkInfo info) {
                if (info == null || !info.getState().isFinished()) return;
                workInfo.removeObserver(this);
                if (info.getState() != WorkInfo.State.SUCCEEDED) {
                    if (password != null) Arrays.fill(password, '\0');
                    boolean auth = info.getOutputData().getBoolean(
                            GoogleDriveBackupDownloadWorker.OUTPUT_AUTH_REQUIRED, false);
                    Toast.makeText(MainActivity.this, auth
                            ? "Google ต้องอนุญาตสิทธิ์ Drive ใหม่ กรุณาเลือกบัญชีอีกครั้ง"
                            : "ดาวน์โหลดข้อมูลสำรองไม่สำเร็จ",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String path = info.getOutputData().getString(
                        GoogleDriveBackupDownloadWorker.OUTPUT_BACKUP_PATH);
                if (path == null) {
                    if (password != null) Arrays.fill(password, '\0');
                    Toast.makeText(MainActivity.this,
                            "Drive ไม่ส่งตำแหน่งไฟล์สำรองกลับมา", Toast.LENGTH_LONG).show();
                    return;
                }
                restoreBackupFile(new File(path), password, recoveryKey);
            }
        });
    }

    private void restoreBackupFile(File backup, char[] password, String recoveryKey) {
        restoreButton.setEnabled(false);
        new Thread(() -> {
            try {
                new BackupRestoreCoordinator().restoreDownloadedBackup(this,
                        AppDatabase.getInstance(this), backup, password, recoveryKey);
                runOnUiThread(() -> Toast.makeText(this,
                        "กู้คืนข้อมูลสำเร็จ กรุณาตรวจสอบสินค้าและยอดขาย",
                        Toast.LENGTH_LONG).show());
            } catch (Exception failure) {
                String message = BackupErrorMessages.toUserMessage(failure);
                runOnUiThread(() -> Toast.makeText(this,
                        message, Toast.LENGTH_LONG).show());
            } finally {
                if (password != null) Arrays.fill(password, '\0');
                runOnUiThread(() -> restoreButton.setEnabled(true));
            }
        }, "pos-drive-restore").start();
    }

    private void confirmRestore(File backup) {
        promptRecoveryCredentials("ยืนยันการกู้คืน",
                "การกู้คืนจะแทนที่ฐานข้อมูลปัจจุบัน: " + backup.getName()
                        + "\nระบบจะสร้าง safety snapshot ก่อนแทนที่",
                (password, key) -> {
                    restoreButton.setEnabled(false);
                    new Thread(() -> {
                        try {
                            new BackupRestoreCoordinator().restoreDownloadedBackup(this,
                                    AppDatabase.getInstance(this), backup, password, key);
                            runOnUiThread(() -> Toast.makeText(this,
                                    "กู้คืนข้อมูลสำเร็จ กรุณาตรวจสอบสินค้าและยอดขาย",
                                    Toast.LENGTH_LONG).show());
                        } catch (Exception failure) {
                            String message = BackupErrorMessages.toUserMessage(failure);
                            runOnUiThread(() -> Toast.makeText(this,
                                    message, Toast.LENGTH_LONG).show());
                        } finally {
                            if (password != null) Arrays.fill(password, '\0');
                            runOnUiThread(() -> restoreButton.setEnabled(true));
                        }
                    }, "pos-backup-restore").start();
                });
    }

    private interface RecoveryCredentialCallback {
        void onCredentials(char[] password, String recoveryKey);
    }

    private void promptRecoveryCredentials(String title, String message,
            RecoveryCredentialCallback callback) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        fields.setPadding(horizontal, dp(8), horizontal, 0);

        EditText passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setHint("Recovery Password (กรอกอย่างใดอย่างหนึ่ง)");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(passwordInput, matchWrap());

        EditText keyInput = new EditText(this);
        keyInput.setSingleLine(true);
        keyInput.setHint("Recovery Key (กรอกอย่างใดอย่างหนึ่ง)");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT);
        fields.addView(keyInput, matchWrap());

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setView(fields)
                .setPositiveButton("ดำเนินการ", (dialog, which) -> {
                    String passwordValue = passwordInput.getText().toString();
                    String keyValue = keyInput.getText().toString().trim();
                    boolean hasPassword = !passwordValue.isEmpty();
                    boolean hasKey = !keyValue.isEmpty();
                    if (hasPassword == hasKey) {
                        Toast.makeText(this,
                                "กรอก Recovery Password หรือ Recovery Key เพียงอย่างเดียว",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    callback.onCredentials(hasPassword ? passwordValue.toCharArray() : null,
                            hasKey ? keyValue : null);
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void setBackupActionsEnabled(boolean enabled) {
        localBackupButton.setEnabled(enabled);
        driveBackupButton.setEnabled(enabled);
        restoreButton.setEnabled(enabled);
    }

    private void refreshBackupButtons() {
        if (restoreButton == null) return;
        File directory = new File(getFilesDir(), "backups");
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".myobak"));
        restoreButton.setEnabled(files != null && files.length > 0);
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
            Toast.makeText(this, resultCode == RESULT_CANCELED
                    ? "Google Sign-In ถูกยกเลิกหรือไม่ส่งข้อมูลกลับมา"
                    : "Google Sign-In ไม่ส่งข้อมูลผลลัพธ์กลับมา",
                    Toast.LENGTH_LONG).show();
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
            int status = failure.getStatusCode();
            String message;
            if (status == 10) {
                message = "Google Sign-In ตั้งค่า OAuth ไม่ตรงกับแอป (ตรวจ Package name และ SHA-1 ของ APK)";
            } else if (status == 12500) {
                message = "Google Sign-In ไม่สำเร็จ (12500) กรุณาตรวจ OAuth Client และการตั้งค่าบัญชี";
            } else if (status == 12501 || resultCode == RESULT_CANCELED) {
                message = "Google Sign-In ถูกยกเลิกโดยระบบ (12501); หากไม่ได้กดยกเลิก ให้ตรวจ OAuth และ Log";
            } else if (status == 7) {
                message = "เชื่อมต่อ Google ไม่ได้ กรุณาตรวจอินเทอร์เน็ต";
            } else {
                message = "Google Sign-In ไม่สำเร็จ (รหัส " + status + ")";
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
