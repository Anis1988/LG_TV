package com.claude.tvremote;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** "LG TV" tile in the Quick Settings pull-down: shows on/off and toggles power. */
public class TvTileService extends TileService {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    static void requestUpdate(Context ctx) {
        try {
            TileService.requestListeningState(ctx, new ComponentName(ctx, TvTileService.class));
        } catch (Exception ignored) {}
    }

    @Override
    public void onStartListening() {
        render(savedState(), null);
        // Opening the pull-down is a good moment to re-check the TV.
        worker.execute(() -> {
            String state = TvClient.get(this).powerState();
            TvWidgetProvider.showState(this, state, null);
            main.post(() -> render(state, null));
        });
    }

    @Override
    public void onClick() {
        String state = savedState();
        boolean on = "on".equals(state) || "screen_off".equals(state);
        render(on ? "off" : "on", on ? "Turning off…" : "Turning on…");
        worker.execute(() -> {
            TvClient tv = TvClient.get(this);
            String result;
            try {
                if (on && tv.connect(3000)) {
                    tv.request("ssap://system/turnOff", null);
                    tv.disconnect();
                    result = "off";
                } else if (on) {
                    result = tv.stateAfterFailure();
                } else {
                    tv.wake();
                    result = "off";
                    for (int i = 0; i < 5 && "off".equals(result); i++) {
                        Thread.sleep(2500);
                        if (tv.connect(1500)) result = "on";
                    }
                }
            } catch (Exception e) {
                result = tv.stateAfterFailure();
            }
            String finalState = result;
            TvWidgetProvider.showState(this, finalState, null);
            main.post(() -> render(finalState, null));
        });
    }

    private String savedState() {
        return getSharedPreferences(TvClient.PREFS, MODE_PRIVATE).getString("widgetState", "unknown");
    }

    private void render(String state, String subtitle) {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = "on".equals(state) || "screen_off".equals(state);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("LG TV");
        if (Build.VERSION.SDK_INT >= 29) {
            String sub = subtitle != null ? subtitle
                : on ? "On" : "unreachable".equals(state) ? "Can't reach" : "Off";
            tile.setSubtitle(sub);
        }
        tile.setContentDescription(on ? "LG TV is on" : "LG TV is off");
        tile.updateTile();
    }
}
