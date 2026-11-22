package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;

/** Opaque versioned AES-256-GCM payload. Ciphertext includes the 128-bit GCM tag. */
public final class EncryptedSyncPayload {
    public static final int ENCRYPTION_VERSION = 1;
    public static final String ALGORITHM = "A256GCM";

    @NonNull public final int encryptionVersion;
    @NonNull public final long keyEpoch;
    @NonNull public final String algorithm;
    @NonNull public final String nonce;
    @NonNull public final String ciphertext;
    @NonNull public final int aadVersion;

    EncryptedSyncPayload(long keyEpoch, byte[] nonce, byte[] ciphertext) {
        this.encryptionVersion = ENCRYPTION_VERSION;
        this.keyEpoch = keyEpoch;
        this.algorithm = ALGORITHM;
        this.nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        this.ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        this.aadVersion = SyncAssociatedData.VERSION;
    }

    private EncryptedSyncPayload(int version, long keyEpoch, String algorithm,
            String nonce, String ciphertext, int aadVersion) {
        this.encryptionVersion = version;
        this.keyEpoch = keyEpoch;
        this.algorithm = algorithm;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
        this.aadVersion = aadVersion;
    }

    @NonNull
    public JSONObject toJsonObject() throws SyncException {
        try {
            JSONObject json = new JSONObject();
            json.put("encryptionVersion", encryptionVersion);
            json.put("keyEpoch", keyEpoch);
            json.put("algorithm", algorithm);
            json.put("nonce", nonce);
            json.put("ciphertext", ciphertext);
            json.put("aadVersion", aadVersion);
            return json;
        } catch (JSONException error) {
            throw new SyncException("Unable to serialize encrypted sync payload", error);
        }
    }

    @NonNull
    public static EncryptedSyncPayload fromJsonObject(@NonNull JSONObject json)
            throws SyncException {
        if (json == null) throw new SyncException("Encrypted payload is required");
        int version = json.optInt("encryptionVersion", -1);
        long epoch = json.optLong("keyEpoch", -1);
        String algorithm = json.optString("algorithm", "");
        String nonce = json.optString("nonce", "");
        String ciphertext = json.optString("ciphertext", "");
        int aadVersion = json.optInt("aadVersion", -1);
        if (version != ENCRYPTION_VERSION || epoch < 1 || !ALGORITHM.equals(algorithm)
                || aadVersion != SyncAssociatedData.VERSION) {
            throw new SyncException("Unsupported encrypted payload metadata");
        }
        byte[] nonceBytes = decode(nonce, "nonce");
        byte[] cipherBytes = decode(ciphertext, "ciphertext");
        if (nonceBytes.length != AesGcmEventCipher.NONCE_BYTES
                || cipherBytes.length < AesGcmEventCipher.TAG_BYTES
                || cipherBytes.length > AesGcmEventCipher.MAX_CIPHERTEXT_BYTES) {
            throw new SyncException("Encrypted payload length is invalid");
        }
        return new EncryptedSyncPayload(version, epoch, algorithm, nonce, ciphertext, aadVersion);
    }

    byte[] nonceBytes() throws SyncException { return decode(nonce, "nonce"); }
    byte[] ciphertextBytes() throws SyncException { return decode(ciphertext, "ciphertext"); }

    private static byte[] decode(String value, String label) throws SyncException {
        if (value == null || value.isEmpty() || value.length() > 400_000
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new SyncException("Invalid encrypted payload " + label);
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new SyncException("Invalid encrypted payload " + label, error);
        }
    }
}
