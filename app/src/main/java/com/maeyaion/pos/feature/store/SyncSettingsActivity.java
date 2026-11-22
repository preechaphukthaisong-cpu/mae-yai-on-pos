package com.maeyaion.pos.feature.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.common.api.ApiException;
import com.maeyaion.pos.core.backup.BackupCrypto;
import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.sync.DeviceIdentityStore;
import com.maeyaion.pos.core.sync.DeviceKeyPairManager;
import com.maeyaion.pos.core.sync.GoogleSignInTokenProvider;
import com.maeyaion.pos.core.sync.StoreKeyEnrollmentService;
import com.maeyaion.pos.core.sync.SyncAccountBootstrapper;
import com.maeyaion.pos.core.sync.SyncApiClient;
import com.maeyaion.pos.core.sync.SyncBaselineCoordinator;
import com.maeyaion.pos.core.sync.SyncException;
import com.maeyaion.pos.core.sync.SyncGoogleSignIn;
import com.maeyaion.pos.core.sync.SyncReadinessGate;
import com.maeyaion.pos.core.sync.SyncScheduler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit multi-device setup and recovery controls. This screen never enables live sync:
 * readiness qualification, production deployment, and physical multi-device testing remain gates.
 */
public final class SyncSettingsActivity extends Activity {
    private static final int REQUEST_SIGN_IN = 7401;
    private static final String PREFS = "maeyai_sync_config_v1";
    private static final String KEY_URL = "server_url";
    private static final String KEY_CLIENT = "web_client_id";
    private static final String KEY_STORE = "store_id";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText serverUrlInput;
    private EditText webClientInput;
    private EditText recoveryKeyInput;
    private EditText recoveryPasswordInput;
    private EditText targetDeviceInput;
    private EditText targetFingerprintInput;
    private TextView status;
    private GoogleSignInClient signInClient;
    private String pendingRecoveryKey;
    private String pendingServerAuthCode;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        TextView heading = new TextView(this);
        heading.setText("ตั้งค่าซิงก์หลายเครื่อง");
        heading.setTextSize(22);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(heading);

        TextView warning = new TextView(this);
        warning.setText("โหมดซิงก์อัตโนมัติยังปิดอยู่ หน้านี้ใช้ลงทะเบียนอุปกรณ์ จัดการกุญแจ และสำรอง/กู้คืนข้อมูลเท่านั้น");
        warning.setPadding(0, dp(8), 0, dp(14));
        root.addView(warning);

        serverUrlInput = field(root, "Backend HTTPS URL", false);
        webClientInput = field(root, "Google Web Server OAuth Client ID", false);
        recoveryKeyInput = field(root, "Recovery Key (base64url 256-bit)", true);
        recoveryPasswordInput = field(root, "Recovery Password (อย่างน้อย 12 ตัว)", true);
        targetDeviceInput = field(root, "รหัสอุปกรณ์ที่ต้องการอนุมัติ", false);
        targetFingerprintInput = field(root, "Fingerprint ที่ตรวจสอบจากอุปกรณ์ปลายทาง", false);

        SharedPreferences preferences = preferences();
        serverUrlInput.setText(preferences.getString(KEY_URL, ""));
        webClientInput.setText(preferences.getString(KEY_CLIENT, ""));

        addButton(root, "บันทึกการตั้งค่าเซิร์ฟเวอร์", this::saveConfiguration);
        addButton(root, "ลงชื่อเข้าใช้ Google และลงทะเบียนอุปกรณ์นี้", this::startGoogleSignIn);
        addButton(root, "แสดงอุปกรณ์และ Fingerprint", this::loadDevices);
        addButton(root, "อนุมัติอุปกรณ์ปลายทางและส่งกุญแจทุก Epoch", this::approveDevice);
        addButton(root, "หมุนเวียน Store Key (Key Rotation)", this::rotateStoreKey);
        addButton(root, "เพิกถอนอุปกรณ์และหมุนเวียนกุญแจ", this::confirmRevokeDevice);
        addButton(root, "กู้คืนกุญแจทุก Epoch ด้วย Recovery Key", this::recoverKeyHistory);
        addButton(root, "ส่งออก Baseline เข้ารหัส", this::exportBaseline);
        addButton(root, "กู้คืน Baseline จากเซิร์ฟเวอร์", this::restoreBaseline);

