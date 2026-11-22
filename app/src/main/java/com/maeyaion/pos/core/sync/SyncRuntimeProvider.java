package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

/** App-owned factory for authenticated sync runtime dependencies. */
public interface SyncRuntimeProvider {
    @NonNull SyncRuntime createSyncRuntime() throws SyncException;
}
