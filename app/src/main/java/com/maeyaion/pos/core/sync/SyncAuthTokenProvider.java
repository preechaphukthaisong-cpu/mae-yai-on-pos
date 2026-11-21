package com.maeyaion.pos.core.sync;

import androidx.annotation.NonNull;

/** Supplies a short-lived Google ID token for the signed-in store owner. */
public interface SyncAuthTokenProvider {
    /** Must return a fresh or valid ID token; never return an access token or server auth code. */
    @NonNull String getGoogleIdToken() throws SyncException;
}
