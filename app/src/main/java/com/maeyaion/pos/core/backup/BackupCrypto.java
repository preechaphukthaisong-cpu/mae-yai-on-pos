package com.maeyaion.pos.core.backup;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Portable authenticated backup envelope.
 *
 * The payload and its SHA-256 digest are encrypted together with AES-256-GCM.
 * A random 256-bit data key is independently wrapped by the Recovery Password
 * and Recovery Key. No password or recovery secret is serialized.
 */
public final class BackupCrypto {
    private static final byte[] MAGIC = "MYONBKP1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int GCM_TAG_BITS = 128;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;
    private static final int FIXED_HEADER_BYTES = 8 + 4 + 4 + NONCE_BYTES + SALT_BYTES
            + NONCE_BYTES + NONCE_BYTES + 4;
    private static final int WRAPPED_KEY_BYTES = KEY_BYTES + GCM_TAG_BITS / 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {}

    public static String generateRecoveryKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    public static byte[] encrypt(byte[] plaintext, char[] recoveryPassword, String recoveryKey)
            throws GeneralSecurityException {
        requirePayload(plaintext);
        requirePasswordForCreation(recoveryPassword);
        byte[] recoveryKeyBytes = decodeRecoveryKey(recoveryKey);

        byte[] dataKey = randomBytes(KEY_BYTES);
        byte[] payloadNonce = randomBytes(NONCE_BYTES);
        byte[] passwordSalt = randomBytes(SALT_BYTES);
        byte[] passwordNonce = randomBytes(NONCE_BYTES);
        byte[] recoveryNonce = randomBytes(NONCE_BYTES);

        byte[] baseHeader = baseHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce);
        byte[] passwordKey = derivePasswordKey(recoveryPassword, passwordSalt);
        byte[] recoveryWrappingKey = recoveryWrappingKey(recoveryKeyBytes);

        byte[] passwordWrapped = gcm(Cipher.ENCRYPT_MODE, passwordKey, passwordNonce,
                wrapAad(baseHeader, "password"), dataKey);
        byte[] recoveryWrapped = gcm(Cipher.ENCRYPT_MODE, recoveryWrappingKey, recoveryNonce,
                wrapAad(baseHeader, "recovery"), dataKey);

        byte[] digest = sha256(plaintext);
        byte[] authenticatedPlaintext = new byte[DIGEST_BYTES + plaintext.length];
        System.arraycopy(digest, 0, authenticatedPlaintext, 0, DIGEST_BYTES);
        System.arraycopy(plaintext, 0, authenticatedPlaintext, DIGEST_BYTES, plaintext.length);

