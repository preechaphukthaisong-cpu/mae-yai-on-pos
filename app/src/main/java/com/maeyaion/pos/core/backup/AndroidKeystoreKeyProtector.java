package com.maeyaion.pos.core.backup;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Protects a local copy of the backup data key with a non-exportable Android Keystore key. */
public final class AndroidKeystoreKeyProtector {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "maeyai-on-pos.backup-key.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    public byte[] protect(byte[] dataKey) throws GeneralSecurityException {
        if (dataKey == null || dataKey.length != 32) {
            throw new GeneralSecurityException("Expected a 256-bit backup data key");
        }
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(aad());
        byte[] nonce = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(dataKey);
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }

    public byte[] unprotect(byte[] protectedKey) throws GeneralSecurityException {
        if (protectedKey == null || protectedKey.length != NONCE_BYTES + 32 + TAG_BITS / 8) {
            throw new GeneralSecurityException("Invalid locally protected backup key");
        }
        ByteBuffer input = ByteBuffer.wrap(protectedKey);
        byte[] nonce = new byte[NONCE_BYTES];
        input.get(nonce);
        byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad());
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        SecretKey existing = getExistingKeyOrNull();
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SecretKey getExistingKey() throws GeneralSecurityException {
        SecretKey key = getExistingKeyOrNull();
        if (key == null) throw new GeneralSecurityException("Local backup key is unavailable; use Recovery Password or Recovery Key");
        return key;
    }

    private static SecretKey getExistingKeyOrNull() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance(ANDROID_KEY_STORE);
            store.load(null);
            java.security.Key key = store.getKey(KEY_ALIAS, null);
            return key instanceof SecretKey ? (SecretKey) key : null;
        } catch (java.security.KeyStoreException | java.io.IOException
                 | java.security.NoSuchAlgorithmException | java.security.cert.CertificateException ex) {
            throw new GeneralSecurityException("Unable to access Android Keystore", ex);
        } catch (java.security.UnrecoverableKeyException ex) {
            throw new GeneralSecurityException("Local backup key is unavailable", ex);
        }
    }

    private static byte[] aad() {
        return ("MAEYAI-ON-POS-LOCAL-BACKUP-KEY:" + KEY_ALIAS)
                .getBytes(StandardCharsets.US_ASCII);
    }
}
