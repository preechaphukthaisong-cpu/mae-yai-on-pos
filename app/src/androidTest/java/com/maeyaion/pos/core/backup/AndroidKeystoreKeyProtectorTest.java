package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class AndroidKeystoreKeyProtectorTest {
    @Test public void localDataKeyRoundTripsThroughNonExportableKeystoreKey() throws Exception {
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(dataKey);
        AndroidKeystoreKeyProtector protector = new AndroidKeystoreKeyProtector();

        byte[] protectedKey = protector.protect(dataKey);
        assertArrayEquals(dataKey, protector.unprotect(protectedKey));
    }

    @Test public void streamingEnvelopeRoundTripsWithPasswordAndRecoveryKey() throws Exception {
        File source = File.createTempFile("maeyai-stream-source-", ".db");
        File envelope = File.createTempFile("maeyai-stream-envelope-", ".myobak");
        File passwordOutput = File.createTempFile("maeyai-stream-password-", ".db");
        File keyOutput = File.createTempFile("maeyai-stream-key-", ".db");
        File localOutput = File.createTempFile("maeyai-stream-local-", ".db");
        char[] password = "streaming backup password".toCharArray();
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] payload = new byte[3 * 1024 * 1024 + 137];
        new SecureRandom().nextBytes(payload);
        try {
            Files.write(source.toPath(), payload);
            AndroidKeystoreKeyProtector protector = new AndroidKeystoreKeyProtector();
            byte[] localKey = BackupCrypto.encryptFile(
                    source, envelope, password, recoveryKey, protector);
            assertEquals(60, localKey.length);
            BackupCrypto.validateEnvelopeFile(envelope);

            BackupCrypto.decryptFileTo(envelope, passwordOutput, password, null);
            BackupCrypto.decryptFileTo(envelope, keyOutput, null, recoveryKey);
            BackupCrypto.decryptFileToLocalKey(envelope, localOutput, localKey, protector);
            assertArrayEquals(payload, Files.readAllBytes(passwordOutput.toPath()));
            assertArrayEquals(payload, Files.readAllBytes(keyOutput.toPath()));
            assertArrayEquals(payload, Files.readAllBytes(localOutput.toPath()));

            byte[] damaged = Files.readAllBytes(envelope.toPath());
            damaged[damaged.length - 1] ^= 0x20;
            Files.write(envelope.toPath(), damaged);
            File rejected = new File(envelope.getParentFile(), "rejected-" + System.nanoTime() + ".db");
            try {
                BackupCrypto.decryptFileTo(envelope, rejected, password, null);
                fail("Tampered streaming envelope must not publish plaintext");
            } catch (GeneralSecurityException expected) {
                assertFalse(rejected.exists());
            }
        } finally {
            Arrays.fill(password, '\\0');
            Arrays.fill(payload, (byte) 0);
            source.delete();
            envelope.delete();
            passwordOutput.delete();
            keyOutput.delete();
            localOutput.delete();
        }
    }

    @Test public void tamperedLocalKeyBlobIsRejected() throws Exception {
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(dataKey);
        AndroidKeystoreKeyProtector protector = new AndroidKeystoreKeyProtector();
        byte[] protectedKey = protector.protect(dataKey);
        protectedKey[protectedKey.length - 1] ^= 0x01;
        try {
            protector.unprotect(protectedKey);
            fail("Expected Keystore GCM authentication to fail");
        } catch (GeneralSecurityException expected) {
            // Authentication failure is expected for modified local key blobs.
        }
    }
}
