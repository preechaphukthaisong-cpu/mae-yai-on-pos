package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public final class AndroidKeystoreCredentialVaultTest {
    private Context context;
    private AndroidKeystoreCredentialVault vault;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        vault = new AndroidKeystoreCredentialVault(context);
        vault.clear();
    }

    @After public void tearDown() throws Exception {
        vault.clear();
    }

    @Test public void roundTripStoresCredentialsWithoutChangingCallerArrays() throws Exception {
        char[] password = "a sufficiently long recovery password".toCharArray();
        char[] recoveryKey = "0123456789abcdef0123456789abcdef".toCharArray();
        char[] passwordBefore = password.clone();
        char[] keyBefore = recoveryKey.clone();
        vault.save(password, recoveryKey);

        assertArrayEquals(passwordBefore, password);
        assertArrayEquals(keyBefore, recoveryKey);
        assertTrue(vault.exists());
        try (AndroidKeystoreCredentialVault.Credentials restored = vault.load()) {
            assertArrayEquals(passwordBefore, restored.recoveryPassword);
            assertArrayEquals(keyBefore, restored.recoveryKey);
        }
        Arrays.fill(password, '\0');
        Arrays.fill(recoveryKey, '\0');
    }

    @Test public void tamperedCiphertextFailsAuthentication() throws Exception {
        vault.save("valid recovery password".toCharArray(),
                "0123456789abcdef0123456789abcdef".toCharArray());
        File file = new File(context.getNoBackupFilesDir(),
                "backup-credentials/credentials.v1");
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        bytes[bytes.length - 1] ^= 0x01;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
            output.getFD().sync();
        }
        Arrays.fill(bytes, (byte) 0);
        try {
            vault.load();
            fail("Tampered vault must fail closed");
        } catch (GeneralSecurityException expected) {
            // AES-GCM authentication must reject altered ciphertext.
        }
    }

    @Test public void clearRemovesVaultAndPreventsSubsequentLoad() throws Exception {
        vault.save("valid recovery password".toCharArray(),
                "0123456789abcdef0123456789abcdef".toCharArray());
        vault.clear();
        assertFalse(vault.exists());
        try {
            vault.load();
            fail("Cleared vault must not load");
        } catch (GeneralSecurityException expected) {
            // Missing ciphertext is a closed failure.
        }
    }

    @Test public void malformedEmptyCredentialsAreRejected() throws Exception {
        try {
            vault.save(new char[0], "0123456789abcdef0123456789abcdef".toCharArray());
            fail("Empty password must be rejected");
        } catch (GeneralSecurityException expected) {
            assertFalse(vault.exists());
        }
    }
}
