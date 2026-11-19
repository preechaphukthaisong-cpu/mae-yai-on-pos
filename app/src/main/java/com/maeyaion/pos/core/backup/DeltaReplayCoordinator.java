package com.maeyaion.pos.core.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Replays an authenticated delta only into a caller-supplied staging database.
 * The live Room database is never opened or activated by this class.
 */
public final class DeltaReplayCoordinator {
    private static final int SCHEMA_VERSION = 4;
    private static final int MAX_DELTA_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OPERATIONS = 100;
    private static final long FIRST_SYSTEM_BARCODE = 2_000_001L;
    private static final long MAX_SKIPPED_BARCODE_VALUES = 10_000L;

    public ApplyResult applyToStaging(@NonNull Context context, @NonNull File stagingDatabase,
            @NonNull File encryptedDelta, char[] recoveryPassword, String recoveryKey)
            throws IOException, GeneralSecurityException {
        boolean hasPassword = recoveryPassword != null && recoveryPassword.length > 0;
        boolean hasRecoveryKey = recoveryKey != null && !recoveryKey.trim().isEmpty();
        if (hasPassword == hasRecoveryKey) {
            throw new GeneralSecurityException("Supply exactly one Recovery Password or Recovery Key");
        }

        Context app = context.getApplicationContext();
        File root = app.getFilesDir().getCanonicalFile();
        File staging = stagingDatabase.getCanonicalFile();
        File envelope = encryptedDelta.getCanonicalFile();
        String privateRoot = root.getPath() + File.separator;
        if (!staging.getPath().startsWith(privateRoot) || !staging.isFile()
                || !envelope.getPath().startsWith(privateRoot) || !envelope.isFile()
                || !envelope.getName().endsWith(".myobak")) {
            throw new IOException("Staging database and encrypted delta must be app-private files");
        }
        BackupCrypto.validateEnvelopeFile(envelope);

        if (staging.equals(envelope)) {
            throw new IOException("Staging database cannot be the encrypted delta file");
        }
        File plaintext = new File(envelope.getParentFile(),
                "." + UUID.randomUUID() + ".delta-replay.tmp");
        char[] passwordCopy = hasPassword ? recoveryPassword.clone() : null;
        Throwable primaryFailure = null;
        try {
            BackupCrypto.decryptFileTo(envelope, plaintext, passwordCopy,
                    hasPassword ? null : recoveryKey);
            if (!plaintext.isFile() || plaintext.length() <= 0
                    || plaintext.length() > MAX_DELTA_BYTES) {
                throw new IOException("Decrypted delta is empty or exceeds the 4 MiB limit");
            }
            byte[] encoded = Files.readAllBytes(plaintext.toPath());
            try {
                DeltaBatch batch = parseBatch(encoded);
                return applyBatch(staging, batch);
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (passwordCopy != null) Arrays.fill(passwordCopy, '\0');
            if (plaintext.exists() && !plaintext.delete()) {
                IOException cleanupFailure = new IOException("Unable to remove temporary plaintext delta file");
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
    }

    static DeltaBatch parseBatch(byte[] encoded) throws IOException {
        if (encoded == null || encoded.length <= 0 || encoded.length > MAX_DELTA_BYTES) {
            throw new IOException("Delta payload is outside supported bounds");
        }
        try {
            JSONObject root = new JSONObject(new String(encoded, StandardCharsets.UTF_8));
            if (!"MAEYAI_DELTA_V1".equals(root.getString("format"))
                    || root.getInt("schema_version") != SCHEMA_VERSION) {
                throw new IOException("Unsupported delta format or schema version");
            }
            long first = root.getLong("first_seq");
            long last = root.getLong("last_seq");
            JSONArray operations = root.getJSONArray("operations");
            if (first <= 0 || last < first || operations.length() == 0
                    || operations.length() > MAX_OPERATIONS
                    || last - first + 1 != operations.length()) {
                throw new IOException("Delta sequence range does not match its operation count");
            }
            DeltaOperation[] rows = new DeltaOperation[operations.length()];
            long expected = first;
            for (int i = 0; i < operations.length(); i++) {
                JSONObject row = operations.getJSONObject(i);
                long sequence = row.getLong("seq");
                String operationId = row.getString("operation_id");
                String operation = row.getString("op");
                String payload = row.getString("payload");
                String hash = row.getString("payload_sha256");
                String createdAt = row.getString("created_at_utc");
                if (sequence != expected++ || !isCanonicalUuid(operationId)
                        || isBlank(operation) || isBlank(createdAt)
                        || !isSha256(hash) || !constantTimeHexEquals(hash, sha256(payload))) {
                    throw new IOException("Delta row failed sequence, identity, or integrity validation");
                }
                validateTimestamp(createdAt);
                validateOperationPayload(operation, payload, createdAt);
                rows[i] = new DeltaOperation(sequence, operationId, operation,
                        payload, hash.toLowerCase(java.util.Locale.ROOT), createdAt);
            }
            if (rows[rows.length - 1].sequence != last) {
                throw new IOException("Delta last sequence does not match its final row");
            }
            return new DeltaBatch(first, last, rows);
        } catch (JSONException | ArithmeticException malformed) {
            throw new IOException("Malformed delta JSON", malformed);
        } catch (GeneralSecurityException impossible) {
            throw new IOException("Unable to validate delta digest", impossible);
        }
    }

    private static ApplyResult applyBatch(File staging, DeltaBatch batch) throws IOException {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(staging.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        try {
            db.setForeignKeyConstraintsEnabled(true);
            int version = readUserVersion(db);
            if (version != SCHEMA_VERSION) {
                throw new IOException("Staging database schema version is not supported");
            }

            long currentMax = maxJournalSequence(db);
            int duplicates = countMatchingExistingRows(db, batch);
            if (duplicates == batch.rows.length) {
                long acknowledgedMax = acknowledgeDuplicateRows(db, batch);
                return new ApplyResult(0, true, acknowledgedMax);
            }
            if (duplicates != 0) {
                throw new IOException("Delta partially overlaps existing journal history");
            }
            if (batch.firstSequence != currentMax + 1) {
                throw new IOException(batch.firstSequence > currentMax + 1
                        ? "Delta sequence gap detected"
                        : "Delta is out of order or conflicts with existing history");
            }

            db.beginTransaction();
            try {
                if (maxJournalSequence(db) != currentMax) {
                    throw new IOException("Staging journal changed before replay transaction");
                }
                for (DeltaOperation operation : batch.rows) {
                    if (operationIdExists(db, operation.operationId)) {
                        throw new IOException("Duplicate operation ID exists at a different sequence");
                    }
                    applyProductCreate(db, operation);
                    insertJournalRow(db, operation);
                }
                verifyIntegrity(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return new ApplyResult(batch.rows.length, false, batch.lastSequence);
        } finally {
            db.close();
        }
    }

    private static int countMatchingExistingRows(SQLiteDatabase db, DeltaBatch batch)
            throws IOException {
        int matches = 0;
        for (DeltaOperation operation : batch.rows) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT operation_id, op, payload, payload_sha256, schema_version, created_at_utc "
                            + "FROM backup_journal WHERE seq=?",
                    new String[]{Long.toString(operation.sequence)})) {
                if (!cursor.moveToFirst()) continue;
                boolean same = operation.operationId.equals(cursor.getString(0))
                        && operation.operation.equals(cursor.getString(1))
                        && operation.payload.equals(cursor.getString(2))
                        && operation.payloadHash.equalsIgnoreCase(cursor.getString(3))
                        && cursor.getInt(4) == SCHEMA_VERSION
                        && operation.createdAtUtc.equals(cursor.getString(5));
                if (!same) throw new IOException("Existing journal sequence conflicts with delta row");
                matches++;
            }
        }
        return matches;
    }


    /**
     * A full snapshot can already contain the operations while their local
     * uploaded flags are still zero. Since this exact delta was downloaded from
     * Drive, acknowledge those existing rows in the staging copy to prevent
     * unnecessary re-upload after restore.
     */
    private static long acknowledgeDuplicateRows(SQLiteDatabase db, DeltaBatch batch)
            throws IOException {
        db.beginTransaction();
        try {
            if (countMatchingExistingRows(db, batch) != batch.rows.length) {
                throw new IOException("Staging journal changed during duplicate delta acknowledgement");
            }
            ContentValues uploaded = new ContentValues();
            uploaded.put("uploaded", 1);
            for (DeltaOperation operation : batch.rows) {
                int changed = db.update("backup_journal", uploaded, "seq=? AND uploaded=0",
                        new String[]{Long.toString(operation.sequence)});
                if (changed == 0) {
                    try (Cursor cursor = db.rawQuery(
                            "SELECT uploaded FROM backup_journal WHERE seq=?",
                            new String[]{Long.toString(operation.sequence)})) {
                        if (!cursor.moveToFirst() || cursor.getInt(0) != 1) {
                            throw new IOException("Duplicate delta journal acknowledgement failed");
                        }
                    }
                }
            }
            verifyIntegrity(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return maxJournalSequence(db);
    }

    private static boolean operationIdExists(SQLiteDatabase db, String operationId) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM backup_journal WHERE operation_id=? LIMIT 1",
                new String[]{operationId})) {
            return cursor.moveToFirst();
        }
    }

    private static void applyProductCreate(SQLiteDatabase db, DeltaOperation row)
            throws IOException {
        if (!"PRODUCT_CREATE_SYSTEM".equals(row.operation)
                && !"PRODUCT_CREATE_MANUFACTURER".equals(row.operation)) {
            throw new IOException("Delta operation type is not supported by this replay version");
        }
        try {
            JSONObject payload = new JSONObject(row.payload);
            String sku = payload.getString("sku");
            String barcode = payload.getString("barcode");
            String name = payload.getString("name");
            long price = payload.getLong("price_satang");
            long cost = payload.getLong("cost_satang");
            String unit = payload.getString("unit");
            Long stockLimit = payload.isNull("stock_limit_qty_milli")
                    ? null : payload.getLong("stock_limit_qty_milli");
            String created = payload.getString("created_at_utc");
            String updated = payload.getString("updated_at_utc");
            String source = "PRODUCT_CREATE_SYSTEM".equals(row.operation) ? "SYSTEM" : "MANUFACTURER";

            if (payload.getInt("v") != 1 || !row.operation.equals(payload.getString("op"))
                    || isBlank(sku) || !sku.equals(sku.trim()) || containsControl(sku)
                    || isBlank(barcode) || barcode.length() > 128 || !barcode.equals(barcode.trim())
                    || containsControl(barcode) || isBlank(name) || isBlank(unit)
                    || price < 0 || cost < 0 || (stockLimit != null && stockLimit < 0)
                    || !created.equals(row.createdAtUtc)) {
                throw new IOException("Product creation payload failed domain validation");
            }
            validateTimestamp(created);
            validateTimestamp(updated);
            if (exists(db, "SELECT 1 FROM barcode_registry WHERE barcode=?", barcode)
                    || exists(db, "SELECT 1 FROM product WHERE sku=? OR barcode=?", sku, barcode)) {
                throw new IOException("Product or barcode already exists in staging database");
            }

            if ("SYSTEM".equals(source)) {
                long candidate;
                try {
                    candidate = Long.parseLong(barcode);
                } catch (NumberFormatException invalidBarcode) {
                    throw new IOException("System barcode is not numeric", invalidBarcode);
                }
                advanceBarcodeCounter(db, candidate);
            }

            ContentValues registry = new ContentValues();
            registry.put("barcode", barcode);
            registry.put("display_name", name);
            registry.put("price_satang", price);
            registry.put("source_type", source);
            registry.put("created_at_utc", created);
            db.insertOrThrow("barcode_registry", null, registry);

            ContentValues product = new ContentValues();
            product.put("sku", sku);
            product.put("barcode", barcode);
            product.put("name", name);
            product.put("price_satang", price);
            product.put("cost_satang", cost);
            product.put("unit", unit);
            if (stockLimit == null) product.putNull("stock_limit_qty_milli");
            else product.put("stock_limit_qty_milli", stockLimit);
            product.put("stock_qty_milli", 0L);
            product.put("is_active", 1);
            product.put("created_at_utc", created);
            product.put("updated_at_utc", updated);
            db.insertOrThrow("product", null, product);

            ContentValues log = new ContentValues();
            log.put("id", "DELTA_" + row.operationId);
            log.put("action", row.operation);
            log.put("entity_type", "PRODUCT");
            log.put("entity_id", sku);
            log.put("detail_json", row.payload);
            log.put("created_at_utc", created);
            db.insertOrThrow("op_log", null, log);
        } catch (JSONException | android.database.SQLException malformed) {
            throw new IOException("Unable to apply product creation operation", malformed);
        }
    }

    private static void advanceBarcodeCounter(SQLiteDatabase db, long candidate)
            throws IOException {
        if (candidate < FIRST_SYSTEM_BARCODE || candidate == Long.MAX_VALUE) {
            throw new IOException("System barcode is outside supported range");
        }
        long current;
        try (Cursor cursor = db.rawQuery(
                "SELECT next_value FROM barcode_counter WHERE id=1", null)) {
            if (!cursor.moveToFirst()) throw new IOException("Barcode counter is missing in staging DB");
            current = cursor.getLong(0);
        }
        if (current < FIRST_SYSTEM_BARCODE || candidate < current
                || candidate - current > MAX_SKIPPED_BARCODE_VALUES) {
            throw new IOException("System barcode counter is inconsistent with delta history");
        }
        for (long skipped = current; skipped < candidate; skipped++) {
            if (!exists(db, "SELECT 1 FROM barcode_registry WHERE barcode=?",
                    Long.toString(skipped))) {
                throw new IOException("System barcode sequence contains an unexplained gap");
            }
        }
        ContentValues counter = new ContentValues();
        counter.put("next_value", candidate + 1);
        if (db.update("barcode_counter", counter, "id=1", null) != 1) {
            throw new IOException("Unable to advance system barcode counter");
        }
    }

    private static void insertJournalRow(SQLiteDatabase db, DeltaOperation row) throws IOException {
        ContentValues journal = new ContentValues();
        journal.put("seq", row.sequence);
        journal.put("operation_id", row.operationId);
        journal.put("op", row.operation);
        journal.put("payload", row.payload);
        journal.put("payload_sha256", row.payloadHash);
        journal.put("schema_version", SCHEMA_VERSION);
        journal.put("created_at_utc", row.createdAtUtc);
        journal.put("uploaded", 1);
        if (db.insert("backup_journal", null, journal) == -1) {
            throw new IOException("Unable to append replayed delta journal row");
        }
    }

    private static void validateOperationPayload(String operation, String payload, String createdAt)
            throws IOException, GeneralSecurityException {
        if (!"PRODUCT_CREATE_SYSTEM".equals(operation)
                && !"PRODUCT_CREATE_MANUFACTURER".equals(operation)) {
            throw new IOException("Delta contains an unsupported operation type");
        }
        try {
            JSONObject object = new JSONObject(payload);
            if (object.getInt("v") != 1 || !operation.equals(object.getString("op"))
                    || !createdAt.equals(object.getString("created_at_utc"))) {
                throw new IOException("Delta operation payload metadata is inconsistent");
            }
        } catch (JSONException malformed) {
            throw new IOException("Delta operation payload is malformed", malformed);
        }
    }

    private static void verifyIntegrity(SQLiteDatabase db) throws IOException {
        try (Cursor integrity = db.rawQuery("PRAGMA integrity_check", null)) {
            if (!integrity.moveToFirst() || !"ok".equalsIgnoreCase(integrity.getString(0))) {
                throw new IOException("Staging database failed integrity_check after delta replay");
            }
        }
        try (Cursor foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null)) {
            if (foreignKeys.moveToFirst()) {
                throw new IOException("Staging database has foreign-key violations after delta replay");
            }
        }
    }

    private static int readUserVersion(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("PRAGMA user_version", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private static long maxJournalSequence(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery(
                "SELECT COALESCE(MAX(seq),0) FROM backup_journal", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    private static boolean exists(SQLiteDatabase db, String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst();
        }
    }

    private static void validateTimestamp(String value) throws IOException {
        try {
            Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new IOException("Delta timestamp must be ISO-8601 UTC", invalid);
        }
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private static String sha256(String value) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte item : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return hex.toString();
    }

    private static boolean constantTimeHexEquals(String left, String right) {
        return MessageDigest.isEqual(left.toLowerCase(java.util.Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static final class DeltaBatch {
        final long firstSequence;
        final long lastSequence;
        final DeltaOperation[] rows;
        DeltaBatch(long firstSequence, long lastSequence, DeltaOperation[] rows) {
            this.firstSequence = firstSequence;
            this.lastSequence = lastSequence;
            this.rows = rows;
        }
    }

    private static final class DeltaOperation {
        final long sequence;
        final String operationId;
        final String operation;
        final String payload;
        final String payloadHash;
        final String createdAtUtc;
        DeltaOperation(long sequence, String operationId, String operation, String payload,
                String payloadHash, String createdAtUtc) {
            this.sequence = sequence;
            this.operationId = operationId;
            this.operation = operation;
            this.payload = payload;
            this.payloadHash = payloadHash;
            this.createdAtUtc = createdAtUtc;
        }
    }

    public static final class ApplyResult {
        public final int appliedCount;
        public final boolean alreadyApplied;
        public final long lastSequence;
        ApplyResult(int appliedCount, boolean alreadyApplied, long lastSequence) {
            this.appliedCount = appliedCount;
            this.alreadyApplied = alreadyApplied;
            this.lastSequence = lastSequence;
        }
    }
}
