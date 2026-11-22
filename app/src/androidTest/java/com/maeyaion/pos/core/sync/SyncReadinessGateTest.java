package com.maeyaion.pos.core.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies that installing the sync feature never enables background data transfer. */
@RunWith(AndroidJUnit4.class)
public final class SyncReadinessGateTest {
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("maeyai_sync_readiness_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After public void tearDown() {
        SyncReadinessGate.disable(context, "Test cleanup");
        context.getSharedPreferences("maeyai_sync_readiness_v1", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void syncIsDisabledByDefaultAndRemainsDisabledAfterExplicitDisable() {
        assertFalse(SyncReadinessGate.isEnabled(context));
        SyncReadinessGate.disable(context, "Qualification not complete");
        assertFalse(SyncReadinessGate.isEnabled(context));
    }

    @Test public void schedulerGateRejectsUnqualifiedSync() {
        assertFalse(SyncReadinessGate.isEnabled(context));
        try {
            SyncReadinessGate.requireEnabled(context);
            throw new AssertionError("Unqualified sync must be rejected");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("disabled"));
        }
    }
}
