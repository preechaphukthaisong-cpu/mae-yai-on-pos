package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Strict HTTPS transport for the v1 sync API.
 *
 * This class deliberately does not mutate Room. Callers must persist push
 * acknowledgements and pull cursors transactionally, and validate/apply remote
 * events through domain services before enabling background synchronization.
 */
public final class SyncApiClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BACKUP_BYTES = 128 * 1024 * 1024;

    private final String baseUrl;
    private final SyncAuthTokenProvider tokenProvider;
    private final DeviceIdentityStore identityStore;

    public SyncApiClient(@NonNull String baseUrl,
            @NonNull SyncAuthTokenProvider tokenProvider,
            @NonNull DeviceIdentityStore identityStore) throws SyncException {
        if (baseUrl == null || tokenProvider == null || identityStore == null) {
            throw new IllegalArgumentException("Sync API configuration is required");
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        try {
            URI parsed = new URI(normalized);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getQuery() != null
                    || parsed.getFragment() != null || parsed.getPath() == null
                    || (parsed.getPort() != -1 && (parsed.getPort() < 1 || parsed.getPort() > 65535))) {
                throw new SyncException("Sync server URL must be a valid HTTPS origin with no credentials, query, or fragment");
            }
        } catch (URISyntaxException error) {
            throw new SyncException("Sync server URL is invalid", error);
        }
        this.baseUrl = normalized;
        this.tokenProvider = tokenProvider;
        this.identityStore = identityStore;
    }

    @NonNull public JSONObject getStore() throws SyncException {
        return requestJson("GET", "/v1/stores/me", null, false);
    }

    @NonNull public JSONObject createStore() throws SyncException {
        return requestJson("POST", "/v1/stores", new JSONObject(), false);
    }

    @NonNull public JSONObject registerDevice() throws SyncException {
        JSONObject body = new JSONObject();
        try {
            body.put("deviceId", identityStore.getOrCreateDeviceId());
            body.put("label", identityStore.getDeviceLabel());
        } catch (JSONException error) {
            throw new SyncException("Unable to prepare device registration", error);
        }
        return requestJson("POST", "/v1/devices", body, false);
    }

    @NonNull public JSONObject pushEvents(@NonNull JSONArray events) throws SyncException {
        if (events == null || events.length() < 1 || events.length() > 100) {
            throw new SyncException("A sync batch must contain between 1 and 100 events");
        }
        JSONObject body = new JSONObject();
        try { body.put("events", events); }
        catch (JSONException error) { throw new SyncException("Unable to encode sync events", error); }
        return requestJson("POST", "/v1/sync/push", body, true);
    }

    @NonNull public JSONObject pullEvents(long afterSequence, int limit) throws SyncException {
        if (afterSequence < 0 || limit < 1 || limit > 500) {
            throw new SyncException("Invalid sync cursor or page size");
        }
        return requestJson("GET", "/v1/sync/pull?after=" + afterSequence + "&limit=" + limit,
                null, true);
    }

    @NonNull public JSONObject connectGoogleDrive(@NonNull String serverAuthCode) throws SyncException {
        if (serverAuthCode == null || serverAuthCode.trim().length() < 20
                || serverAuthCode.length() > 4096
                || serverAuthCode.indexOf('\r') >= 0 || serverAuthCode.indexOf('\n') >= 0) {
            throw new SyncException("Google Drive authorization code is missing or invalid");
        }
        JSONObject body = new JSONObject();
        try { body.put("serverAuthCode", serverAuthCode); }
        catch (JSONException error) { throw new SyncException("Unable to prepare Google Drive connection", error); }
        return requestJson("POST", "/v1/drive/connect", body, true);
    }

    @NonNull public JSONObject listConflicts() throws SyncException {
        return requestJson("GET", "/v1/conflicts", null, true);
    }

    public void revokeDevice(@NonNull String deviceId) throws SyncException {
        if (!isUuid(deviceId)) throw new SyncException("Invalid device ID");
        requestJson("DELETE", "/v1/devices/" + deviceId, null, true);
    }

    @NonNull public JSONObject listBackups() throws SyncException {
        return requestJson("GET", "/v1/backups", null, true);
    }

    @NonNull public JSONObject uploadEncryptedBackup(byte[] encryptedBackup,
            long throughSequence, @NonNull String sha256) throws SyncException {
        if (encryptedBackup == null || encryptedBackup.length == 0
                || encryptedBackup.length > MAX_BACKUP_BYTES || throughSequence < 0
                || sha256 == null || !sha256.matches("(?i)[0-9a-f]{64}")) {
            throw new SyncException("Encrypted backup metadata or size is invalid");
        }
        if (!sha256.equalsIgnoreCase(sha256Hex(encryptedBackup))) {
            throw new SyncException("Encrypted backup checksum does not match its content");
        }
        return requestBytes("POST", "/v1/backups", encryptedBackup, true,
                throughSequence, sha256);
    }

    @NonNull public byte[] downloadEncryptedBackup(@NonNull String snapshotId)
            throws SyncException {
        if (!isUuid(snapshotId)) throw new SyncException("Invalid backup snapshot ID");
        return requestBinary("GET", "/v1/backups/" + snapshotId, true);
    }

    private JSONObject requestJson(String method, String path, JSONObject body,
            boolean deviceScoped) throws SyncException {
        byte[] bytes = null;
        if (body != null) {
            bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_RESPONSE_BYTES) {
                throw new SyncException("Sync request exceeds the 2 MiB payload limit");
            }
        }
        byte[] response = execute(method, path, bytes, "application/json; charset=utf-8",
                deviceScoped, null, null, MAX_JSON_RESPONSE_BYTES, false);
        try { return new JSONObject(new String(response, StandardCharsets.UTF_8)); }
        catch (JSONException error) { throw new SyncException("Sync server returned invalid JSON", error); }
    }

    private JSONObject requestBytes(String method, String path, byte[] body,
            boolean deviceScoped, long throughSequence, String sha256) throws SyncException {
        byte[] response = execute(method, path, body, "application/octet-stream",
                deviceScoped, throughSequence, sha256, MAX_JSON_RESPONSE_BYTES, false);
        try { return new JSONObject(new String(response, StandardCharsets.UTF_8)); }
        catch (JSONException error) { throw new SyncException("Sync server returned invalid JSON", error); }
    }

    private byte[] requestBinary(String method, String path, boolean deviceScoped)
            throws SyncException {
        return execute(method, path, null, null, deviceScoped, null, null,
                MAX_BACKUP_BYTES, true);
    }

    private byte[] execute(String method, String path, byte[] body, String contentType,
            boolean deviceScoped, Long throughSequence, String backupSha, int responseLimit,
            boolean binaryResponse) throws SyncException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", binaryResponse
                    ? "application/octet-stream" : "application/json");
            String idToken = tokenProvider.getGoogleIdToken();
            if (idToken == null || idToken.trim().isEmpty() || idToken.length() > 8192
                    || idToken.indexOf('\r') >= 0 || idToken.indexOf('\n') >= 0) {
                throw new SyncException("Google sign-in token is missing or invalid");
            }
            connection.setRequestProperty("Authorization", "Bearer " + idToken);
            if (deviceScoped) {
                connection.setRequestProperty("X-Device-ID", identityStore.getOrCreateDeviceId());
            }
            if (throughSequence != null) {
                connection.setRequestProperty("X-Through-Seq", String.valueOf(throughSequence));
                connection.setRequestProperty("X-Backup-SHA256", backupSha);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] response = stream == null ? new byte[0] : readBounded(stream, responseLimit);
            if (status < 200 || status >= 300) {
                String message = "Sync request failed (HTTP " + status + ")";
                try {
                    JSONObject error = new JSONObject(new String(response, StandardCharsets.UTF_8));
                    if (error.has("error")) message = error.optString("error", message);
                } catch (JSONException ignored) { }
                throw new SyncException(message, status);
            }
            if (binaryResponse) {
                String contentType = connection.getContentType();
                String claimedSha = connection.getHeaderField("X-Backup-SHA256");
                String throughSequence = connection.getHeaderField("X-Through-Seq");
                if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                        .startsWith("application/octet-stream")
                        || claimedSha == null || !claimedSha.matches("(?i)[0-9a-f]{64}")
                        || throughSequence == null || !throughSequence.matches("[0-9]{1,19}")
                        || !claimedSha.equalsIgnoreCase(sha256Hex(response))) {
                    throw new SyncException("Downloaded encrypted backup failed metadata or SHA-256 verification");
                }
            }
            return response;
        } catch (SyncException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new SyncException("Unable to reach the sync server", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String sha256Hex(byte[] value) throws SyncException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new SyncException("SHA-256 is unavailable on this device", error);
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Sync response exceeds size limit");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equalsIgnoreCase(value); }
        catch (IllegalArgumentException ignored) { return false; }
    }
}
