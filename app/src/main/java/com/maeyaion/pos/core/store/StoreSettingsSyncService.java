package com.maeyaion.pos.core.store;

import androidx.annotation.NonNull;

import com.maeyaion.pos.core.db.AppDatabase;
import com.maeyaion.pos.core.db.AppMetaEntity;
import com.maeyaion.pos.core.db.BackupJournalEntity;
import com.maeyaion.pos.core.db.OpLogEntity;
import com.maeyaion.pos.core.sync.SyncException;
import com.maeyaion.pos.core.sync.SyncOutboxWriter;
import com.maeyaion.pos.core.tx.TransactionRunner;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit allow-listed store profile settings. Secrets, device preferences, auth tokens,
 * recovery material and operational toggles are intentionally not syncable.
 */
public final class StoreSettingsSyncService {
    public static final String STORE_NAME = "store_name";
    public static final String STORE_PHONE = "store_phone";
    public static final String STORE_ADDRESS = "store_address";
    public static final String RECEIPT_FOOTER = "receipt_footer";
    public static final String TAX_ID = "tax_id";

    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            STORE_NAME, STORE_PHONE, STORE_ADDRESS, RECEIPT_FOOTER, TAX_ID));

    private final AppDatabase database;
    private final TransactionRunner transactions;
    private final SyncOutboxWriter syncOutbox;

    public StoreSettingsSyncService(@NonNull AppDatabase database,
            @NonNull TransactionRunner transactions) {
        this.database = Objects.requireNonNull(database);
        this.transactions = Objects.requireNonNull(transactions);
        this.syncOutbox = new SyncOutboxWriter(database);
    }

    /** Updates one approved profile setting and its encrypted outbox event atomically. */
    public void update(@NonNull String key, @NonNull String value, @NonNull String atUtc) {
        validate(key, value, atUtc);
        transactions.runInTransaction(() -> {
            String old = database.appMetaDao().findValue(key);
            if (Objects.equals(old, value)) return null;
            database.appMetaDao().put(new AppMetaEntity(key, value, atUtc));
            String payload = "{\"setting_key\":" + JSONObject.quote(key)
                    + ",\"value\":" + JSONObject.quote(value)
                    + ",\"updated_at_utc\":" + JSONObject.quote(atUtc) + "}";
            String operationId = UUID.randomUUID().toString();
            database.opLogDao().append(new OpLogEntity(UUID.randomUUID().toString(),
                    "STORE_SETTING_UPDATED", "STORE_SETTING", key, payload, atUtc));
            database.backupJournalDao().append(new BackupJournalEntity(0L, operationId,
                    "STORE_SETTING_UPDATED", payload, sha256(payload), 7, atUtc, 0));
            try {
                JSONObject data = new JSONObject();
                data.put("setting_key", key);
                data.put("value", value);
                data.put("updated_at_utc", atUtc);
                syncOutbox.appendUnboundInTransaction("upsert", "store_setting",
                        key, data, atUtc);
            } catch (JSONException | SyncException error) {
                throw new IllegalStateException("Unable to append encrypted store setting event", error);
            }
            return null;
        });
    }

    private static void validate(String key, String value, String atUtc) {
        if (key == null || !ALLOWED.contains(key) || value == null) {
            throw new IllegalArgumentException("Store setting key or value is not allowed");
        }
        int max = STORE_ADDRESS.equals(key) ? 1000 : RECEIPT_FOOTER.equals(key) ? 500
                : STORE_NAME.equals(key) ? 160 : STORE_PHONE.equals(key) ? 32 : 32;
        if (value.length() > max || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Store setting value exceeds its allowed format");
        }
        if (STORE_PHONE.equals(key) && !value.isEmpty()
                && !value.matches("[0-9+() .-]{6,32}")) {
            throw new IllegalArgumentException("Store phone format is invalid");
        }
        if (TAX_ID.equals(key) && !value.isEmpty()
                && !value.matches("[0-9-]{10,20}")) {
            throw new IllegalArgumentException("Tax ID format is invalid");
        }
        Instant.parse(atUtc);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
