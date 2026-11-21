package com.maeyaion.pos.core.sync;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

/** Creates the single Google sign-in client used for ID-token auth and Drive appData access. */
public final class SyncGoogleSignIn {
    private static final String DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata";

    private SyncGoogleSignIn() {}

    @NonNull
    public static com.google.android.gms.auth.api.signin.GoogleSignInClient createClient(
            @NonNull Context context, @NonNull String webServerClientId) throws SyncException {
        if (context == null || webServerClientId == null
                || !webServerClientId.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")) {
            throw new SyncException("A valid Google Web Server OAuth client ID is required");
        }
        GoogleSignInOptions options = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(webServerClientId)
                .requestServerAuthCode(webServerClientId, true)
                .requestScopes(new Scope(DRIVE_APPDATA_SCOPE))
                .build();
        return GoogleSignIn.getClient(context.getApplicationContext(), options);
    }
}
