package com.maeyaion.pos.core.sync;

import android.os.Looper;

import androidx.annotation.NonNull;

import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.tasks.Tasks;

/**
 * Obtains a fresh Google ID token using the configured GoogleSignInClient.
 * The client must be built with requestIdToken(WEB_SERVER_CLIENT_ID).
 * Call only from a background worker; never from the main thread.
 */
public final class GoogleSignInTokenProvider implements SyncAuthTokenProvider {
    private static final long TOKEN_TIMEOUT_SECONDS = 20L;
    private final GoogleSignInClient signInClient;

    public GoogleSignInTokenProvider(@NonNull GoogleSignInClient signInClient) {
        if (signInClient == null) throw new IllegalArgumentException("GoogleSignInClient is required");
        this.signInClient = signInClient;
    }

    @NonNull
    @Override
    public String getGoogleIdToken() throws SyncException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new SyncException("Google ID token refresh must run off the main thread");
        }
        try {
            GoogleSignInAccount account = Tasks.await(
                    signInClient.silentSignIn(), TOKEN_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS);
            if (account == null || account.getIdToken() == null
                    || account.getIdToken().trim().isEmpty()) {
                throw new SyncException("Google sign-in is required before syncing");
            }
            return account.getIdToken();
        } catch (SyncException error) {
            throw error;
        } catch (Exception error) {
            throw new SyncException("Unable to refresh Google sign-in for sync", error);
        }
    }
}
