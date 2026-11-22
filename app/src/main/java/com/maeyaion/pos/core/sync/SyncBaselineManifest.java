package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;

/** Signed-by-content metadata for a full encrypted local database baseline. */
public final class SyncBaselineManifest {
    public static final int VERSION = 1;
    public static final String FORMAT = "MAEYAI_SYNC_BASELINE";

    public final String storeId;
    public final long throughSequence;
    public final int databaseSchemaVersion;
    public final String snapshotSha256;
    public final String encryptedFileSha256;
    public final String createdAtUtc;

    private SyncBaselineManifest(String storeId, long throughSequence, int schemaVersion,
            String snapshotSha256, String encryptedFileSha256, String createdAtUtc) {
        this.storeId = storeId;
        this.throughSequence = throughSequence;
        this.databaseSchemaVersion = schemaVersion;
        this.snapshotSha256 = snapshotSha256;
        this.encryptedFileSha256 = encryptedFileSha256;
        this.createdAtUtc = createdAtUtc;
    }

    @NonNull
    public static SyncBaselineManifest create(String storeId, long sequence, int schemaVersion,
            String snapshotSha, String encryptedSha, String createdAtUtc) throws SyncException {
        validate(storeId, sequence, schemaVersion, snapshotSha, encryptedSha, createdAtUtc);
        return new SyncBaselineManifest(storeId, sequence, schemaVersion,
                snapshotSha.toLowerCase(java.util.Locale.ROOT),
                encryptedSha.toLowerCase(java.util.Locale.ROOT), createdAtUtc);
    }

    @NonNull
    public JSONObject toJson() throws SyncException {
        try {
            JSONObject json = new JSONObject();
            json.put("format", FORMAT);
            json.put("manifestVersion", VERSION);
            json.put("storeId", storeId);
            json.put("throughSequence", String.valueOf(throughSequence));
            json.put("databaseSchemaVersion", databaseSchemaVersion);
            json.put("snapshotSha256", snapshotSha256);
            json.put("encryptedFileSha256", encryptedFileSha256);
            json.put("createdAtUtc", createdAtUtc);
            return json;
        } catch (JSONException error) {
            throw new SyncException("Unable to serialize baseline manifest", error);
        }
    }

    @NonNull
    public static SyncBaselineManifest fromJson(@NonNull JSONObject json) throws SyncException {
        if (json == null || json.optInt("manifestVersion", -1) != VERSION
                || !FORMAT.equals(json.optString("format", ""))) {
            throw new SyncException("Unsupported baseline manifest format");
        }
        String storeId = json.optString("storeId", "");
        String sequence = json.optString("throughSequence", "");
        String snapshotSha = json.optString("snapshotSha256", "");
        String encryptedSha = json.optString("encryptedFileSha256", "");
        String createdAt = json.optString("createdAtUtc", "");
        int schema = json.optInt("databaseSchemaVersion", -1);
        long through;
        try { through = new java.math.BigDecimal(sequence).longValueExact(); }
        catch (RuntimeException error) { throw new SyncException("Baseline sequence is invalid", error); }
        return create(storeId, through, schema, snapshotSha, encryptedSha, createdAt);
    }

    private static void validate(String storeId, long sequence, int schemaVersion,
            String snapshotSha, String encryptedSha, String createdAtUtc) throws SyncException {
        if (storeId == null || !storeId.matches("[A-Za-z0-9_-]{1,128}")
                || sequence < 0 || schemaVersion < 1
                || snapshotSha == null || !snapshotSha.matches("(?i)[a-f0-9]{64}")
                || encryptedSha == null || !encryptedSha.matches("(?i)[a-f0-9]{64}")) {
            throw new SyncException("Baseline manifest contains invalid identity, version or digest");
        }
        try { Instant.parse(createdAtUtc); }
        catch (RuntimeException error) { throw new SyncException("Baseline manifest timestamp is invalid", error); }
    }
}