        status = new TextView(this);
        status.setText("สถานะ: ยังไม่ได้ตรวจสอบการเชื่อมต่อ");
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(16), 0, dp(16));
        root.addView(status);

        addButton(root, "ปิดซิงก์และยกเลิกงานเบื้องหลัง", () -> {
            SyncReadinessGate.disable(this, "Disabled by operator");
            SyncScheduler.cancel(this);
            showStatus("ปิด Sync และยกเลิก WorkManager แล้ว");
        });
        addButton(root, "ย้อนกลับ", this::finish);
        setContentView(scroll);
    }

    private EditText field(LinearLayout parent, String hint, boolean secret) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(v -> action.run());
        parent.addView(button);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void saveConfiguration() {
        String url = serverUrlInput.getText().toString().trim();
        String client = webClientInput.getText().toString().trim();
        if (!url.matches("https://[^\\s]+") || !client.matches(
                "[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")) {
            showStatus("ต้องใช้ Backend HTTPS URL และ Google Web OAuth Client ID ที่ถูกต้อง");
            return;
        }
        if (!preferences().edit().putString(KEY_URL, url)
                .putString(KEY_CLIENT, client).commit()) {
            showStatus("บันทึกการตั้งค่าไม่สำเร็จ");
            return;
        }
        showStatus("บันทึกการตั้งค่าแล้ว");
    }

    private void startGoogleSignIn() {
        saveConfiguration();
        try {
            signInClient = SyncGoogleSignIn.createClient(this,
                    webClientInput.getText().toString().trim());
            startActivityForResult(signInClient.getSignInIntent(), REQUEST_SIGN_IN);
        } catch (SyncException error) {
            showStatus(error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SIGN_IN) return;
        if (resultCode != RESULT_OK || data == null) {
            showStatus("ยกเลิกการลงชื่อเข้าใช้ Google");
            return;
        }
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException.class);
            if (account == null || account.getIdToken() == null) {
                showStatus("Google ไม่ส่ง ID token กลับมา ตรวจสอบ OAuth client configuration");
                return;
            }
            pendingServerAuthCode = account.getServerAuthCode();
            registerDevice();
        } catch (ApiException error) {
            showStatus("Google Sign-in error: " + error.getStatusCode());
        }
    }

    private void registerDevice() {
        worker.execute(() -> {
            try {
                JSONObject registration = new SyncAccountBootstrapper(
                        api(), AppDatabase.getInstance(getApplicationContext())).ensureRegistered();
                String storeId = registration.getJSONObject("store").getString("storeId");
                String authCode = pendingServerAuthCode;
                pendingServerAuthCode = null;
                if (authCode != null) {
                    try { api().connectGoogleDrive(authCode); }
                    catch (SyncException driveError) {
                        // Drive is a backup mirror; event transport and local recovery remain usable.
                    }
                }
                preferences().edit().putString(KEY_STORE, storeId).commit();
                long epoch = api().getCurrentKeyEpoch().getLong("keyEpoch");
                String fingerprint = ownFingerprint();
                runOnUiThread(() -> {
                    showStatus("ลงทะเบียนแล้ว\nStore ID: " + storeId
                            + "\nDevice ID: " + new DeviceIdentityStore(this).getOrCreateDeviceId()
                            + "\nFingerprint: " + fingerprint
                            + "\nServer key epoch: " + epoch);
                    if (epoch == 0) promptFirstDeviceEnrollment(storeId);
                    else installApprovedKeys(storeId);
                });
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("ลงทะเบียนไม่สำเร็จ: " + message(error)));
            }
        });
    }

    private void promptFirstDeviceEnrollment(String storeId) {
        pendingRecoveryKey = BackupCrypto.generateRecoveryKey();
        TextView keyView = new TextView(this);
        keyView.setText(pendingRecoveryKey);
        keyView.setTextIsSelectable(true);
        keyView.setPadding(dp(12), dp(12), dp(12), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("Recovery Key ของร้าน")
                .setMessage("บันทึก Recovery Key นี้ไว้ในที่ปลอดภัยก่อนดำเนินการ ห้ามส่งให้ผู้อื่น ระบบจะไม่เก็บกุญแจนี้ไว้")
                .setView(keyView)
                .setNegativeButton("ยกเลิก", (dialog, which) -> pendingRecoveryKey = null)
                .setPositiveButton("บันทึกแล้ว เริ่มเข้ารหัส", (dialog, which) -> {
                    String recovery = pendingRecoveryKey;
                    pendingRecoveryKey = null;
                    if (recovery == null) return;
                    worker.execute(() -> {
                        try {
                            new StoreKeyEnrollmentService(this, api())
                                    .initializeFirstDevice(storeId, recovery);
                            runOnUiThread(() -> showStatus("สร้าง Store Key Epoch 1 และ Recovery Wrapper สำเร็จ"));
                        } catch (Exception error) {
                            runOnUiThread(() -> showStatus("ตั้งค่ากุญแจไม่สำเร็จ: " + message(error)));
                        }
                    });
                }).show();
    }

    private void installApprovedKeys(String storeId) {
        worker.execute(() -> {
            try {
                long epoch = new StoreKeyEnrollmentService(this, api())
                        .installAllApprovedDeviceKeys(storeId);
                runOnUiThread(() -> showStatus("ติดตั้งกุญแจทุก Epoch สำเร็จ: " + epoch));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("อุปกรณ์นี้ยังไม่ได้รับอนุมัติครบทุก Epoch: " + message(error)
                        + "\nให้เครื่องที่เชื่อถือแล้วอนุมัติ Device ID และ Fingerprint นี้ หรือใช้ Recovery Key"));
            }
        });
    }

    private void loadDevices() {
        worker.execute(() -> {
            try {
                JSONArray devices = api().listDevices().getJSONArray("devices");
                StringBuilder output = new StringBuilder("อุปกรณ์ที่ลงทะเบียนอยู่:\n");
                for (int i = 0; i < devices.length(); i++) {
                    JSONObject device = devices.getJSONObject(i);
                    output.append("\nชื่อ: ").append(device.optString("label"))
                            .append("\nDevice ID: ").append(device.getString("id"))
                            .append("\nFingerprint: ").append(device.optString("key_fingerprint"))
                            .append("\nKey enrolled: ").append(device.optBoolean("key_enrolled"))
                            .append("\n----------------\n");
                }
                runOnUiThread(() -> showStatus(output.toString()));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("อ่านรายการอุปกรณ์ไม่สำเร็จ: " + message(error)));
            }
        });
    }

    private void approveDevice() {
        String storeId = storedStoreId();
        String deviceId = targetDeviceInput.getText().toString().trim();
        String fingerprint = targetFingerprintInput.getText().toString().trim();
        if (storeId == null || deviceId.isEmpty() || fingerprint.isEmpty()) {
            showStatus("กรอก Store ID, Device ID และ Fingerprint ให้ครบ");
            return;
        }
        worker.execute(() -> {
            try {
                new StoreKeyEnrollmentService(this, api())
                        .approveDevice(storeId, deviceId, fingerprint);
                runOnUiThread(() -> showStatus("อนุมัติอุปกรณ์และส่งกุญแจย้อนหลังทุก Epoch แล้ว"));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("อนุมัติอุปกรณ์ไม่สำเร็จ: " + message(error)));
            }
        });
    }

    private void rotateStoreKey() {
        String storeId = storedStoreId();
        String recovery = recoveryKeyInput.getText().toString().trim();
        if (storeId == null || recovery.isEmpty()) {
            showStatus("ต้องลงทะเบียน Store และกรอก Recovery Key ก่อนหมุนเวียนกุญแจ");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ยืนยัน Key Rotation")
                .setMessage("ระบบจะสร้าง Store Key epoch ใหม่และส่ง wrapper ให้ทุกอุปกรณ์ที่ได้รับอนุมัติ")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("หมุนเวียนกุญแจ", (dialog, which) -> worker.execute(() -> {
                    try {
                        long epoch = new StoreKeyEnrollmentService(this, api())
                                .rotateStoreKey(storeId, recovery);
                        runOnUiThread(() -> showStatus("หมุนเวียน Store Key สำเร็จ: Epoch " + epoch));
                    } catch (Exception error) {
                        runOnUiThread(() -> showStatus("หมุนเวียนกุญแจไม่สำเร็จ: " + message(error)));
                    }
                })).show();
    }

    private void confirmRevokeDevice() {
        String storeId = storedStoreId();
        String deviceId = targetDeviceInput.getText().toString().trim();
        String fingerprint = targetFingerprintInput.getText().toString().trim();
        String recovery = recoveryKeyInput.getText().toString().trim();
        if (storeId == null || deviceId.isEmpty() || fingerprint.isEmpty() || recovery.isEmpty()) {
            showStatus("กรอก Device ID, Fingerprint ที่ตรวจสอบแล้ว และ Recovery Key ให้ครบ");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("เพิกถอนอุปกรณ์")
                .setMessage("อุปกรณ์ที่เลือกจะถูกเพิกถอน และ Store Key จะหมุนเวียนเพื่อไม่ให้ใช้งานกุญแจ epoch ใหม่ได้ ยืนยันเฉพาะเมื่อ Fingerprint ตรงกับเครื่องเป้าหมาย")
                .setNegativeButton("ยกเลิก", null)
                .setPositiveButton("เพิกถอนและหมุนเวียน", (dialog, which) -> worker.execute(() -> {
                    try {
                        new StoreKeyEnrollmentService(this, api())
                                .approveDevice(storeId, deviceId, fingerprint);
                        long epoch = new StoreKeyEnrollmentService(this, api())
                                .rotateAndRevokeDevice(storeId, recovery, deviceId);
                        runOnUiThread(() -> showStatus("เพิกถอนอุปกรณ์และหมุนเวียนกุญแจสำเร็จ: Epoch " + epoch));
                    } catch (Exception error) {
                        runOnUiThread(() -> showStatus("เพิกถอนอุปกรณ์ไม่สำเร็จ: " + message(error)));
                    }
                })).show();
    }

    private void recoverKeyHistory() {
        String storeId = storedStoreId();
        String recovery = recoveryKeyInput.getText().toString().trim();
        if (storeId == null || recovery.isEmpty()) {
            showStatus("ต้องลงทะเบียน Store และกรอก Recovery Key");
            return;
        }
        worker.execute(() -> {
            try {
                long epoch = new StoreKeyEnrollmentService(this, api())
                        .recoverAllDeviceKeyEpochs(storeId, recovery);
                runOnUiThread(() -> showStatus("กู้คืนกุญแจทุก Epoch สำเร็จ: " + epoch));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("กู้คืนกุญแจไม่สำเร็จ: " + message(error)));
            }
        });
    }

    private void exportBaseline() {
        String storeId = storedStoreId();
        String recovery = recoveryKeyInput.getText().toString().trim();
        char[] password = recoveryPasswordInput.getText().toString().toCharArray();
        if (storeId == null || recovery.isEmpty() || password.length < 12) {
            java.util.Arrays.fill(password, '\0');
            showStatus("ต้องกรอก Recovery Key และ Recovery Password อย่างน้อย 12 ตัว");
            return;
        }
        worker.execute(() -> {
            try {
                SyncBaselineCoordinator.BaselineResult result =
                        new SyncBaselineCoordinator().exportAndUpload(this,
                                AppDatabase.getInstance(getApplicationContext()), api(),
                                storeId, password, recovery);
                runOnUiThread(() -> showStatus("อัปโหลด Baseline สำเร็จ\nSnapshot ID: "
                        + result.snapshotId + "\nSequence: " + result.throughSequence));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("ส่งออก Baseline ไม่สำเร็จ: " + message(error)));
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        });
    }

    private void restoreBaseline() {
        String storeId = storedStoreId();
        if (storeId == null) {
            showStatus("ต้องลงทะเบียน Store ก่อนกู้คืน Baseline");
            return;
        }
        worker.execute(() -> {
            try {
                JSONArray backups = api().listBackups().getJSONArray("backups");
                if (backups.length() == 0) {
                    runOnUiThread(() -> showStatus("ยังไม่มี Baseline บนเซิร์ฟเวอร์"));
                    return;
                }
                String[] labels = new String[backups.length()];
                for (int i = 0; i < backups.length(); i++) {
                    JSONObject item = backups.getJSONObject(i);
                    labels[i] = item.optString("id") + " · seq " + item.optString("through_seq");
                }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("เลือก Baseline")
                        .setItems(labels, (dialog, index) -> restoreSelectedBackup(backups, index, storeId))
                        .setNegativeButton("ยกเลิก", null).show());
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("อ่าน Baseline ไม่สำเร็จ: " + message(error)));
            }
        });
    }

    private void restoreSelectedBackup(JSONArray backups, int index, String storeId) {
        String recovery = recoveryKeyInput.getText().toString().trim();
        char[] password = recoveryPasswordInput.getText().toString().toCharArray();
        boolean usePassword = password.length > 0;
        if (usePassword == !recovery.isEmpty()) {
            java.util.Arrays.fill(password, '\0');
            showStatus("กรอก Recovery Password หรือ Recovery Key เพียงอย่างเดียว");
            return;
        }
        worker.execute(() -> {
            try {
                JSONObject selected = backups.getJSONObject(index);
                new SyncBaselineCoordinator().restoreDownloaded(this,
                        AppDatabase.getInstance(getApplicationContext()), api(), storeId,
                        selected.getString("id"), selected.getJSONObject("manifest"),
                        usePassword ? password : null, usePassword ? null : recovery);
                runOnUiThread(() -> showStatus("กู้คืน Baseline และตั้งค่า Sync Cursor สำเร็จ"));
            } catch (Exception error) {
                runOnUiThread(() -> showStatus("กู้คืน Baseline ไม่สำเร็จ: " + message(error)));
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
        });
    }

    private SyncApiClient api() throws SyncException {
        String url = preferences().getString(KEY_URL, "");
        String clientId = preferences().getString(KEY_CLIENT, "");
        if (signInClient == null) signInClient = SyncGoogleSignIn.createClient(this, clientId);
        return new SyncApiClient(url, new GoogleSignInTokenProvider(signInClient),
                new DeviceIdentityStore(this));
    }

    private String storedStoreId() {
        return preferences().getString(KEY_STORE, null);
    }

    private String ownFingerprint() throws Exception {
        String encoded = new DeviceKeyPairManager(this).getPublicKeyBase64();
        byte[] der = java.util.Base64.getUrlDecoder().decode(encoded);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02X", b & 255));
        return out.toString();
    }

    private void showStatus(String value) {
        if (status != null) status.setText(value);
        Toast.makeText(this, value.length() > 80 ? value.substring(0, 80) : value,
                Toast.LENGTH_SHORT).show();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }
}
