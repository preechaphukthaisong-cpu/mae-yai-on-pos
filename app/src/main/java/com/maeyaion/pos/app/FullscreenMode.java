package com.maeyaion.pos.app;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Applies immersive full-screen presentation consistently to every app screen. */
public final class FullscreenMode implements Application.ActivityLifecycleCallbacks {
    @Override public void onActivityResumed(Activity activity) {
        apply(activity.getWindow());
    }

    private static void apply(Window window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override public void onActivityCreated(Activity activity, android.os.Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { }
}
