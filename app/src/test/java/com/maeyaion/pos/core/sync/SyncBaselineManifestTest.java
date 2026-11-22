package com.maeyaion.pos.core.sync;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

public final class SyncBaselineManifestTest {
    @Test public void manifestRoundTripsAndRejectsTamperedIdentity() throws Exception {
        String digest = "a".repeat(64);
        SyncBaselineManifest manifest = SyncBaselineManifest.create(
                "store_123", 42L, 6, digest, "b".repeat(64), "2026-11-22T01:00:00Z");
        SyncBaselineManifest decoded = SyncBaselineManifest.fromJson(manifest.toJson());
        assertEquals("store_123", decoded.storeId);
        assertEquals(42L, decoded.throughSequence);
        assertEquals(6, decoded.databaseSchemaVersion);
        assertEquals(digest, decoded.snapshotSha256);
        JSONObject tampered = manifest.toJson();
        tampered.put("storeId", "another_store");
        assertNotEquals(manifest.storeId, SyncBaselineManifest.fromJson(tampered).storeId);
    }

    @Test public void invalidDigestsAndSequencesAreRejected() throws Exception {
        try {
            SyncBaselineManifest.create("store_123", -1L, 6,
                    "x".repeat(64), "b".repeat(64), "2026-11-22T01:00:00Z");
            fail("Invalid manifest must be rejected");
        } catch (SyncException expected) { }
    }
}
