package com.maeyaion.pos.core.backup;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Device-bound storage for credentials used by explicitly enabled unattended backups.
 * Uses no-backup app-private storage and an alias independent from the portable-backup DEK.
 */
public final class AndroidKeystoreCredentialVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "maeyaion-pos.backup-credential-vault.v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte[] MAGIC = "MYONCV01".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = MAGIC.length + 4 + NONCE_BYTES + 4;
    private static final int MAX_PASSWORD_BYTES = 4096;
    private static final int MAX_RECOVERY_KEY_BYTES = 1024;
    private static final int MAX_FILE_BYTES = HEADER_BYTES + MAX_PASSWORD_BYTES
            + MAX_RECOVERY_KEY_BYTES + 8 + TAG_BITS / 8;
    private static final byte[] AAD = ("MAEYAI-ON-POS-BACKUP-CREDENTIAL-VAULT:v"
            + VERSION).getBytes(StandardCharsets.US_ASCII);

    private final File vaultFile;
    private final SecureRandom random = new SecureRandom();

    public AndroidKeystoreCredentialVault(Context context) throws IOException {
        if (context == null) throw new IllegalArgumentException("Context is required");
        File root = context.getNoBackupFilesDir().getCanonicalFile();
        File directory = new File(root, "backup-credentials");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create private credential vault directory");
        }
        File canonicalDirectory = directory.getCanonicalFile();
        if (!canonicalDirectory.getPath().startsWith(root.getPath() + File.separator)
                || !canonicalDirectory.isDirectory()) {
            throw new IOException("Credential vault directory is outside private storage");
        }
        vaultFile = new File(canonicalDirectory, "credentials.v1");
    }

    /** Stores a fresh authenticated ciphertext; caller retains ownership of input arrays. */
    public synchronized void save(char[] recoveryPassword, char[] recoveryKey)
            throws IOException, GeneralSecurityException {
        if (recoveryPassword == null || recoveryPassword.length == 0
                || recoveryKey == null || recoveryKey.length == 0) {
            throw new GeneralSecurityException("Both recovery credentials are required");
        }
        byte[] passwordBytes = encode(recoveryPassword);
        byte[] recoveryBytes = encode(recoveryKey);
        byte[] plaintext = null;
        byte[] nonce = null;
        byte[] ciphertext = null;
        File temporary = new File(vaultFile.getParentFile(), ".credentials-"
                + Long.toHexString(random.nextLong()) + ".tmp");
        try {
            if (passwordBytes.length > MAX_PASSWORD_BYTES
                    || recoveryBytes.length > MAX_RECOVERY_KEY_BYTES) {
                throw new GeneralSecurityException("Recovery credential exceeds vault limits");
            }
            plaintext = ByteBuffer.allocate(8 + passwordBytes.length + recoveryBytes.length)
                    .putInt(passwordBytes.length).put(passwordBytes)
                    .putInt(recoveryBytes.length).put(recoveryBytes).array();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            nonce = cipher.getIV();
            if (nonce == null || nonce.length != NONCE_BYTES) {
                throw new GeneralSecurityException("Keystore returned an invalid GCM nonce");
            }
            cipher.updateAAD(AAD);
            ciphertext = cipher.doFinal(plaintext);
            ByteBuffer envelope = ByteBuffer.allocate(HEADER_BYTES + ciphertext.length);
            envelope.put(MAGIC).putInt(VERSION).put(nonce).putInt(ciphertext.length).put(ciphertext);

            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(envelope.array());
                output.flush();
                output.getFD().sync();
            }
            if (temporary.length() <= HEADER_BYTES + TAG_BITS / 8
                    || temporary.length() > MAX_FILE_BYTES
                    || !temporary.renameTo(vaultFile)) {
                throw new IOException("Unable to atomically publish credential vault");
            }
            Arrays.fill(envelope.array(), (byte) 0);
        } finally {
            wipe(passwordBytes);
            wipe(recoveryBytes);
            wipe(plaintext);
            wipe(nonce);
            wipe(ciphertext);
            if (temporary.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    /** Fails closed if the file, format, Keystore key, or GCM authentication is invalid. */
    public synchronized Credentials load() throws IOException, GeneralSecurityException {
        if (!vaultFile.isFile() || vaultFile.length() <= HEADER_BYTES + TAG_BITS / 8
                || vaultFile.length() > MAX_FILE_BYTES) {
            throw new GeneralSecurityException("Credential vault is missing or has invalid size");
        }
        byte[] envelope = new byte[(int) vaultFile.length()];
        byte[] nonce = null;
        byte[] ciphertext = null;
        byte[] plaintext = null;
        try (FileInputStream input = new FileInputStream(vaultFile)) {
            int offset = 0;
            while (offset < envelope.length) {
                int read = input.read(envelope, offset, envelope.length - offset);
                if (read < 0) throw new IOException("Credential vault is truncated");
                offset += read;
            }
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            byte[] magic = new byte[MAGIC.length];
            buffer.get(magic);
            int version = buffer.getInt();
            if (!Arrays.equals(MAGIC, magic) || version != VERSION) {
                throw new GeneralSecurityException("Unsupported credential vault format");
            }
            nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            int ciphertextLength = buffer.getInt();
            if (ciphertextLength != buffer.remaining()
                    || ciphertextLength < TAG_BITS / 8
                    || ciphertextLength > MAX_PASSWORD_BYTES + MAX_RECOVERY_KEY_BYTES + 8
                            + TAG_BITS / 8) {
                throw new GeneralSecurityException("Invalid credential vault payload length");
            }
            ciphertext = new byte[ciphertextLength];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            plaintext = cipher.doFinal(ciphertext);
            ByteBuffer secrets = ByteBuffer.wrap(plaintext);
            if (secrets.remaining() < 8) throw new GeneralSecurityException("Invalid vault plaintext");
            int passwordLength = secrets.getInt();
            if (passwordLength <= 0 || passwordLength > MAX_PASSWORD_BYTES
                    || passwordLength > secrets.remaining() - 4) {
                throw new GeneralSecurityException("Invalid recovery password entry");
            }
            byte[] passwordBytes = new byte[passwordLength];
            secrets.get(passwordBytes);
            int recoveryLength = secrets.getInt();
            if (recoveryLength <= 0 || recoveryLength > MAX_RECOVERY_KEY_BYTES
                    || recoveryLength != secrets.remaining()) {
                wipe(passwordBytes);
                throw new GeneralSecurityException("Invalid recovery key entry");
            }
            byte[] recoveryBytes = new byte[recoveryLength];
            secrets.get(recoveryBytes);
            try {
                return new Credentials(decode(passwordBytes), decode(recoveryBytes));
            } finally {
                wipe(passwordBytes);
                wipe(recoveryBytes);
            }
        } catch (java.nio.BufferUnderflowException malformed) {
            throw new GeneralSecurityException("Malformed credential vault", malformed);
        } finally {
            wipe(envelope);
            wipe(nonce);
            wipe(ciphertext);
            wipe(plaintext);
        }
    }

    public synchronized boolean exists() {
        return vaultFile.isFile();
    }

    /** Disabling unattended backup removes ciphertext and its dedicated device key. */
    public synchronized void clear() throws IOException, GeneralSecurityException {
        if (vaultFile.exists() && !vaultFile.delete()) {
            throw new IOException("Unable to remove credential vault ciphertext");
        }
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (java.security.cert.CertificateException | java.security.NoSuchAlgorithmException
                 | java.security.KeyStoreException failure) {
            throw new GeneralSecurityException("Unable to clear credential vault key", failure);
        }
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        SecretKey existing = getExistingKeyOrNull();
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
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
        if (key == null) throw new GeneralSecurityException(
                "Credential vault Keystore key is unavailable; owner re-entry is required");
        return key;
    }

    private static SecretKey getExistingKeyOrNull() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            java.security.Key key = store.getKey(KEY_ALIAS, null);
            return key instanceof SecretKey ? (SecretKey) key : null;
        } catch (java.security.KeyStoreException | IOException
                 | java.security.NoSuchAlgorithmException | java.security.cert.CertificateException
                 | java.security.UnrecoverableKeyException failure) {
            throw new GeneralSecurityException("Unable to access credential vault Keystore key", failure);
        }
    }

    private static byte[] encode(char[] value) throws GeneralSecurityException {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            if (encoded.hasArray()) wipe(encoded.array());
            return result;
        } catch (CharacterCodingException failure) {
            throw new GeneralSecurityException("Recovery credential is not valid Unicode", failure);
        }
    }

    private static char[] decode(byte[] value) throws GeneralSecurityException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value));
            char[] result = new char[decoded.remaining()];
            decoded.get(result);
            if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
            return result;
        } catch (CharacterCodingException failure) {
            throw new GeneralSecurityException("Credential vault contains invalid UTF-8", failure);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }

    public static final class Credentials implements AutoCloseable {
        public final char[] recoveryPassword;
        public final char[] recoveryKey;

        private Credentials(char[] recoveryPassword, char[] recoveryKey) {
            this.recoveryPassword = recoveryPassword;
            this.recoveryKey = recoveryKey;
        }

        @Override public void close() {
            Arrays.fill(recoveryPassword, '\0');
            Arrays.fill(recoveryKey, '\0');
        }
    }
}
