package com.claude.tvremote;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.RemoteViews;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Home-screen remote: power, arrows, OK, back, home, volume and mute, plus a
 * status line that shows whether the TV is on or off.
 */
public class TvWidgetProvider extends AppWidgetProvider {
    static final String ACTION = "com.claude.tvremote.WIDGET_CMD";

    // One worker so quick taps run in order.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static final int[] IDS = {
        R.id.w_power, R.id.w_up, R.id.w_down, R.id.w_left, R.id.w_right, R.id.w_ok,
        R.id.w_back, R.id.w_home, R.id.w_volup, R.id.w_voldown, R.id.w_mute, R.id.w_status_row,
    };
    private static final String[] CMDS = {
        "POWER", "UP", "DOWN", "LEFT", "RIGHT", "ENTER",
        "BACK", "HOME", "VOLUP", "VOLDOWN", "MUTE", "STATUS",
    };

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] widgetIds) {
        for (int id : widgetIds) mgr.updateAppWidget(id, build(ctx));
        // Placed, or the 30-minute refresh: check whether the TV is on.
        PendingResult result = goAsync();
        worker.execute(() -> {
            showState(ctx, TvClient.get(ctx).powerState(), null);
            result.finish();
        });
    }

    private static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_remote);
        String token = token(ctx);
        for (int i = 0; i < IDS.length; i++) {
            Intent intent = new Intent(ctx, TvWidgetProvider.class)
                .setAction(ACTION)
                .putExtra("cmd", CMDS[i])
                .putExtra("token", token);
            v.setOnClickPendingIntent(IDS[i], PendingIntent.getBroadcast(ctx, i, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_name, PendingIntent.getActivity(ctx, 100, open, PendingIntent.FLAG_IMMUTABLE));
        SharedPreferences prefs = prefs(ctx);
        applyState(v, prefs.getString("widgetState", "unknown"), prefs.getString("widgetText", null));
        return v;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE);
    }

    // Random per-install token so other apps can't fire commands at this receiver.
    private static String token(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String t = prefs.getString("widgetToken", null);
        if (t == null) {
            t = UUID.randomUUID().toString();
            prefs.edit().putString("widgetToken", t).apply();
        }
        return t;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (!ACTION.equals(intent.getAction())) return;
        if (!token(ctx).equals(intent.getStringExtra("token"))) return;
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) return;

        buzz(ctx);
        if ("STATUS".equals(cmd)) showState(ctx, "checking", null);
        PendingResult result = goAsync();
        worker.execute(() -> {
            String[] out = run(ctx, cmd);
            Log.i("TvWidget", cmd + " -> " + out[0] + " / " + out[1]);
            showState(ctx, out[0], out[1]);
            result.finish();
        });
    }

    /** Returns {state, message}; message null means use the plain state label. */
    private static String[] run(Context ctx, String cmd) {
        TvClient tv = TvClient.get(ctx);
        try {
            switch (cmd) {
                case "STATUS":
                    return new String[] { tv.powerState(), null };
                case "POWER":
                    if (tv.connect(2500)) {
                        tv.request("ssap://system/turnOff", null);
                        tv.disconnect();
                        return new String[] { "off", null };
                    }
                    tv.wake();
                    // Give the TV a few seconds to boot, then confirm.
                    for (int i = 0; i < 4; i++) {
                        Thread.sleep(2500);
                        if (tv.connect(1500)) return new String[] { "on", null };
                    }
                    return new String[] { "off", "Waking… tap to check" };
                case "VOLUP":
                    tv.request("ssap://audio/volumeUp", null);
                    return new String[] { "on", volumeText(tv) };
                case "VOLDOWN":
                    tv.request("ssap://audio/volumeDown", null);
                    return new String[] { "on", volumeText(tv) };
                case "MUTE": {
                    boolean muted = isMuted(tv.request("ssap://audio/getVolume", null));
                    tv.request("ssap://audio/setMute", new JSONObject().put("mute", !muted));
                    return new String[] { "on", muted ? "TV is on · sound on" : "TV is on · muted" };
                }
                default:
                    tv.button(cmd);
                    return new String[] { "on", null };
            }
        } catch (Exception e) {
            String state = tv.stateAfterFailure();
            return new String[] { state, "on".equals(state) ? e.getMessage() : null };
        }
    }

    private static boolean isMuted(JSONObject v) {
        JSONObject s = v.optJSONObject("volumeStatus");
        return s != null ? s.optBoolean("muteStatus") : v.optBoolean("muted");
    }

    private static String volumeText(TvClient tv) {
        try {
            JSONObject v = tv.request("ssap://audio/getVolume", null);
            JSONObject s = v.optJSONObject("volumeStatus");
            return "TV is on · volume " + (s != null ? s.optInt("volume") : v.optInt("volume"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String label(String state) {
        switch (state) {
            case "on": return "TV is on";
            case "screen_off": return "Screen off · sound on";
            case "off": return "TV is off";
            case "unreachable": return "Can't reach TV";
            case "checking": return "Checking…";
            default: return "Tap to check";
        }
    }

    private static void applyState(RemoteViews v, String state, String text) {
        v.setTextViewText(R.id.w_status, text != null ? text : label(state));
        int dot;
        switch (state) {
            case "on": dot = R.drawable.w_dot_on; break;
            case "screen_off": dot = R.drawable.w_dot_screen; break;
            case "unreachable": dot = R.drawable.w_dot_unknown; break;
            case "off": dot = R.drawable.w_dot_off; break;
            default: dot = R.drawable.w_dot_unknown;
        }
        v.setImageViewResource(R.id.w_dot, dot);
        // Power button: green "turn on" when off, red "turn off" when on.
        boolean isOn = "on".equals(state) || "screen_off".equals(state);
        v.setInt(R.id.w_power, "setBackgroundResource", isOn ? R.drawable.widget_btn_power : R.drawable.widget_btn_power_on);
        v.setImageViewResource(R.id.w_power, isOn ? R.drawable.wi_power : R.drawable.wi_power_on);
    }

    /** Updates every widget's status; also used by the app when it knows the state. */
    static void showState(Context ctx, String state, String text) {
        if (!"checking".equals(state)) {
            prefs(ctx).edit().putString("widgetState", state).putString("widgetText", text).apply();
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TvWidgetProvider.class));
        if (ids.length == 0) return;
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_remote);
        applyState(v, state, text);
        mgr.partiallyUpdateAppWidget(ids, v);
    }

    private static void buzz(Context ctx) {
        Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
        else vib.vibrate(12);
    }
}
