package com.maeyaion.pos.core.sync;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** Non-exportable per-installation RSA-OAEP key pair for wrapping store keys to approved devices. */
public final class DeviceKeyPairManager {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "maeyai-pos-device-key-wrap-v1";
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT);

    public DeviceKeyPairManager(@NonNull Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
    }

    @NonNull
    public synchronized String getPublicKeyBase64() throws SyncException {
        try {
            KeyStore store = keyStore();
            if (!store.containsAlias(ALIAS)) generateKeyPair();
            java.security.cert.Certificate certificate = store.getCertificate(ALIAS);
            if (certificate == null || certificate.getPublicKey() == null) {
                throw new SyncException("Device public key is unavailable");
            }
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(certificate.getPublicKey().getEncoded());
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to access device key pair", error);
        }
    }

    @NonNull
    public byte[] wrapForDevice(@NonNull String publicKeyBase64, @NonNull byte[] storeKey)
            throws SyncException {
        if (publicKeyBase64 == null || storeKey == null || storeKey.length != 32) {
            throw new SyncException("Target public key or store key is invalid");
        }
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(publicKeyBase64);
            PublicKey publicKey = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            if (!"RSA".equalsIgnoreCase(publicKey.getAlgorithm())) {
                throw new SyncException("Target device key must use RSA");
            }
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP);
            return cipher.doFinal(storeKey);
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to wrap store key for the target device", error);
        }
    }

    @NonNull
    public synchronized byte[] unwrapForThisDevice(@NonNull byte[] wrappedKey)
            throws SyncException {
        if (wrappedKey == null || wrappedKey.length < 256 || wrappedKey.length > 1024) {
            throw new SyncException("Wrapped store key length is invalid");
        }
        try {
            KeyStore store = keyStore();
            if (!store.containsAlias(ALIAS)) throw new SyncException("This device has no enrolled key pair");
            java.security.Key privateKey = store.getKey(ALIAS, null);
            if (!(privateKey instanceof java.security.PrivateKey)) {
                throw new SyncException("Device private key is unavailable");
            }
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP);
            byte[] result = cipher.doFinal(wrappedKey);
            if (result.length != 32) {
                java.util.Arrays.fill(result, (byte) 0);
                throw new SyncException("Unwrapped store key has an invalid length");
            }
            return result;
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to unwrap store key for this device", error);
        }
    }

    private static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(3072)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setRandomizedEncryptionRequired(true)
                .build());
        generator.generateKeyPair();
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        return store;
    }
}
