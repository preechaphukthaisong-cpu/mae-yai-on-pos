package com.maeyaion.pos.core.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class DeviceIdentityStoreTest {
    @Test public void installationIdentityIsStableAndCanonicalUuid() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefsName = "maeyai_sync_identity";
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit();
        DeviceIdentityStore first = new DeviceIdentityStore(context);
        String id = first.getOrCreateDeviceId();

        assertNotNull(id);
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals(id, new DeviceIdentityStore(context).getOrCreateDeviceId());
        assertTrue(first.getDeviceLabel().length() <= 100);

        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void transportRejectsPlainHttpBeforeMakingAnyRequest() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            new SyncApiClient("http://sync.example.invalid",
                    () -> "not-used", new DeviceIdentityStore(context));
            fail("Plain HTTP must be rejected");
        } catch (SyncException expected) {
            assertTrue(expected.getMessage().contains("HTTPS"));
        }
    }
}
