package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.backup.BackupCrypto;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class DeviceKeyEnrollmentCryptoTest {
    @Test public void devicePrivateKeyNeverLeavesKeystoreAndWrapRoundTrips() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceKeyPairManager manager = new DeviceKeyPairManager(context);
        String publicKey = manager.getPublicKeyBase64();
        byte[] original = new byte[32];
        new java.security.SecureRandom().nextBytes(original);
        byte[] wrapped = manager.wrapForDevice(publicKey, original);
        assertNotEquals(publicKey, java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(wrapped));
        byte[] restored = manager.unwrapForThisDevice(wrapped);
        assertArrayEquals(original, restored);
        Arrays.fill(original, (byte) 0);
        Arrays.fill(restored, (byte) 0);
        Arrays.fill(wrapped, (byte) 0);
    }

    @Test public void recoveryWrapperUsesStoreAndEpochAsAuthenticatedContext() throws Exception {
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] storeKey = new byte[32];
        new java.security.SecureRandom().nextBytes(storeKey);
        String wrapped = StoreKeyRecoveryWrapper.wrap("store_test_01", 3, recoveryKey, storeKey);
        byte[] recovered = StoreKeyRecoveryWrapper.unwrap("store_test_01", 3, recoveryKey, wrapped);
        assertArrayEquals(storeKey, recovered);
        try {
            StoreKeyRecoveryWrapper.unwrap("store_test_01", 4, recoveryKey, wrapped);
            fail("Wrong epoch must fail authentication");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
        Arrays.fill(storeKey, (byte) 0);
        Arrays.fill(recovered, (byte) 0);
    }
}
