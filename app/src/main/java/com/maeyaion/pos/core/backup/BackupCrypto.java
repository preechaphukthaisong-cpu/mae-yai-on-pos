package com.maeyaion.pos.core.backup;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

    /**
     * Performs bounded structural validation before a backup is eligible for upload.
     * This rejects plaintext/renamed files and malformed envelopes without loading
     * the backup payload into memory. GCM authenticity is verified only when a
     * recovery credential is supplied during restore.
     */
    public static void validateEnvelopeFile(File file)
            throws IOException, GeneralSecurityException {
        if (file == null || !file.isFile()) {
            throw new IOException("Encrypted backup file is missing");
        }
        long fileLength = file.length();
        long minimum = (long) FIXED_HEADER_BYTES + 2L * WRAPPED_KEY_BYTES
                + DIGEST_BYTES + GCM_TAG_BITS / 8;
        long maximum = (long) FIXED_HEADER_BYTES + 2L * WRAPPED_KEY_BYTES
                + MAX_PAYLOAD_BYTES + DIGEST_BYTES + GCM_TAG_BITS / 8;
        if (fileLength < minimum || fileLength > maximum) {
            throw new GeneralSecurityException("Backup envelope size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new GeneralSecurityException("Unknown backup format");
            }
            int version = input.readInt();
            int iterations = input.readInt();
            if (version != VERSION || iterations != PBKDF2_ITERATIONS) {
                throw new GeneralSecurityException("Unsupported backup version or KDF parameters");
            }
            input.skipBytes(NONCE_BYTES + SALT_BYTES + NONCE_BYTES + NONCE_BYTES);
            int ciphertextLength = input.readInt();
            long expectedLength = (long) FIXED_HEADER_BYTES + 2L * WRAPPED_KEY_BYTES
                    + ciphertextLength;
            if (ciphertextLength < DIGEST_BYTES + GCM_TAG_BITS / 8
                    || ciphertextLength > MAX_PAYLOAD_BYTES + DIGEST_BYTES + GCM_TAG_BITS / 8
                    || expectedLength != fileLength) {
                throw new GeneralSecurityException("Backup envelope length is invalid");
            }
        } catch (IOException e) {
            throw e;
        }
    }

    public static String generateRecoveryKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    public static byte[] encrypt(byte[] plaintext, char[] recoveryPassword, String recoveryKey)
            throws GeneralSecurityException {
        return encryptInternal(plaintext, recoveryPassword, recoveryKey, null).envelope.clone();
    }

    /** Creates a portable envelope and a separate device-bound DEK wrapper. */
    public static EncryptedEnvelope encryptWithLocalKeyProtector(byte[] plaintext,
            char[] recoveryPassword, String recoveryKey, AndroidKeystoreKeyProtector protector)
            throws GeneralSecurityException {
        if (protector == null) throw new GeneralSecurityException("Local key protector is required");
        return encryptInternal(plaintext, recoveryPassword, recoveryKey, protector);
    }

    private static EncryptedEnvelope encryptInternal(byte[] plaintext, char[] recoveryPassword,
            String recoveryKey, AndroidKeystoreKeyProtector localProtector)
            throws GeneralSecurityException {
        requirePayload(plaintext);
        requirePasswordForCreation(recoveryPassword);

        byte[] recoveryKeyBytes = null;
        byte[] dataKey = null;
        byte[] passwordKey = null;
        byte[] recoveryWrappingKey = null;
        byte[] authenticatedPlaintext = null;
        byte[] digest = null;
        byte[] passwordWrapped = null;
        byte[] recoveryWrapped = null;
        byte[] ciphertext = null;
        byte[] locallyProtectedKey = null;
        try {
            recoveryKeyBytes = decodeRecoveryKey(recoveryKey);
            dataKey = randomBytes(KEY_BYTES);
            byte[] payloadNonce = randomBytes(NONCE_BYTES);
            byte[] passwordSalt = randomBytes(SALT_BYTES);
            byte[] passwordNonce = randomBytes(NONCE_BYTES);
            byte[] recoveryNonce = randomBytes(NONCE_BYTES);

            byte[] baseHeader = baseHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce);
            passwordKey = derivePasswordKey(recoveryPassword, passwordSalt);
            recoveryWrappingKey = recoveryWrappingKey(recoveryKeyBytes);

            passwordWrapped = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(passwordKey, "AES"),
                    passwordNonce, wrapAad(baseHeader, "password"), dataKey);
            recoveryWrapped = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(recoveryWrappingKey, "AES"),
                    recoveryNonce, wrapAad(baseHeader, "recovery"), dataKey);

            digest = sha256(plaintext);
            authenticatedPlaintext = new byte[DIGEST_BYTES + plaintext.length];
            System.arraycopy(digest, 0, authenticatedPlaintext, 0, DIGEST_BYTES);
            System.arraycopy(plaintext, 0, authenticatedPlaintext, DIGEST_BYTES, plaintext.length);

            int ciphertextLength = authenticatedPlaintext.length + GCM_TAG_BITS / 8;
            byte[] header = fullHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce,
                    ciphertextLength);
            ciphertext = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    payloadNonce, header, authenticatedPlaintext);

            if (localProtector != null) locallyProtectedKey = localProtector.protect(dataKey);

            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    header.length + passwordWrapped.length + recoveryWrapped.length + ciphertext.length);
            output.write(header, 0, header.length);
            output.write(passwordWrapped, 0, passwordWrapped.length);
            output.write(recoveryWrapped, 0, recoveryWrapped.length);
            output.write(ciphertext, 0, ciphertext.length);
            return new EncryptedEnvelope(output.toByteArray(), locallyProtectedKey);
        } finally {
            wipe(recoveryKeyBytes);
            wipe(dataKey);
            wipe(passwordKey);
            wipe(recoveryWrappingKey);
            wipe(authenticatedPlaintext);
            wipe(digest);
            wipe(passwordWrapped);
            wipe(recoveryWrapped);
            wipe(ciphertext);
            // EncryptedEnvelope owns a defensive copy of this wrapped key.
            wipe(locallyProtectedKey);
        }
    }

    /**
     * Encrypts a database snapshot file without materializing its contents in heap memory.
     * The caller owns cleanup of the destination if this method fails.
     *
     * @return device-bound Keystore wrapper for the random data key; never upload it.
     */
    public static byte[] encryptFile(File plaintextFile, File envelopeFile,
            char[] recoveryPassword, String recoveryKey,
            AndroidKeystoreKeyProtector localProtector)
            throws IOException, GeneralSecurityException {
        if (plaintextFile == null || !plaintextFile.isFile() || envelopeFile == null
                || localProtector == null) {
            throw new IOException("Plaintext file, destination and local key protector are required");
        }
        long payloadLength = plaintextFile.length();
        if (payloadLength <= 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new GeneralSecurityException("Backup payload is empty or exceeds 512 MiB");
        }
        requirePasswordForCreation(recoveryPassword);
        byte[] recoveryBytes = null;
        byte[] dataKey = null;
        byte[] passwordKey = null;
        byte[] recoveryWrapKey = null;
        byte[] digest = null;
        byte[] passwordWrapped = null;
        byte[] recoveryWrapped = null;
        byte[] localWrapped = null;
        try {
            recoveryBytes = decodeRecoveryKey(recoveryKey);
            dataKey = randomBytes(KEY_BYTES);
            byte[] payloadNonce = randomBytes(NONCE_BYTES);
            byte[] passwordSalt = randomBytes(SALT_BYTES);
            byte[] passwordNonce = randomBytes(NONCE_BYTES);
            byte[] recoveryNonce = randomBytes(NONCE_BYTES);
            byte[] base = baseHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce);
            passwordKey = derivePasswordKey(recoveryPassword, passwordSalt);
            recoveryWrapKey = recoveryWrappingKey(recoveryBytes);
            passwordWrapped = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(passwordKey, "AES"),
                    passwordNonce, wrapAad(base, "password"), dataKey);
            recoveryWrapped = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(recoveryWrapKey, "AES"),
                    recoveryNonce, wrapAad(base, "recovery"), dataKey);
            digest = digestFile(plaintextFile);
            int cipherLength = Math.toIntExact(payloadLength + DIGEST_BYTES + GCM_TAG_BITS / 8);
            byte[] header = fullHeader(payloadNonce, passwordSalt, passwordNonce,
                    recoveryNonce, cipherLength);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, payloadNonce));
            cipher.updateAAD(header);
            localWrapped = localProtector.protect(dataKey);

            MessageDigest secondPass = sha256Digest();
            long readTotal = 0;
            long cipherTotal = 0;
            try (FileInputStream input = new FileInputStream(plaintextFile);
                 FileOutputStream output = new FileOutputStream(envelopeFile, false)) {
                output.write(header);
                output.write(passwordWrapped);
                output.write(recoveryWrapped);
                byte[] encryptedDigest = cipher.update(digest);
                if (encryptedDigest != null) {
                    output.write(encryptedDigest);
                    cipherTotal += encryptedDigest.length;
                    wipe(encryptedDigest);
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    readTotal += read;
                    secondPass.update(buffer, 0, read);
                    byte[] encrypted = cipher.update(buffer, 0, read);
                    if (encrypted != null) {
                        output.write(encrypted);
                        cipherTotal += encrypted.length;
                        wipe(encrypted);
                    }
                }
                byte[] finalBlock = cipher.doFinal();
                output.write(finalBlock);
                cipherTotal += finalBlock.length;
                wipe(finalBlock);
                if (readTotal != payloadLength || cipherTotal != cipherLength
                        || !MessageDigest.isEqual(digest, secondPass.digest())
                        || plaintextFile.length() != payloadLength) {
                    throw new IOException("Snapshot changed while backup encryption was running");
                }
                output.flush();
                output.getFD().sync();
            }
            return localWrapped.clone();
        } catch (ArithmeticException tooLarge) {
            throw new GeneralSecurityException("Backup payload length overflow", tooLarge);
        } finally {
            wipe(recoveryBytes);
            wipe(dataKey);
            wipe(passwordKey);
            wipe(recoveryWrapKey);
            wipe(digest);
            wipe(passwordWrapped);
            wipe(recoveryWrapped);
            wipe(localWrapped);
            if (envelopeFile.exists() && envelopeFile.length() == 0) {
                //noinspection ResultOfMethodCallIgnored
                envelopeFile.delete();
            }
        }
    }

    /**
     * Decrypts an envelope to a private temporary file using bounded buffers.
     * The output must not be published or consumed until this method returns:
     * GCM authentication is finalized only after the final ciphertext/tag bytes.
     */
    public static void decryptFileTo(File envelopeFile, File plaintextFile,
            char[] recoveryPassword, String recoveryKey)
            throws IOException, GeneralSecurityException {
        boolean usePassword = recoveryPassword != null && recoveryPassword.length > 0;
        boolean useRecoveryKey = recoveryKey != null && !recoveryKey.trim().isEmpty();
        if (usePassword == useRecoveryKey || envelopeFile == null || plaintextFile == null
                || !envelopeFile.isFile()) {
            throw new GeneralSecurityException("Supply exactly one recovery credential and valid files");
        }
        byte[] passwordSalt = null, payloadNonce = null, passwordNonce = null, recoveryNonce = null;
        byte[] passwordWrapped = null, recoveryWrapped = null, passwordKey = null;
        byte[] recoveryBytes = null, recoveryWrapKey = null, dataKey = null;
        byte[] header = null, base = null;
        boolean success = false;
        try (DataInputStream input = new DataInputStream(new FileInputStream(envelopeFile))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) throw new GeneralSecurityException("Unknown backup format");
            int version = input.readInt();
            int iterations = input.readInt();
            if (version != VERSION || iterations != PBKDF2_ITERATIONS) {
                throw new GeneralSecurityException("Unsupported backup version or KDF parameters");
            }
            payloadNonce = new byte[NONCE_BYTES];
            passwordSalt = new byte[SALT_BYTES];
            passwordNonce = new byte[NONCE_BYTES];
            recoveryNonce = new byte[NONCE_BYTES];
            input.readFully(payloadNonce);
            input.readFully(passwordSalt);
            input.readFully(passwordNonce);
            input.readFully(recoveryNonce);
            int cipherLength = input.readInt();
            long expectedFileLength = (long) FIXED_HEADER_BYTES + 2L * WRAPPED_KEY_BYTES + cipherLength;
            if (cipherLength < DIGEST_BYTES + GCM_TAG_BITS / 8
                    || cipherLength > MAX_PAYLOAD_BYTES + DIGEST_BYTES + GCM_TAG_BITS / 8
                    || expectedFileLength != envelopeFile.length()) {
                throw new GeneralSecurityException("Backup envelope length is invalid");
            }
            header = fullHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce, cipherLength);
            base = baseHeader(payloadNonce, passwordSalt, passwordNonce, recoveryNonce);
            passwordWrapped = new byte[WRAPPED_KEY_BYTES];
            recoveryWrapped = new byte[WRAPPED_KEY_BYTES];
            input.readFully(passwordWrapped);
            input.readFully(recoveryWrapped);
            if (usePassword) {
                passwordKey = derivePasswordKey(recoveryPassword, passwordSalt);
                dataKey = gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(passwordKey, "AES"),
                        passwordNonce, wrapAad(base, "password"), passwordWrapped);
            } else {
                recoveryBytes = decodeRecoveryKey(recoveryKey);
                recoveryWrapKey = recoveryWrappingKey(recoveryBytes);
                dataKey = gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(recoveryWrapKey, "AES"),
                        recoveryNonce, wrapAad(base, "recovery"), recoveryWrapped);
            }

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, payloadNonce));
            cipher.updateAAD(header);
            PlaintextDigestVerifier verifier = new PlaintextDigestVerifier(plaintextFile);
            try {
                int remaining = cipherLength;
                byte[] encrypted = new byte[64 * 1024];
                while (remaining > 0) {
                    int read = input.read(encrypted, 0, Math.min(encrypted.length, remaining));
                    if (read < 0) throw new IOException("Backup ciphertext is truncated");
                    remaining -= read;
                    byte[] clear = cipher.update(encrypted, 0, read);
                    if (clear != null) {
                        verifier.accept(clear, 0, clear.length);
                        wipe(clear);
                    }
                }
                byte[] finalClear = cipher.doFinal();
                if (finalClear != null) {
                    verifier.accept(finalClear, 0, finalClear.length);
                    wipe(finalClear);
                }
                verifier.finish();
            } finally {
                verifier.close();
            }
            success = true;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            throw failure;
        } finally {
            wipe(passwordSalt);
            wipe(payloadNonce);
            wipe(passwordNonce);
            wipe(recoveryNonce);
            wipe(passwordWrapped);
            wipe(recoveryWrapped);
            wipe(passwordKey);
            wipe(recoveryBytes);
            wipe(recoveryWrapKey);
            wipe(dataKey);
            wipe(header);
            wipe(base);
            if (!success && plaintextFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                plaintextFile.delete();
            }
        }
    }

    private static byte[] digestFile(File file) throws IOException, GeneralSecurityException {
        MessageDigest digest = sha256Digest();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return digest.digest();
    }

    private static final class PlaintextDigestVerifier implements AutoCloseable {
        private final FileOutputStream output;
        private final MessageDigest digest;
        private final byte[] expected = new byte[DIGEST_BYTES];
        private int expectedBytes;
        private boolean finished;

        PlaintextDigestVerifier(File destination) throws IOException, GeneralSecurityException {
            output = new FileOutputStream(destination, false);
            digest = sha256Digest();
        }

        void accept(byte[] bytes, int offset, int length) throws IOException {
            int position = offset;
            int remaining = length;
            if (expectedBytes < DIGEST_BYTES) {
                int count = Math.min(DIGEST_BYTES - expectedBytes, remaining);
                System.arraycopy(bytes, position, expected, expectedBytes, count);
                expectedBytes += count;
                position += count;
                remaining -= count;
            }
            if (remaining > 0) {
                output.write(bytes, position, remaining);
                digest.update(bytes, position, remaining);
            }
        }

        void finish() throws IOException, GeneralSecurityException {
            if (expectedBytes != DIGEST_BYTES
                    || !MessageDigest.isEqual(expected, digest.digest())) {
                throw new GeneralSecurityException("Backup plaintext checksum mismatch");
            }
            output.flush();
            output.getFD().sync();
            finished = true;
        }

        @Override public void close() throws IOException {
            output.close();
            wipe(expected);
            if (!finished) {
                // Caller removes the unauthenticated temporary output.
            }
        }
    }

    public static byte[] decryptWithPassword(byte[] envelope, char[] password)
            throws GeneralSecurityException {
        Parsed parsed = parse(envelope);
        byte[] passwordKey = derivePasswordKey(password, parsed.passwordSalt);
        try {
            byte[] dataKey = gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(passwordKey, "AES"), parsed.passwordNonce,
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
            byte[] dataKey = gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(wrappingKey, "AES"), parsed.recoveryNonce,
                    wrapAad(parsed.baseHeader, "recovery"), parsed.recoveryWrapped);
            return decryptPayload(parsed, dataKey);
        } finally {
            wipe(recoveryBytes);
            wipe(wrappingKey);
        }
    }

    public static byte[] decryptWithLocalKey(byte[] envelope, byte[] locallyProtectedDataKey,
            AndroidKeystoreKeyProtector protector) throws GeneralSecurityException {
        if (protector == null) throw new GeneralSecurityException("Local key protector is required");
        Parsed parsed = parse(envelope);
        byte[] dataKey = protector.unprotect(locallyProtectedDataKey);
        return decryptPayload(parsed, dataKey);
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
        if (encoded == null || encoded.isEmpty()) {
            throw new GeneralSecurityException("Recovery Key is required");
        }
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

    public static final class EncryptedEnvelope {
        public final byte[] envelope;
        public final byte[] locallyProtectedDataKey;

        private EncryptedEnvelope(byte[] envelope, byte[] locallyProtectedDataKey) {
            this.envelope = envelope.clone();
            this.locallyProtectedDataKey = locallyProtectedDataKey == null
                    ? null : locallyProtectedDataKey.clone();
        }
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