        int ciphertextLength = authenticatedPlaintext.length + GCM_TAG_BITS / 8;
        byte[] header = fullHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce,
                ciphertextLength);
        byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                payloadNonce, header, authenticatedPlaintext);

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                header.length + passwordWrapped.length + recoveryWrapped.length + ciphertext.length);
        output.write(header, 0, header.length);
        output.write(passwordWrapped, 0, passwordWrapped.length);
        output.write(recoveryWrapped, 0, recoveryWrapped.length);
        output.write(ciphertext, 0, ciphertext.length);

        wipe(dataKey);
        wipe(passwordKey);
        wipe(recoveryWrappingKey);
        wipe(recoveryKeyBytes);
        wipe(authenticatedPlaintext);
        wipe(digest);
        return output.toByteArray();
    }

    public static byte[] decryptWithPassword(byte[] envelope, char[] password)
            throws GeneralSecurityException {
        Parsed parsed = parse(envelope);
        byte[] passwordKey = derivePasswordKey(password, parsed.passwordSalt);
        try {
            byte[] dataKey = gcm(Cipher.DECRYPT_MODE, passwordKey, parsed.passwordNonce,
                    wrapAad(parsed.baseHeader, "password"), parsed.passwordWrapped);
            return decryptPayload(parsed, dataKey);
        } finally {
            wipe(passwordKey);
        }
    }

    public static byte[] decryptWithRecoveryKey(byte[] envelope, String recoveryKey)
            throws GeneralSecurityException {
        Parsed parsed = parse(envelope);
        byte[] recoveryBytes = decodeRecoveryKey(recoveryKey);
        byte[] wrappingKey = recoveryWrappingKey(recoveryBytes);
        try {
            byte[] dataKey = gcm(Cipher.DECRYPT_MODE, wrappingKey, parsed.recoveryNonce,
                    wrapAad(parsed.baseHeader, "recovery"), parsed.recoveryWrapped);
            return decryptPayload(parsed, dataKey);
        } finally {
            wipe(recoveryBytes);
            wipe(wrappingKey);
        }
    }

    private static byte[] decryptPayload(Parsed parsed, byte[] dataKey)
            throws GeneralSecurityException {
        try {
            byte[] authenticated = gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    parsed.payloadNonce, parsed.header, parsed.ciphertext);
            if (authenticated.length < DIGEST_BYTES) {
                throw new GeneralSecurityException("Backup payload is truncated");
            }
            byte[] expected = Arrays.copyOfRange(authenticated, 0, DIGEST_BYTES);
            byte[] plaintext = Arrays.copyOfRange(authenticated, DIGEST_BYTES, authenticated.length);
            byte[] actual = sha256(plaintext);
            boolean valid = MessageDigest.isEqual(expected, actual);
            wipe(authenticated);
            wipe(expected);
            wipe(actual);
            if (!valid) {
                wipe(plaintext);
                throw new GeneralSecurityException("Backup checksum mismatch");
            }
            return plaintext;
        } finally {
            wipe(dataKey);
        }
    }

    private static Parsed parse(byte[] envelope) throws GeneralSecurityException {
        if (envelope == null || envelope.length < FIXED_HEADER_BYTES + 2 * WRAPPED_KEY_BYTES
                + GCM_TAG_BITS / 8) {
            throw new GeneralSecurityException("Invalid or truncated backup envelope");
        }
        ByteBuffer input = ByteBuffer.wrap(envelope).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        if (!Arrays.equals(MAGIC, magic)) throw new GeneralSecurityException("Unknown backup format");
        int version = input.getInt();
        int iterations = input.getInt();
        if (version != VERSION || iterations != PBKDF2_ITERATIONS) {
            throw new GeneralSecurityException("Unsupported backup version or KDF parameters");
        }
        byte[] payloadNonce = readBytes(input, NONCE_BYTES);
        byte[] passwordSalt = readBytes(input, SALT_BYTES);
        byte[] passwordNonce = readBytes(input, NONCE_BYTES);
        byte[] recoveryNonce = readBytes(input, NONCE_BYTES);
        int ciphertextLength = input.getInt();
        if (ciphertextLength < DIGEST_BYTES + GCM_TAG_BITS / 8
                || ciphertextLength > MAX_PAYLOAD_BYTES + DIGEST_BYTES + GCM_TAG_BITS / 8
                || envelope.length != FIXED_HEADER_BYTES + 2 * WRAPPED_KEY_BYTES + ciphertextLength) {
            throw new GeneralSecurityException("Invalid backup payload length");
        }
        byte[] header = Arrays.copyOfRange(envelope, 0, FIXED_HEADER_BYTES);
        byte[] baseHeader = baseHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce);
        byte[] passwordWrapped = readBytes(input, WRAPPED_KEY_BYTES);
        byte[] recoveryWrapped = readBytes(input, WRAPPED_KEY_BYTES);
        byte[] ciphertext = readBytes(input, ciphertextLength);
        return new Parsed(header, baseHeader, payloadNonce, passwordSalt, passwordNonce,
                recoveryNonce, passwordWrapped, recoveryWrapped, ciphertext);
    }

    private static byte[] baseHeader(byte[] payloadNonce, byte[] passwordSalt,
                                     byte[] passwordNonce, byte[] recoveryNonce) {
        ByteBuffer out = ByteBuffer.allocate(FIXED_HEADER_BYTES - 4).order(ByteOrder.BIG_ENDIAN);
        out.put(MAGIC).putInt(VERSION).putInt(PBKDF2_ITERATIONS)
                .put(payloadNonce).put(passwordSalt).put(passwordNonce).put(recoveryNonce);
        return out.array();
    }

    private static byte[] fullHeader(byte[] payloadNonce, byte[] passwordSalt,
                                     byte[] passwordNonce, byte[] recoveryNonce, int length) {
        ByteBuffer out = ByteBuffer.allocate(FIXED_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        out.put(baseHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce)).putInt(length);
        return out.array();
    }

    private static byte[] wrapAad(byte[] baseHeader, String purpose) {
        byte[] label = ("MAEYAI-ON-POS-BACKUP-KEY-" + purpose)
                .getBytes(StandardCharsets.US_ASCII);
        ByteBuffer out = ByteBuffer.allocate(baseHeader.length + label.length);
        out.put(baseHeader).put(label);
        return out.array();
    }

    private static byte[] derivePasswordKey(char[] password, byte[] salt)
            throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new GeneralSecurityException("Recovery Password is required");
        }
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] recoveryWrappingKey(byte[] recoveryKey) throws GeneralSecurityException {
        MessageDigest digest = sha256Digest();
        digest.update("MAEYAI-ON-POS-RECOVERY-KEY-V1".getBytes(StandardCharsets.US_ASCII));
        return digest.digest(recoveryKey);
    }

    private static byte[] gcm(int mode, SecretKey key, byte[] nonce, byte[] aad, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(input);
    }

    private static byte[] sha256(byte[] value) throws GeneralSecurityException {
        return sha256Digest().digest(value);
    }

    private static MessageDigest sha256Digest() throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256");
    }

    private static byte[] decodeRecoveryKey(String encoded) throws GeneralSecurityException {
        try {
            byte[] key = Base64.getUrlDecoder().decode(encoded);
            if (key.length != KEY_BYTES) {
                wipe(key);
                throw new GeneralSecurityException("Recovery Key must contain 256 bits");
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw new GeneralSecurityException("Invalid Recovery Key", ex);
        }
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] readBytes(ByteBuffer input, int count) {
        byte[] bytes = new byte[count];
        input.get(bytes);
        return bytes;
    }

    private static void requirePayload(byte[] payload) throws GeneralSecurityException {
        if (payload == null || payload.length > MAX_PAYLOAD_BYTES) {
            throw new GeneralSecurityException("Backup payload is missing or exceeds 512 MiB");
        }
    }

    private static void requirePasswordForCreation(char[] password) throws GeneralSecurityException {
        if (password == null || password.length < 12) {
            throw new GeneralSecurityException("Recovery Password must be at least 12 characters");
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    private static final class Parsed {
        final byte[] header, baseHeader, payloadNonce, passwordSalt, passwordNonce, recoveryNonce;
        final byte[] passwordWrapped, recoveryWrapped, ciphertext;

        Parsed(byte[] header, byte[] baseHeader, byte[] payloadNonce, byte[] passwordSalt,
               byte[] passwordNonce, byte[] recoveryNonce, byte[] passwordWrapped,
               byte[] recoveryWrapped, byte[] ciphertext) {
            this.header = header;
            this.baseHeader = baseHeader;
            this.payloadNonce = payloadNonce;
            this.passwordSalt = passwordSalt;
            this.passwordNonce = passwordNonce;
            this.recoveryNonce = recoveryNonce;
            this.passwordWrapped = passwordWrapped;
            this.recoveryWrapped = recoveryWrapped;
            this.ciphertext = ciphertext;
        }
    }
}
