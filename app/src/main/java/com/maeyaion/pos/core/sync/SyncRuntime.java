package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

/** Fully configured per-installation sync dependencies. Construct only after account consent/configuration. */
public final class SyncRuntime {
    @NonNull public final SyncAccountBootstrapper bootstrapper;
    @NonNull public final SyncPushCoordinator pushCoordinator;
    @NonNull public final SyncPullCoordinator pullCoordinator;
    @NonNull public final StoreKeyManager keyManager;

    public SyncRuntime(@NonNull SyncAccountBootstrapper bootstrapper,
            @NonNull SyncPushCoordinator pushCoordinator,
            @NonNull SyncPullCoordinator pullCoordinator,
            @NonNull StoreKeyManager keyManager) {
        if (bootstrapper == null || pushCoordinator == null || pullCoordinator == null
                || keyManager == null) throw new IllegalArgumentException("All sync runtime components are required");
        this.bootstrapper = bootstrapper;
        this.pushCoordinator = pushCoordinator;
        this.pullCoordinator = pullCoordinator;
        this.keyManager = keyManager;
    }
}
