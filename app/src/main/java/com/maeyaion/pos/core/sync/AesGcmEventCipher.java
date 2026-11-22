package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM payload protection primitive.
 *
 * This class deliberately accepts an already provisioned 32-byte store key.
 * It does not create, persist, wrap, recover, rotate, or revoke that key.
 * Callers must obtain keys from the not-yet-completed StoreKeyManager and must
 * never fall back to plaintext when key provisioning is unavailable.
 */
public final class AesGcmEventCipher {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final int TAG_BYTES = TAG_BITS / 8;
    public static final int MAX_PLAINTEXT_BYTES = 256 * 1024;
    public static final int MAX_CIPHERTEXT_BYTES = MAX_PLAINTEXT_BYTES + TAG_BYTES;

    private final SecureRandom secureRandom;

    public AesGcmEventCipher() {
        this(new SecureRandom());
    }

    AesGcmEventCipher(@NonNull SecureRandom secureRandom) {
        if (secureRandom == null) throw new IllegalArgumentException("SecureRandom is required");
        this.secureRandom = secureRandom;
    }

    @NonNull
    public EncryptedSyncPayload encrypt(@NonNull byte[] storeKey, long keyEpoch,
            @NonNull byte[] plaintext, @NonNull byte[] associatedData) throws SyncException {
        validateInputs(storeKey, keyEpoch, plaintext, associatedData);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(storeKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            byte[] encrypted = cipher.doFinal(plaintext);
            return new EncryptedSyncPayload(keyEpoch, nonce, encrypted);
        } catch (GeneralSecurityException error) {
            throw new SyncException("Unable to encrypt sync event", error);
        }
    }

    @NonNull
    public byte[] decrypt(@NonNull byte[] storeKey,
            @NonNull EncryptedSyncPayload payload, @NonNull byte[] associatedData)
            throws SyncException {
        if (storeKey == null || storeKey.length != KEY_BYTES) {
            throw new SyncException("Store encryption key must contain exactly 256 bits");
        }
        if (payload == null || associatedData == null || associatedData.length == 0
                || associatedData.length > 4096) {
            throw new SyncException("Encrypted payload or associated data is invalid");
        }
        if (payload.encryptionVersion != EncryptedSyncPayload.ENCRYPTION_VERSION
                || payload.aadVersion != SyncAssociatedData.VERSION
                || payload.keyEpoch < 1
                || !EncryptedSyncPayload.ALGORITHM.equals(payload.algorithm)) {
            throw new SyncException("Unsupported encrypted payload protocol");
        }
        try {
            byte[] nonce = payload.nonceBytes();
            byte[] ciphertext = payload.ciphertextBytes();
            if (nonce.length != NONCE_BYTES || ciphertext.length < TAG_BYTES
                    || ciphertext.length > MAX_CIPHERTEXT_BYTES) {
                throw new SyncException("Encrypted payload length is invalid");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(storeKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException error) {
            throw new SyncException("Encrypted sync payload authentication failed", error);
        } catch (GeneralSecurityException error) {
            throw new SyncException("Unable to decrypt sync event", error);
        }
    }

    private static void validateInputs(byte[] key, long keyEpoch, byte[] plaintext,
            byte[] associatedData) throws SyncException {
        if (key == null || key.length != KEY_BYTES) {
            throw new SyncException("Store encryption key must contain exactly 256 bits");
        }
        if (keyEpoch < 1) throw new SyncException("Invalid store key epoch");
        if (plaintext == null || plaintext.length == 0
                || plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new SyncException("Sync plaintext size is invalid");
        }
        if (associatedData == null || associatedData.length == 0
                || associatedData.length > 4096) {
            throw new SyncException("Sync associated data is invalid");
        }
    }
}
