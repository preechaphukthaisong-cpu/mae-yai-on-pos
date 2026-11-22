package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public final class AesGcmEventCipherTest {
    private final AesGcmEventCipher cipher = new AesGcmEventCipher();

    @Test public void encryptDecryptRoundTripAndWireSerialization() throws Exception {
        byte[] key = key(7);
        byte[] clear = "{\"schemaVersion\":1,\"data\":{\"sku\":\"SKU-1\"}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] aad = aad("store-a", "event-a");
        EncryptedSyncPayload encrypted = cipher.encrypt(key, 3, clear, aad);

        assertEquals(EncryptedSyncPayload.ENCRYPTION_VERSION, encrypted.encryptionVersion);
        assertEquals(3L, encrypted.keyEpoch);
        assertEquals(EncryptedSyncPayload.ALGORITHM, encrypted.algorithm);
        assertEquals(AesGcmEventCipher.NONCE_BYTES,
                java.util.Base64.getUrlDecoder().decode(encrypted.nonce).length);

        EncryptedSyncPayload decoded = EncryptedSyncPayload.fromJsonObject(
                new JSONObject(encrypted.toJsonObject().toString()));
        assertArrayEquals(clear, cipher.decrypt(key, decoded, aad));
        assertFalse(Arrays.equals(clear, decoded.ciphertextBytes()));
    }

    @Test public void everyEncryptionUsesFreshNonce() throws Exception {
        byte[] key = key(9);
        byte[] clear = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] aad = aad("store-a", "event-a");
        EncryptedSyncPayload first = cipher.encrypt(key, 1, clear, aad);
        EncryptedSyncPayload second = cipher.encrypt(key, 1, clear, aad);
        assertNotEquals(first.nonce, second.nonce);
        assertNotEquals(first.ciphertext, second.ciphertext);
    }

    @Test public void modifiedAssociatedDataIsRejected() throws Exception {
        byte[] key = key(1);
        EncryptedSyncPayload encrypted = cipher.encrypt(key, 1,
                "sensitive".getBytes(StandardCharsets.UTF_8), aad("store-a", "event-a"));
        try {
            cipher.decrypt(key, encrypted, aad("store-b", "event-a"));
            fail("Store identity is authenticated");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
    }

    @Test public void modifiedCiphertextAndWrongKeyAreRejected() throws Exception {
        byte[] key = key(2);
        byte[] aad = aad("store-a", "event-a");
        EncryptedSyncPayload encrypted = cipher.encrypt(key, 1,
                "sensitive".getBytes(StandardCharsets.UTF_8), aad);
        byte[] changed = encrypted.ciphertextBytes();
        changed[0] ^= 1;
        JSONObject tamperedJson = encrypted.toJsonObject();
        tamperedJson.put("ciphertext",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(changed));
        EncryptedSyncPayload tampered = EncryptedSyncPayload.fromJsonObject(tamperedJson);
        try {
            cipher.decrypt(key, tampered, aad);
            fail("Modified ciphertext must be rejected");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
        try {
            cipher.decrypt(key(3), encrypted, aad);
            fail("Wrong key must be rejected");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
    }

    @Test public void invalidKeyAndProtocolMetadataFailClosed() throws Exception {
        try {
            cipher.encrypt(new byte[16], 1, new byte[]{1}, new byte[]{2});
            fail("128-bit keys are not accepted");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("256 bits"));
        }
        JSONObject malformed = new JSONObject();
        malformed.put("encryptionVersion", 99);
        malformed.put("keyEpoch", 1);
        malformed.put("algorithm", "A256GCM");
        malformed.put("nonce", "AAAAAAAAAAAAAAAA");
        malformed.put("ciphertext", "AAAAAAAAAAAAAAAAAAAAAA");
        malformed.put("aadVersion", 1);
        try {
            EncryptedSyncPayload.fromJsonObject(malformed);
            fail("Unknown protocol version must be rejected");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("Unsupported"));
        }
    }

    @Test public void associatedDataBindsEveryRoutingField() throws Exception {
        byte[] original = aad("store-a", "event-a");
        assertArrayEquals(original, aad("store-a", "event-a"));
        assertFalse(Arrays.equals(original, aad("store-b", "event-a")));
        assertFalse(Arrays.equals(original, aad("store-a", "event-b")));
    }

    private static byte[] key(int seed) {
        byte[] result = new byte[AesGcmEventCipher.KEY_BYTES];
        Arrays.fill(result, (byte) seed);
        return result;
    }

    private static byte[] aad(String storeId, String eventId) throws Exception {
        return SyncAssociatedData.encode(storeId, eventId, "upsert", "product",
                "SKU-1", 0, EncryptedSyncPayload.ENCRYPTION_VERSION, 1);
    }
}
