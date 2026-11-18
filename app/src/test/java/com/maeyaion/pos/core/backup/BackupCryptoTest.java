package com.maeyaion.pos.core.backup;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class BackupCryptoTest {
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();

    @Test public void passwordAndRecoveryKeyBothRestoreSamePayload() throws Exception {
        byte[] plain = "Maeyai-On POS backup payload".getBytes(StandardCharsets.UTF_8);
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] envelope = BackupCrypto.encrypt(plain, PASSWORD, recoveryKey);

        assertArrayEquals(plain, BackupCrypto.decryptWithPassword(envelope, PASSWORD));
        assertArrayEquals(plain, BackupCrypto.decryptWithRecoveryKey(envelope, recoveryKey));
    }

    @Test public void everyBackupUsesFreshRandomizedEncryptionMaterial() throws Exception {
        byte[] plain = "same payload".getBytes(StandardCharsets.UTF_8);
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] first = BackupCrypto.encrypt(plain, PASSWORD, recoveryKey);
        byte[] second = BackupCrypto.encrypt(plain, PASSWORD, recoveryKey);
        assertNotEquals(Arrays.toString(first), Arrays.toString(second));
    }

    @Test public void wrongPasswordAndWrongRecoveryKeyAreRejected() throws Exception {
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] envelope = BackupCrypto.encrypt(new byte[]{1, 2, 3}, PASSWORD, recoveryKey);
        expectFailure(() -> BackupCrypto.decryptWithPassword(envelope, "wrong password".toCharArray()));
        expectFailure(() -> BackupCrypto.decryptWithRecoveryKey(envelope,
                BackupCrypto.generateRecoveryKey()));
    }

    @Test public void tamperedHeaderWrappedKeyAndCiphertextAreRejected() throws Exception {
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] envelope = BackupCrypto.encrypt(new byte[]{9, 8, 7}, PASSWORD, recoveryKey);
        int[] positions = {12, 45, envelope.length - 1};
        for (int position : positions) {
            byte[] tampered = envelope.clone();
            tampered[position] ^= 0x40;
            expectFailure(() -> BackupCrypto.decryptWithPassword(tampered, PASSWORD));
            expectFailure(() -> BackupCrypto.decryptWithRecoveryKey(tampered, recoveryKey));
        }
    }

    @Test public void truncatedAndUnknownFormatAreRejected() throws Exception {
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] envelope = BackupCrypto.encrypt(new byte[]{1}, PASSWORD, recoveryKey);
        expectFailure(() -> BackupCrypto.decryptWithPassword(
                Arrays.copyOf(envelope, envelope.length - 1), PASSWORD));
        byte[] unknown = envelope.clone();
        unknown[0] = 'X';
        expectFailure(() -> BackupCrypto.decryptWithPassword(unknown, PASSWORD));
    }

    @Test public void weakPasswordAndMalformedRecoveryKeyAreRejected() throws Exception {
        expectFailure(() -> BackupCrypto.encrypt(new byte[]{1}, "short".toCharArray(),
                BackupCrypto.generateRecoveryKey()));
        expectFailure(() -> BackupCrypto.encrypt(new byte[]{1}, PASSWORD, "not-a-key"));
    }

    @Test public void uploadPreflightAcceptsEnvelopeAndRejectsRenamedPlaintext() throws Exception {
        String recoveryKey = BackupCrypto.generateRecoveryKey();
        byte[] envelope = BackupCrypto.encrypt(new byte[]{4, 5, 6}, PASSWORD, recoveryKey);
        File valid = File.createTempFile("backup-valid-", ".myobak");
        File plain = File.createTempFile("backup-plain-", ".myobak");
        try {
            Files.write(valid.toPath(), envelope);
            BackupCrypto.validateEnvelopeFile(valid);
            Files.write(plain.toPath(), "plain sqlite bytes".getBytes(StandardCharsets.UTF_8));
            try {
                BackupCrypto.validateEnvelopeFile(plain);
                fail("Plaintext renamed as .myobak must be rejected");
            } catch (GeneralSecurityException expected) {
                // Structural envelope validation rejects non-backup payloads.
            }
        } finally {
            valid.delete();
            plain.delete();
        }
    }

    private static void expectFailure(CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Expected authenticated decryption to fail");
        } catch (GeneralSecurityException expected) {
            // Authentication and format failures are intentionally indistinguishable to callers.
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
