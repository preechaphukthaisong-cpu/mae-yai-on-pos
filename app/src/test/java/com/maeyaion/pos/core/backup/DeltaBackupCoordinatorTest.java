package com.maeyaion.pos.core.backup;

import com.maeyaion.pos.core.db.BackupJournalEntity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class DeltaBackupCoordinatorTest {
    @Test public void buildsOrderedPayloadAndEscapesUntrustedJournalText() throws Exception {
        BackupJournalEntity first = entry(1, "op-1", "{\"name\":\"A\\nB\"}");
        BackupJournalEntity second = entry(2, "op-2", "{\"sku\":\"SKU-2\"}");
        String json = new String(DeltaBackupCoordinator.buildPayload(
                Arrays.asList(first, second), 4), StandardCharsets.UTF_8);

        assertTrue(json.startsWith("{\"format\":\"MAEYAI_DELTA_V1\",\"schema_version\":4"));
        assertTrue(json.contains("\"first_seq\":1,\"last_seq\":2"));
        assertTrue(json.indexOf("\"seq\":1") < json.indexOf("\"seq\":2"));
        assertTrue(json.contains("\\\\n"));
        assertTrue(json.contains("\"payload_sha256\""));
    }

    @Test public void rejectsPayloadHashTampering() throws Exception {
        BackupJournalEntity valid = entry(1, "op-1", "{\"sku\":\"A\"}");
        BackupJournalEntity tampered = new BackupJournalEntity(valid.seq, valid.operation_id,
                valid.op, "{\"sku\":\"B\"}", valid.payload_sha256, valid.schema_version,
                valid.created_at_utc, valid.uploaded);
        expectInvalid(Collections.singletonList(tampered));
    }

    @Test public void rejectsNonMonotonicSequenceAndWrongSchema() throws Exception {
        expectInvalid(Arrays.asList(entry(2, "op-2", "{}"), entry(1, "op-1", "{}")));
        expectInvalid(Collections.singletonList(entry(1, "op-1", "{}")), 3);
    }


    @Test public void rejectsSequenceGapInPendingBatch() throws Exception {
        expectInvalid(Arrays.asList(entry(1, "op-1", "{}"), entry(3, "op-3", "{}")));
    }

    @Test public void rejectsAlreadyUploadedRowsAndEmptyBatch() throws Exception {
        BackupJournalEntity valid = entry(1, "op-1", "{}");
        BackupJournalEntity uploaded = new BackupJournalEntity(valid.seq, valid.operation_id,
                valid.op, valid.payload, valid.payload_sha256, valid.schema_version,
                valid.created_at_utc, 1);
        expectInvalid(Collections.singletonList(uploaded));
        expectInvalid(Collections.emptyList());
    }

    private static BackupJournalEntity entry(long seq, String operationId, String payload)
            throws Exception {
        String hash = hex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        return new BackupJournalEntity(seq, operationId, "PRODUCT_UPDATE", payload, hash, 4,
                "2026-11-19T00:00:00Z", 0);
    }

    private static void expectInvalid(java.util.List<BackupJournalEntity> rows) throws Exception {
        expectInvalid(rows, 4);
    }

    private static void expectInvalid(java.util.List<BackupJournalEntity> rows, int version)
            throws Exception {
        try {
            DeltaBackupCoordinator.buildPayload(rows, version);
            fail("Expected invalid delta batch to be rejected");
        } catch (java.io.IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }
}
