package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Wraps store keys with a high-entropy user-held recovery key using HKDF-SHA-256 and AES-GCM. */
public final class StoreKeyRecoveryWrapper {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private StoreKeyRecoveryWrapper() {}

    @NonNull
    public static String wrap(@NonNull String storeId, long epoch,
            @NonNull String recoveryKey, @NonNull byte[] storeKey) throws SyncException {
        validate(storeId, epoch, recoveryKey, storeKey);
        byte[] rawRecovery = decodeRecoveryKey(recoveryKey);
        byte[] wrappingKey = derive(rawRecovery, storeId, epoch);
        byte[] nonce = new byte[NONCE_BYTES];
        new java.security.SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(storeId, epoch));
            byte[] encrypted = cipher.doFinal(storeKey);
            ByteBuffer packed = ByteBuffer.allocate(nonce.length + encrypted.length);
            packed.put(nonce).put(encrypted);
            String result = Base64.getUrlEncoder().withoutPadding().encodeToString(packed.array());
            Arrays.fill(encrypted, (byte) 0);
            Arrays.fill(packed.array(), (byte) 0);
            return result;
        } catch (Exception error) {
            throw new SyncException("Unable to wrap store key with recovery key", error);
        } finally {
            Arrays.fill(rawRecovery, (byte) 0);
            Arrays.fill(wrappingKey, (byte) 0);
        }
    }

    @NonNull
    public static byte[] unwrap(@NonNull String storeId, long epoch,
            @NonNull String recoveryKey, @NonNull String wrappedKey) throws SyncException {
        if (wrappedKey == null || wrappedKey.length() > 1024
                || !wrappedKey.matches("[A-Za-z0-9_-]+")) {
            throw new SyncException("Recovery key wrapper is malformed");
        }
        validate(storeId, epoch, recoveryKey, new byte[KEY_BYTES]);
        byte[] packed;
        try { packed = Base64.getUrlDecoder().decode(wrappedKey); }
        catch (IllegalArgumentException error) { throw new SyncException("Recovery key wrapper is malformed", error); }
        if (packed.length != NONCE_BYTES + KEY_BYTES + 16) {
            Arrays.fill(packed, (byte) 0);
            throw new SyncException("Recovery key wrapper length is invalid");
        }
        byte[] rawRecovery = decodeRecoveryKey(recoveryKey);
        byte[] wrappingKey = derive(rawRecovery, storeId, epoch);
        try {
            byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(storeId, epoch));
            byte[] key = cipher.doFinal(ciphertext);
            if (key.length != KEY_BYTES) {
                Arrays.fill(key, (byte) 0);
                throw new SyncException("Recovered store key length is invalid");
            }
            return key;
        } catch (javax.crypto.AEADBadTagException error) {
            throw new SyncException("Recovery key authentication failed", error);
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to recover store key", error);
        } finally {
            Arrays.fill(rawRecovery, (byte) 0);
            Arrays.fill(wrappingKey, (byte) 0);
            Arrays.fill(packed, (byte) 0);
        }
    }

    private static byte[] derive(byte[] inputKey, String storeId, long epoch) throws SyncException {
        try {
            byte[] salt = MessageDigest.getInstance("SHA-256")
                    .digest(("MAEYAI-STORE-RECOVERY-SALT-v1:" + storeId)
                            .getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(inputKey);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] info = ("MAEYAI-STORE-RECOVERY-WRAP-v1:" + epoch)
                    .getBytes(StandardCharsets.UTF_8);
            mac.update(info);
            mac.update((byte) 1);
            byte[] result = mac.doFinal();
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(prk, (byte) 0);
            return result;
        } catch (Exception error) {
            throw new SyncException("Unable to derive recovery wrapping key", error);
        }
    }

    private static byte[] decodeRecoveryKey(String value) throws SyncException {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (bytes.length != KEY_BYTES || !Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes).equals(value)) {
                Arrays.fill(bytes, (byte) 0);
                throw new SyncException("Recovery key must be a canonical 256-bit base64url secret");
            }
            return bytes;
        } catch (IllegalArgumentException error) {
            throw new SyncException("Recovery key encoding is invalid", error);
        }
    }

    private static byte[] aad(String storeId, long epoch) {
        return ("MAEYAI-STORE-RECOVERY-WRAPPER-v1\u0000" + storeId + "\u0000" + epoch)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void validate(String storeId, long epoch, String recoveryKey, byte[] key)
            throws SyncException {
        if (storeId == null || !storeId.matches("[A-Za-z0-9_-]{1,128}")
                || epoch < 1 || recoveryKey == null || key == null || key.length != KEY_BYTES) {
            throw new SyncException("Recovery wrapper inputs are invalid");
        }
    }
}
