package com.maeyaion.pos.core.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Local store-key lifecycle backed by a non-exportable Android Keystore wrapping key.
 *
 * Store keys are random AES-256 values encrypted at rest in app-private preferences.
 * Historical epochs are retained so queued and historical events remain decryptable.
 * This class is device-local only: enrollment, recovery wrapping and remote revocation
 * require a separate authenticated protocol and must not be inferred from local storage.
 */
public final class StoreKeyManager {
    private static final String PREFS = "maeyai_store_keys_v1";
    private static final String KEY_ALIAS_PREFIX = "maeyai_store_wrap_";
    private static final String CURRENT_PREFIX = "current_epoch_";
    private static final String ENTRY_PREFIX = "store_key_";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Context context;
    private final SharedPreferences preferences;
    private final SecureRandom random = new SecureRandom();

    public StoreKeyManager(@NonNull Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Creates epoch 1 only when this installation has no key for the store. */
    public synchronized long initializeStore(@NonNull String storeId) throws SyncException {
        validateStoreId(storeId);
        long current = currentEpoch(storeId);
        if (current > 0) return current;
        ensureWrappingKey(storeId);
        return persistNewEpoch(storeId, 1L);
    }

    /** Returns a defensive copy of the requested epoch's 256-bit key. */
    @NonNull
    public synchronized byte[] getStoreKey(@NonNull String storeId, long epoch)
            throws SyncException {
        validateStoreId(storeId);
        if (epoch < 1 || epoch > currentEpoch(storeId)) {
            throw new SyncException("Unknown store-key epoch");
        }
        String encoded = preferences.getString(entryKey(storeId, epoch), null);
        if (encoded == null) throw new SyncException("Store-key epoch is unavailable on this device");
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length != NONCE_BYTES + AesGcmEventCipher.KEY_BYTES + 16) {
                throw new SyncException("Stored store-key record has an invalid length");
            }
            byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getWrappingKey(storeId),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(storeId, epoch));
            byte[] key = cipher.doFinal(ciphertext);
            if (key.length != AesGcmEventCipher.KEY_BYTES) {
                Arrays.fill(key, (byte) 0);
                throw new SyncException("Unwrapped store key has an invalid length");
            }
            return key;
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to unlock local store key", error);
        }
    }

    /** Creates a fresh epoch; prior epochs remain available for decrypting old events. */
    public synchronized long rotateStoreKey(@NonNull String storeId) throws SyncException {
        validateStoreId(storeId);
        long current = currentEpoch(storeId);
        if (current < 1) throw new SyncException("Store keys must be initialized before rotation");
        if (current == Long.MAX_VALUE) throw new SyncException("Store-key epoch exhausted");
        // Do not silently replace a lost Keystore wrapper: that would strand all prior epochs.
        byte[] previousKey = getStoreKey(storeId, current);
        Arrays.fill(previousKey, (byte) 0);
        ensureWrappingKey(storeId);
        return persistNewEpoch(storeId, current + 1);
    }

    public synchronized long getCurrentEpoch(@NonNull String storeId) throws SyncException {
        validateStoreId(storeId);
        long epoch = currentEpoch(storeId);
        if (epoch < 1) throw new SyncException("Store encryption key is not initialized");
        return epoch;
    }

    private long persistNewEpoch(String storeId, long epoch) throws SyncException {
        byte[] storeKey = new byte[AesGcmEventCipher.KEY_BYTES];
        random.nextBytes(storeKey);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getWrappingKey(storeId),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(storeId, epoch));
            byte[] ciphertext = cipher.doFinal(storeKey);
            ByteBuffer packed = ByteBuffer.allocate(nonce.length + ciphertext.length);
            packed.put(nonce).put(ciphertext);
            String encoded = Base64.getEncoder().encodeToString(packed.array());
            boolean saved = preferences.edit()
                    .putString(entryKey(storeId, epoch), encoded)
                    .putLong(currentKey(storeId), epoch)
                    .commit();
            if (!saved) throw new SyncException("Unable to persist store encryption key");
            return epoch;
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to create store encryption key", error);
        } finally {
            Arrays.fill(storeKey, (byte) 0);
        }
    }

    private void ensureWrappingKey(String storeId) throws SyncException {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            String alias = alias(storeId);
            if (keyStore.containsAlias(alias)) return;
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        } catch (Exception error) {
            throw new SyncException("Unable to provision Android Keystore wrapping key", error);
        }
    }

    private SecretKey getWrappingKey(String storeId) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key key = keyStore.getKey(alias(storeId), null);
        if (!(key instanceof SecretKey)) throw new SyncException("Local wrapping key is missing");
        return (SecretKey) key;
    }

    private long currentEpoch(String storeId) {
        return preferences.getLong(currentKey(storeId), 0L);
    }

    private static byte[] aad(String storeId, long epoch) {
        return ("maeyai-store-key-v1\u0000" + storeId + "\u0000" + epoch)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String alias(String storeId) throws SyncException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(storeId.getBytes(StandardCharsets.UTF_8));
            return KEY_ALIAS_PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest).substring(0, 32);
        } catch (Exception error) {
            throw new SyncException("Unable to derive Android Keystore alias", error);
        }
    }

    private static String currentKey(String storeId) { return CURRENT_PREFIX + storeId; }
    private static String entryKey(String storeId, long epoch) {
        return ENTRY_PREFIX + storeId + "_" + epoch;
    }

    private static void validateStoreId(String storeId) throws SyncException {
        if (storeId == null || storeId.trim().isEmpty() || storeId.length() > 128
                || !storeId.matches("[A-Za-z0-9_-]+")) {
            throw new SyncException("Invalid store identifier for key management");
        }
    }
}
