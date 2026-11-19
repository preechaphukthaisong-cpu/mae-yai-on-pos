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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Downloads one Drive appDataFolder object into private staging storage.
 * It never decrypts the object or activates a live database.
 */
public final class GoogleDriveBackupDownloadWorker extends Worker {
    public static final String INPUT_ACCOUNT_NAME = "google_account_name";
    public static final String INPUT_DRIVE_FILE_ID = "drive_file_id";
    public static final String OUTPUT_BACKUP_PATH = "downloaded_backup_path";
    public static final String OUTPUT_AUTH_REQUIRED = "auth_required";

    private static final String TOKEN_SCOPE =
            "oauth2:https://www.googleapis.com/auth/drive.appdata";
    private static final long MAX_BYTES = 512L * 1024L * 1024L + 256L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    public GoogleDriveBackupDownloadWorker(@NonNull Context context,
            @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull @Override
    public Result doWork() {
        String account = getInputData().getString(INPUT_ACCOUNT_NAME);
        String driveId = getInputData().getString(INPUT_DRIVE_FILE_ID);
        if (TextUtils.isEmpty(account) || !isValidDriveFileId(driveId)) {
            return Result.failure();
        }

        String token;
        try {
            token = GoogleAuthUtil.getToken(getApplicationContext(), account, TOKEN_SCOPE);
        } catch (UserRecoverableAuthException consentRequired) {
            return Result.failure(new Data.Builder()
                    .putBoolean(OUTPUT_AUTH_REQUIRED, true).build());
        } catch (GoogleAuthException | IOException authFailure) {
            return Result.retry();
        }

        File temporary = null;
        HttpURLConnection connection = null;
        try {
            File directory = downloadDirectory(getApplicationContext());
            temporary = new File(directory, "." + UUID.randomUUID() + ".download.tmp");
            File destination = new File(directory, UUID.randomUUID() + ".myobak");
            connection = (HttpURLConnection) new URL(
                    "https://www.googleapis.com/drive/v3/files/" + driveId + "?alt=media")
                    .openConnection();
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

            long declaredLength = connection.getContentLengthLong();
            if (declaredLength == 0 || declaredLength > MAX_BYTES) return Result.failure();
            long written = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(temporary, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_BYTES) throw new IOException("Drive backup exceeds supported size");
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            }
            if (written <= 0 || (declaredLength >= 0 && written != declaredLength)) {
                throw new IOException("Drive download length did not match response metadata");
            }
            BackupCrypto.validateEnvelopeFile(temporary);
            if (!temporary.renameTo(destination)) {
                throw new IOException("Unable to publish downloaded encrypted backup");
            }
            return Result.success(new Data.Builder()
                    .putString(OUTPUT_BACKUP_PATH, destination.getAbsolutePath()).build());
        } catch (java.security.GeneralSecurityException invalidEnvelope) {
            return Result.failure();
        } catch (IOException networkOrStorageFailure) {
            return Result.retry();
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary != null && temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    static boolean isValidDriveFileId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{10,256}");
    }

    private static File downloadDirectory(Context context) throws IOException {
        File root = context.getFilesDir().getCanonicalFile();
        File directory = new File(root, "backups/downloads");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private backup download directory");
        }
        File canonical = directory.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getPath() + File.separator)
                || !canonical.canWrite()) {
            throw new IOException("Download directory is outside private app storage");
        }
        return canonical;
    }
}
