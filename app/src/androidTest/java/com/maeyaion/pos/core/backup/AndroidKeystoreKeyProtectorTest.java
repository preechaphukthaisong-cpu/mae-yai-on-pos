package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

@RunWith(AndroidJUnit4.class)
public final class AndroidKeystoreKeyProtectorTest {
    @Test public void localDataKeyRoundTripsThroughNonExportableKeystoreKey() throws Exception {
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(dataKey);
        AndroidKeystoreKeyProtector protector = new AndroidKeystoreKeyProtector();

        byte[] protectedKey = protector.protect(dataKey);
        assertArrayEquals(dataKey, protector.unprotect(protectedKey));
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
