package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class EncryptedSyncEventCodecTest {
    @Test public void encryptedWirePayloadRoundTripsAndContainsNoPlaintext() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storeId = "codec_store_" + System.nanoTime();
        StoreKeyManager keys = new StoreKeyManager(context);
        keys.initializeStore(storeId);
        EncryptedSyncEventCodec codec = new EncryptedSyncEventCodec(keys);

        JSONObject domain = new JSONObject();
        domain.put("schemaVersion", 1);
        JSONObject data = new JSONObject();
        data.put("sku", "SKU-SECRET-42");
        data.put("name", "Private Product Name");
        domain.put("data", data);

        JSONObject wire = codec.encrypt(storeId, "8f9a6a45-94d5-4b9e-9f39-13e0a2cbb401",
                "upsert", "product", "SKU-SECRET-42", 0, domain);
        String serialized = wire.toString();
        assertFalse(serialized.contains("SKU-SECRET-42"));
        assertFalse(serialized.contains("Private Product Name"));
        assertTrue(wire.getJSONObject("payload").has("ciphertext"));

        JSONObject restored = codec.decrypt(storeId, wire.getString("eventId"),
                wire.getString("operation"), wire.getString("entityType"),
                wire.getString("entityId"), wire.getLong("baseVersion"),
                wire.getJSONObject("payload"));
        assertEquals(domain.toString(), restored.toString());
    }

    @Test public void associatedMetadataTamperingFailsAuthentication() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String storeId = "aad_store_" + System.nanoTime();
        StoreKeyManager keys = new StoreKeyManager(context);
        keys.initializeStore(storeId);
        EncryptedSyncEventCodec codec = new EncryptedSyncEventCodec(keys);
        JSONObject payload = new JSONObject();
        payload.put("schemaVersion", 1);
        payload.put("data", new JSONObject().put("sku", "A"));
        JSONObject wire = codec.encrypt(storeId, "8f9a6a45-94d5-4b9e-9f39-13e0a2cbb402",
                "upsert", "product", "A", 0, payload);
        try {
            codec.decrypt(storeId, wire.getString("eventId"), "upsert",
                    "product", "B", 0, wire.getJSONObject("payload"));
            fail("Changed entity identity must invalidate the GCM tag");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("authentication"));
        }
    }
}
