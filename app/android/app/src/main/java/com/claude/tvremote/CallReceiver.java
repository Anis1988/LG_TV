package com.claude.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Pauses and mutes the TV when a call rings or is answered, and undoes it when the call ends.
 * Only restores what it changed itself, and only acts if the TV is on.
 */
public class CallReceiver extends BroadcastReceiver {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean("callPause", false);
    }

    static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("callPause", on).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE);
    }

    /** One TV command; a refusal (e.g. "play" when nothing is paused) doesn't stop the others. */
    private static void step(TvClient tv, String uri, Boolean mute) {
        try {
            tv.request(uri, mute == null ? null : new JSONObject().put("mute", mute));
        } catch (Exception e) {
            Log.i("TvCall", uri + " skipped: " + e.getMessage());
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction()) || !enabled(ctx)) return;
        String phone = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (phone == null) return;
        boolean inCall = !TelephonyManager.EXTRA_STATE_IDLE.equals(phone);
        boolean pausedByUs = prefs(ctx).getBoolean("callPaused", false);
        // Android sends each state more than once; only act on a real change.
        if (inCall == pausedByUs) return;

        PendingResult result = goAsync();
        worker.execute(() -> {
            TvClient tv = TvClient.get(ctx);
            if (inCall) {
                String state = tv.powerState();
                if ("on".equals(state) || "screen_off".equals(state)) {
                    prefs(ctx).edit().putBoolean("callPaused", true).apply();
                    // Mute first (works everywhere, incl. live TV), then pause if something is playing.
                    step(tv, "ssap://audio/setMute", true);
                    step(tv, "ssap://media.controls/pause", null);
                }
            } else {
                prefs(ctx).edit().putBoolean("callPaused", false).apply();
                step(tv, "ssap://audio/setMute", false);
                step(tv, "ssap://media.controls/play", null);
            }
            result.finish();
        });
    }
}
