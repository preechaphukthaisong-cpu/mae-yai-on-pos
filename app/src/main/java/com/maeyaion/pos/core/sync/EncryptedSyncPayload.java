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
        if (json.length() != 6) {
            throw new SyncException("Encrypted payload contains missing or unexpected fields");
        }
        try {
            Object rawVersion = json.get("encryptionVersion");
            Object rawEpoch = json.get("keyEpoch");
            Object rawAadVersion = json.get("aadVersion");
            Object rawAlgorithm = json.get("algorithm");
            Object rawNonce = json.get("nonce");
            Object rawCiphertext = json.get("ciphertext");
            if (!(rawVersion instanceof Number) || !(rawEpoch instanceof Number)
                    || !(rawAadVersion instanceof Number) || !(rawAlgorithm instanceof String)
                    || !(rawNonce instanceof String) || !(rawCiphertext instanceof String)) {
                throw new SyncException("Encrypted payload field types are invalid");
            }
            int version = new java.math.BigDecimal(rawVersion.toString()).intValueExact();
            long epoch = new java.math.BigDecimal(rawEpoch.toString()).longValueExact();
            int aadVersion = new java.math.BigDecimal(rawAadVersion.toString()).intValueExact();
            String algorithm = (String) rawAlgorithm;
            String nonce = (String) rawNonce;
            String ciphertext = (String) rawCiphertext;
            if (version != ENCRYPTION_VERSION || epoch < 1 || !ALGORITHM.equals(algorithm)
                    || aadVersion != SyncAssociatedData.VERSION) {
                throw new SyncException("Unsupported encrypted payload metadata");
            }
            byte[] nonceBytes = decode(nonce, "nonce");
            byte[] cipherBytes = decode(ciphertext, "ciphertext");
            if (nonceBytes.length != AesGcmEventCipher.NONCE_BYTES
                    || cipherBytes.length < AesGcmEventCipher.TAG_BYTES
                    || cipherBytes.length > AesGcmEventCipher.MAX_CIPHERTEXT_BYTES) {
                java.util.Arrays.fill(nonceBytes, (byte) 0);
                java.util.Arrays.fill(cipherBytes, (byte) 0);
                throw new SyncException("Encrypted payload length is invalid");
            }
            java.util.Arrays.fill(nonceBytes, (byte) 0);
            java.util.Arrays.fill(cipherBytes, (byte) 0);
            return new EncryptedSyncPayload(version, epoch, algorithm, nonce, ciphertext, aadVersion);
        } catch (JSONException | ArithmeticException | NumberFormatException error) {
            throw new SyncException("Encrypted payload metadata is malformed", error);
        }
    }

    byte[] nonceBytes() throws SyncException { return decode(nonce, "nonce"); }
    byte[] ciphertextBytes() throws SyncException { return decode(ciphertext, "ciphertext"); }

    private static byte[] decode(String value, String label) throws SyncException {
        if (value == null || value.isEmpty() || value.length() > 400_000
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw new SyncException("Invalid encrypted payload " + label);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
                java.util.Arrays.fill(decoded, (byte) 0);
                throw new SyncException("Non-canonical encrypted payload " + label);
            }
            return decoded;
        } catch (IllegalArgumentException error) {
            throw new SyncException("Invalid encrypted payload " + label, error);
        }
    }
}
