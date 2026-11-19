package com.maeyaion.pos.core.backup;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Lists encrypted backup metadata in the signed-in user's Drive appDataFolder. */
public final class GoogleDriveBackupListWorker extends Worker {
    public static final String INPUT_ACCOUNT_NAME = "google_account_name";
    public static final String OUTPUT_FILE_LIST_JSON = "drive_backup_file_list_json";
    public static final String OUTPUT_AUTH_REQUIRED = "auth_required";

    private static final String TOKEN_SCOPE =
            "oauth2:https://www.googleapis.com/auth/drive.appdata";
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    public GoogleDriveBackupListWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull @Override
    public Result doWork() {
        String account = getInputData().getString(INPUT_ACCOUNT_NAME);
        if (TextUtils.isEmpty(account)) return Result.failure();

        String token;
        try {
            token = GoogleAuthUtil.getToken(getApplicationContext(), account, TOKEN_SCOPE);
        } catch (UserRecoverableAuthException consentRequired) {
            return Result.failure(new Data.Builder()
                    .putBoolean(OUTPUT_AUTH_REQUIRED, true).build());
        } catch (GoogleAuthException | IOException authFailure) {
            return Result.retry();
        }

        HttpURLConnection connection = null;
        try {
            String query = "trashed = false";
            String url = "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder"
                    + "&q=" + URLEncoder.encode(query, "UTF-8")
                    + "&orderBy=createdTime%20desc&pageSize=50"
                    + "&fields=files(id,name,size,createdTime,modifiedTime,mimeType)";
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + token);

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                try { GoogleAuthUtil.clearToken(getApplicationContext(), token); }
                catch (GoogleAuthException | IOException ignored) { }
                return Result.retry();
            }
            if (status == 429 || status >= 500) return Result.retry();
            if (status < 200 || status >= 300) return Result.failure();

            String json = readLimited(connection.getInputStream(), MAX_RESPONSE_BYTES);
            return Result.success(new Data.Builder()
                    .putString(OUTPUT_FILE_LIST_JSON, json).build());
        } catch (IOException failure) {
            return Result.retry();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() + read > limit) {
                    throw new IOException("Drive backup list response exceeds safe WorkManager output size");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }
}
