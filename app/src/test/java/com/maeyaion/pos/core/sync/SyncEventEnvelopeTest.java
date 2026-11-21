package com.maeyaion.pos.core.sync;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SyncEventEnvelopeTest {
    @Test public void createsVersionedEnvelopeAndCanonicalChecksum() throws Exception {
        JSONObject data = new JSONObject();
        data.put("z", 9);
        data.put("a", "สินค้า");
        data.put("lines", new JSONArray().put(new JSONObject().put("qtyMilli", 1500).put("sku", "A-1")));

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
        JSONObject first = new JSONObject().put("b", 2).put("a", 1);
        JSONObject second = new JSONObject().put("a", 1).put("b", 2);
        SyncEventEnvelope a = SyncEventEnvelope.create("upsert", "product", "SKU-1", 0, first);
        SyncEventEnvelope b = SyncEventEnvelope.create("upsert", "product", "SKU-1", 0, second);
        assertEquals(a.payloadSha256, b.payloadSha256);
    }

    @Test public void rejectsUnsupportedOperationAndInvalidEntityVersion() throws Exception {
        JSONObject data = new JSONObject().put("name", "สินค้า");
        expectInvalid("merge", "product", "SKU-1", 0, data);
        expectInvalid("upsert", "Product", "SKU-1", 0, data);
        expectInvalid("upsert", "product", "SKU-1", -1, data);
    }

    @Test public void rejectsFloatingPointPayloadValuesToKeepWireHashDeterministic() throws Exception {
        JSONObject data = new JSONObject().put("price", 12.5d);
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
