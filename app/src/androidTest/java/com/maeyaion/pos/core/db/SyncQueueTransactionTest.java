package com.maeyaion.pos.core.db;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.maeyaion.pos.core.sync.SyncEventEnvelope;
import com.maeyaion.pos.core.sync.SyncException;
import com.maeyaion.pos.core.sync.SyncInboxProcessor;
import com.maeyaion.pos.core.sync.SyncOutboxWriter;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SyncQueueTransactionTest {
    private AppDatabase db;
    private String name;

    @Before public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "sync-queue-" + System.nanoTime() + ".db";
        db = Room.databaseBuilder(context, AppDatabase.class, name)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .addMigrations(Migration1To2.INSTANCE, Migration2To3.INSTANCE,
                        Migration3To4.INSTANCE, Migration4To5.INSTANCE, Migration5To6.INSTANCE)
                .addCallback(AppDatabase.databaseCallback())
                .allowMainThreadQueries().build();
        db.getOpenHelper().getWritableDatabase();
    }

    @After public void tearDown() {
        if (db != null) db.close();
        InstrumentationRegistry.getInstrumentation().getTargetContext().deleteDatabase(name);
    }

    @Test public void outboxAndAggregateVersionRollBackWithBusinessTransaction() throws Exception {
        SyncOutboxWriter writer = new SyncOutboxWriter(db);
        JSONObject data = new JSONObject();
        data.put("sku", "SKU-1");
        try {
            db.runInTransaction(() -> {
                try {
                    writer.appendInTransaction("store-1", "upsert", "product",
                            "SKU-1", data, "2026-11-21T00:00:00Z");
                } catch (SyncException error) {
                    throw new IllegalStateException(error);
                }
                throw new IllegalStateException("simulated business failure");
            });
            fail("Expected rollback");
        } catch (IllegalStateException expected) {
            assertEquals("simulated business failure", expected.getMessage());
        }
        assertEquals(0L, db.syncQueueDao().pendingOutboxCount("store-1"));
        assertNull(db.syncQueueDao().entityVersion("product", "SKU-1"));
    }

    @Test public void outboxRequiresTransactionAndAcknowledgementIsIdempotent() throws Exception {
        SyncOutboxWriter writer = new SyncOutboxWriter(db);
        JSONObject data = new JSONObject();
        data.put("sku", "SKU-1");
        try {
            writer.appendInTransaction("store-1", "upsert", "product",
                    "SKU-1", data, "2026-11-21T00:00:00Z");
            fail("Outbox must reject writes outside a transaction");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("transaction"));
        }
        final String[] eventId = new String[1];
        db.runInTransaction(() -> {
            try {
                eventId[0] = writer.appendInTransaction("store-1", "upsert", "product",
                        "SKU-1", data, "2026-11-21T00:00:00Z").eventId;
            } catch (SyncException error) {
                throw new IllegalStateException(error);
            }
        });
        assertEquals(1L, db.syncQueueDao().pendingOutboxCount("store-1"));
        assertEquals(1L, db.syncQueueDao().entityVersion("product", "SKU-1").version);
        assertEquals(1, db.syncQueueDao().acknowledgeOutbox(eventId[0]));
        assertEquals(0, db.syncQueueDao().acknowledgeOutbox(eventId[0]));
    }

    @Test public void duplicateRemoteEventIsIgnoredAndCursorMovesOnlyAfterReplayCommit() throws Exception {
        JSONObject data = new JSONObject();
        data.put("sku", "SKU-REMOTE");
        SyncEventEnvelope envelope = SyncEventEnvelope.create(
                "upsert", "product", "SKU-REMOTE", 0, data);
        SyncInboxEntity event = new SyncInboxEntity(envelope.eventId, "store-1", 1,
                "upsert", "product", "SKU-REMOTE", 0, 1,
                envelope.payload.toString(), envelope.payloadSha256,
                "2026-11-21T00:00:00Z", 0);
        SyncInboxProcessor processor = new SyncInboxProcessor(db);
        assertTrue(processor.receive(event));
        assertFalse(processor.receive(event));

        try {
            processor.applyNext("store-1", "2026-11-21T00:00:01Z", remote -> {
                throw new IllegalStateException("simulated domain failure");
            });
            fail("Expected replay failure");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("rolled back"));
        }
        assertEquals(0L, db.syncQueueDao().cursor("store-1") == null
                ? 0L : db.syncQueueDao().cursor("store-1").last_applied_sequence);
        assertEquals(1L, db.syncQueueDao().unappliedInboxCount("store-1"));

        assertTrue(processor.applyNext("store-1", "2026-11-21T00:00:02Z", remote -> {
            // Test handler stands in for a domain service using the same Room transaction.
        }));
        assertEquals(1L, db.syncQueueDao().cursor("store-1").last_applied_sequence);
        assertEquals(0L, db.syncQueueDao().unappliedInboxCount("store-1"));
        assertEquals(1L, db.syncQueueDao().entityVersion("product", "SKU-REMOTE").version);
    }

    @Test public void acknowledgedLocalEventEchoAdvancesCursorWithoutApplyingTwice() throws Exception {
        JSONObject data = new JSONObject();
        data.put("sku", "SKU-LOCAL");
        SyncOutboxWriter writer = new SyncOutboxWriter(db);
        final SyncEventEnvelope[] event = new SyncEventEnvelope[1];
        db.runInTransaction(() -> {
            try {
                event[0] = writer.appendUnboundInTransaction("upsert", "product",
                        "SKU-LOCAL", data, "2026-11-21T00:00:00Z");
            } catch (SyncException error) {
                throw new IllegalStateException(error);
            }
        });
        assertEquals(1, db.syncQueueDao().acknowledgeOutbox(event[0].eventId));
        SyncInboxEntity echo = new SyncInboxEntity(event[0].eventId, "__UNBOUND__", 1,
                "upsert", "product", "SKU-LOCAL", 0, 1, event[0].payload.toString(),
                event[0].payloadSha256, "2026-11-21T00:00:01Z", 0);
        SyncInboxProcessor processor = new SyncInboxProcessor(db);
        assertTrue(processor.receive(echo));
        final int[] calls = {0};
        assertTrue(processor.applyNext("__UNBOUND__", "2026-11-21T00:00:02Z", remote -> calls[0]++));
        assertEquals(0, calls[0]);
        assertEquals(1L, db.syncQueueDao().cursor("__UNBOUND__").last_applied_sequence);
    }

    @Test public void reusedRemoteEventIdWithDifferentPayloadIsRejected() throws Exception {
        JSONObject original = new JSONObject();
        original.put("sku", "SKU-ORIGINAL");
        SyncEventEnvelope first = SyncEventEnvelope.create("upsert", "product", "SKU-1", 0, original);
        SyncInboxProcessor processor = new SyncInboxProcessor(db);
        SyncInboxEntity firstRow = new SyncInboxEntity(first.eventId, "store-1", 1,
                "upsert", "product", "SKU-1", 0, 1, first.payload.toString(),
                first.payloadSha256, "2026-11-21T00:00:00Z", 0);
        assertTrue(processor.receive(firstRow));

        JSONObject altered = new JSONObject();
        altered.put("sku", "SKU-ALTERED");
        SyncEventEnvelope changed = SyncEventEnvelope.create("upsert", "product", "SKU-1", 0, altered);
        SyncInboxEntity replay = new SyncInboxEntity(first.eventId, "store-1", 1,
                "upsert", "product", "SKU-1", 0, 1, changed.payload.toString(),
                changed.payloadSha256, "2026-11-21T00:00:01Z", 0);
        try {
            processor.receive(replay);
            fail("Reused event ID with changed payload must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reused"));
        }
    }

}
