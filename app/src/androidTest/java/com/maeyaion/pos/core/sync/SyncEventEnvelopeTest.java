package com.maeyaion.pos.core.sync;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SyncEventEnvelopeTest {
    @Test public void createsVersionedEnvelopeAndCanonicalChecksum() throws Exception {
        JSONObject data = new JSONObject();
        data.put("z", 9);
        data.put("a", "สินค้า");
        JSONObject line = new JSONObject();
        line.put("qtyMilli", 1500);
        line.put("sku", "A-1");
        JSONArray lines = new JSONArray();
        lines.put(line);
        data.put("lines", lines);

        SyncEventEnvelope event = SyncEventEnvelope.create(
                "upsert", "sale", "sale-123", 0, data);
        JSONObject wire = event.toJsonObject();

        assertEquals(event.eventId, wire.getString("eventId"));
        assertEquals("upsert", wire.getString("operation"));
        assertEquals("sale", wire.getString("entityType"));
        assertEquals(0, wire.getLong("baseVersion"));
        assertEquals(SyncEventEnvelope.CURRENT_SCHEMA_VERSION,
                wire.getJSONObject("payload").getInt("schemaVersion"));
        assertEquals("สินค้า", wire.getJSONObject("payload").getJSONObject("data").getString("a"));
        assertEquals(64, wire.getString("payloadSha256").length());
    }

    @Test public void canonicalChecksumDoesNotDependOnObjectInsertionOrder() throws Exception {
        JSONObject first = new JSONObject();
        first.put("b", 2);
        first.put("a", 1);
        JSONObject second = new JSONObject();
        second.put("a", 1);
        second.put("b", 2);
        SyncEventEnvelope a = SyncEventEnvelope.create("upsert", "product", "SKU-1", 0, first);
        SyncEventEnvelope b = SyncEventEnvelope.create("upsert", "product", "SKU-1", 0, second);
        assertEquals(a.payloadSha256, b.payloadSha256);
    }

    @Test public void rejectsUnsupportedOperationAndInvalidEntityVersion() throws Exception {
        JSONObject data = new JSONObject();
        data.put("name", "สินค้า");
        expectInvalid("merge", "product", "SKU-1", 0, data);
        expectInvalid("upsert", "Product", "SKU-1", 0, data);
        expectInvalid("upsert", "product", "SKU-1", -1, data);
    }

    @Test public void rejectsFloatingPointPayloadValuesToKeepWireHashDeterministic() throws Exception {
        JSONObject data = new JSONObject();
        data.put("price", 12.5d);
        expectInvalid("upsert", "product", "SKU-1", 0, data);
    }

    private static void expectInvalid(String op, String type, String id, long version,
            JSONObject data) throws Exception {
        try {
            SyncEventEnvelope.create(op, type, id, version, data);
            fail("Expected invalid event to be rejected");
        } catch (SyncException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
