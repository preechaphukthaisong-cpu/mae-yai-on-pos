package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Encrypts and authenticates complete sync event payloads before they cross the network. */
public final class EncryptedSyncEventCodec {
    private final StoreKeyManager keys;
    private final AesGcmEventCipher cipher;

    public EncryptedSyncEventCodec(@NonNull StoreKeyManager keys) {
        this(keys, new AesGcmEventCipher());
    }

    EncryptedSyncEventCodec(@NonNull StoreKeyManager keys, @NonNull AesGcmEventCipher cipher) {
        if (keys == null || cipher == null) throw new IllegalArgumentException("Key manager and cipher are required");
        this.keys = keys;
        this.cipher = cipher;
    }

    @NonNull
    public JSONObject encrypt(@NonNull String storeId, @NonNull String eventId,
            @NonNull String operation, @NonNull String entityType, @NonNull String entityId,
            long baseVersion, @NonNull JSONObject plaintextPayload) throws SyncException {
        if (plaintextPayload == null) throw new SyncException("Event payload is required");
        long epoch = keys.getCurrentEpoch(storeId);
        byte[] key = keys.getStoreKey(storeId, epoch);
        try {
            byte[] plaintext = SyncEventEnvelope.canonicalJson(plaintextPayload)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] aad = SyncAssociatedData.encode(storeId, eventId, operation, entityType,
                    entityId, baseVersion, EncryptedSyncPayload.ENCRYPTION_VERSION, epoch);
            EncryptedSyncPayload encrypted = cipher.encrypt(key, epoch, plaintext, aad);
            JSONObject wirePayload = encrypted.toJsonObject();
            JSONObject wireEvent = new JSONObject();
            wireEvent.put("eventId", eventId);
            wireEvent.put("operation", operation);
            wireEvent.put("entityType", entityType);
            wireEvent.put("entityId", entityId);
            wireEvent.put("baseVersion", baseVersion);
            wireEvent.put("payload", wirePayload);
            wireEvent.put("payloadSha256", sha256(SyncEventEnvelope.canonicalJson(wirePayload)
                    .getBytes(StandardCharsets.UTF_8)));
            return wireEvent;
        } catch (JSONException error) {
            throw new SyncException("Unable to serialize encrypted sync event", error);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    @NonNull
    public JSONObject decrypt(@NonNull String storeId, @NonNull String eventId,
            @NonNull String operation, @NonNull String entityType, @NonNull String entityId,
            long baseVersion, @NonNull JSONObject wirePayload) throws SyncException {
        if (wirePayload == null) throw new SyncException("Encrypted event payload is required");
        EncryptedSyncPayload encrypted = EncryptedSyncPayload.fromJsonObject(wirePayload);
        byte[] key = keys.getStoreKey(storeId, encrypted.keyEpoch);
        try {
            byte[] aad = SyncAssociatedData.encode(storeId, eventId, operation, entityType,
                    entityId, baseVersion, encrypted.encryptionVersion, encrypted.keyEpoch);
            byte[] plaintext = cipher.decrypt(key, encrypted, aad);
            try {
                String json = new String(plaintext, StandardCharsets.UTF_8);
                JSONObject result = new JSONObject(json);
                if (!SyncEventEnvelope.canonicalJson(result).equals(json)) {
                    throw new SyncException("Decrypted event payload is not canonical JSON");
                }
                return result;
            } catch (JSONException error) {
                throw new SyncException("Decrypted event payload is not a JSON object", error);
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    private static String sha256(byte[] bytes) throws SyncException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new SyncException("SHA-256 is unavailable", error);
        }
    }
}
