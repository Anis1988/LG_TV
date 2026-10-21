package com.claude.tvremote;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.KeyEvent;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    /** Set when opened from the lock-screen notification: show on top of the lock screen. */
    static final String EXTRA_OVER_LOCK = "overLock";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(TvSocketPlugin.class);
        super.onCreate(savedInstanceState);
        applySystemBars();
        applyOverLock(getIntent());

        // Back: let the page close a panel or return to the Remote tab first; only leave when it says so.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                getBridge().getWebView().evaluateJavascript("window.appBack ? window.appBack() : false", handled -> {
                    if ("true".equals(handled)) return;
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                    setEnabled(true);
                });
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyOverLock(intent);
    }

    // Only the remote is reachable without unlocking, and only while it's open from the notification.
    private void applyOverLock(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_OVER_LOCK, false)) setShowWhenLocked(true);
    }

    @Override
    public void onStop() {
        super.onStop();
        setShowWhenLocked(false);
    }

    // The activity handles uiMode changes itself, so re-color the system bars when light/dark flips.
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
    }

    // Set by the page's theme switch; null means follow the phone.
    private Boolean themeDark = null;

    void setThemeDark(boolean dark) {
        themeDark = dark;
        applySystemBars();
    }

    private void applySystemBars() {
        boolean dark = themeDark != null ? themeDark
            : (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = ContextCompat.getColor(this, dark ? R.color.app_bg_dark : R.color.app_bg_light);
        getWindow().getDecorView().setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!dark);
        bars.setAppearanceLightNavigationBars(!dark);
    }

    // Phone volume keys control the TV while it's connected.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        TvSocketPlugin plugin = TvSocketPlugin.instance;
        if (plugin != null && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
            if (plugin.onVolumeKey(code == KeyEvent.KEYCODE_VOLUME_UP, pressed)) return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
