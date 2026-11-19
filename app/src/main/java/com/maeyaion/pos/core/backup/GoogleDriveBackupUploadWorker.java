package com.maeyaion.pos.core.backup;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uploads one already-encrypted .myobak file to the Drive appDataFolder.
 * Recovery credentials and local Keystore sidecars are never sent to Drive.
 */
public final class GoogleDriveBackupUploadWorker extends Worker {
    public static final String INPUT_ACCOUNT_NAME = "google_account_name";
    public static final String INPUT_ENCRYPTED_BACKUP_PATH = "encrypted_backup_path";
    public static final String OUTPUT_AUTH_REQUIRED = "auth_required";
    public static final String OUTPUT_DRIVE_FILE_ID = "drive_file_id";
    private static final String DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private static final String TOKEN_SCOPE = "oauth2:" + DRIVE_APPDATA_SCOPE;
    private static final long MAX_UPLOAD_BYTES = 512L * 1024L * 1024L + 256L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    public GoogleDriveBackupUploadWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull @Override
    public Result doWork() {
        String accountName = getInputData().getString(INPUT_ACCOUNT_NAME);
        String backupPath = getInputData().getString(INPUT_ENCRYPTED_BACKUP_PATH);
        if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(backupPath)) {
            return Result.failure();
        }

        File backup;
        try {
            backup = validatePrivateEncryptedBackup(backupPath);
        } catch (IOException | java.security.GeneralSecurityException invalidInput) {
            return Result.failure();
        }

        String token;
        try {
            token = GoogleAuthUtil.getToken(getApplicationContext(), accountName, TOKEN_SCOPE);
        } catch (UserRecoverableAuthException needsUserConsent) {
            return Result.failure(new Data.Builder().putBoolean(OUTPUT_AUTH_REQUIRED, true).build());
        } catch (GoogleAuthException | IOException authFailure) {
            return Result.retry();
        }

        HttpURLConnection connection = null;
        try {
            String existingDriveId = findExistingDriveFile(token, backup.getName());
            if (existingDriveId != null) {
                return Result.success(new Data.Builder()
                        .putString(OUTPUT_DRIVE_FILE_ID, existingDriveId).build());
            }
            connection = (HttpURLConnection) new URL(
                    "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size")
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            String boundary = "maeyaion_" + UUID.randomUUID().toString().replace("-", "");
            byte[] metadata = ("{\"name\":\"" + backup.getName()
                    + "\",\"parents\":[\"appDataFolder\"]}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] firstPart = ("--" + boundary + "\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] secondPart = ("\r\n--" + boundary + "\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] closing = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            long contentLength = (long) firstPart.length + metadata.length + secondPart.length
                    + backup.length() + closing.length;
            connection.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(contentLength);

            try (BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream());
                 BufferedInputStream input = new BufferedInputStream(new FileInputStream(backup))) {
                output.write(firstPart);
                output.write(metadata);
                output.write(secondPart);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.write(closing);
                output.flush();
            }

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                try { GoogleAuthUtil.clearToken(getApplicationContext(), token); }
                catch (GoogleAuthException | IOException ignored) { }
                return Result.retry();
            }
            if (status == 429 || status >= 500) return Result.retry();
            if (status < 200 || status >= 300) return Result.failure();

            String response = readLimited(connection.getInputStream(), 32 * 1024);
            String driveFileId = extractDriveFileId(response);
            Data output = new Data.Builder()
                    .putString(OUTPUT_DRIVE_FILE_ID, driveFileId)
                    .build();
            return Result.success(output);
        } catch (IOException networkFailure) {
            return Result.retry();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** A 2xx upload without a Drive file ID is ambiguous; retry and reconcile by name. */
    static String extractDriveFileId(String response) throws IOException {
        if (response == null || response.length() > 32 * 1024) {
            throw new IOException("Drive upload response is missing or too large");
        }
        Matcher matcher = Pattern.compile("\\\"id\\\"\\\\s*:\\\\s*\\\"([^\\\"]+)\\\"").matcher(response);
        if (!matcher.find() || matcher.group(1).isEmpty()) {
            throw new IOException("Drive upload response did not contain a file ID");
        }
        return matcher.group(1);
    }

    /** Reconciles a prior successful upload whose response may have been lost. */
    private String findExistingDriveFile(String token, String fileName) throws IOException {
        String query = "name = '" + fileName.replace("'", "\\'") + "' and trashed = false";
        String url = "https://www.googleapis.com/drive/v3/files?q="
                + URLEncoder.encode(query, "UTF-8")
                + "&spaces=appDataFolder&pageSize=10&fields=files(id,name)";
        HttpURLConnection lookup = (HttpURLConnection) new URL(url).openConnection();
        try {
            lookup.setRequestMethod("GET");
            lookup.setConnectTimeout(CONNECT_TIMEOUT_MS);
            lookup.setReadTimeout(30_000);
            lookup.setRequestProperty("Authorization", "Bearer " + token);
            int status = lookup.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) return null;
            if (status == 429 || status >= 500) throw new IOException("Drive lookup temporarily unavailable");
            if (status < 200 || status >= 300) return null;
            String response = readLimited(lookup.getInputStream(), 32 * 1024);
            Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
            return matcher.find() ? matcher.group(1) : null;
        } finally {
            lookup.disconnect();
        }
    }

    private File validatePrivateEncryptedBackup(String path)
            throws IOException, java.security.GeneralSecurityException {
        File candidate = new File(path).getCanonicalFile();
        File privateRoot = getApplicationContext().getFilesDir().getCanonicalFile();
        String root = privateRoot.getPath() + File.separator;
        if (!candidate.getPath().startsWith(root) || !candidate.isFile()
                || !candidate.getName().endsWith(".myobak")
                || candidate.length() <= 0 || candidate.length() > MAX_UPLOAD_BYTES) {
            throw new IOException("Only app-private encrypted backup files may be uploaded");
        }
        BackupCrypto.validateEnvelopeFile(candidate);
        return candidate;
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() + read > limit) throw new IOException("Drive response exceeded limit");
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }
}
