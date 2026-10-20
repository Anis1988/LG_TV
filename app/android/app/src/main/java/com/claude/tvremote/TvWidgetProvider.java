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

/** Home-screen remote: power, arrows, OK, back, home, volume and mute. */
public class TvWidgetProvider extends AppWidgetProvider {
    static final String ACTION = "com.claude.tvremote.WIDGET_CMD";

    // One worker so quick taps run in order.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();

    private static final int[] IDS = {
        R.id.w_power, R.id.w_up, R.id.w_down, R.id.w_left, R.id.w_right, R.id.w_ok,
        R.id.w_back, R.id.w_home, R.id.w_volup, R.id.w_voldown, R.id.w_mute,
    };
    private static final String[] CMDS = {
        "POWER", "UP", "DOWN", "LEFT", "RIGHT", "ENTER",
        "BACK", "HOME", "VOLUP", "VOLDOWN", "MUTE",
    };

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] widgetIds) {
        for (int id : widgetIds) mgr.updateAppWidget(id, build(ctx));
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
        v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(ctx, 100, open, PendingIntent.FLAG_IMMUTABLE));
        return v;
    }

    // Random per-install token so other apps can't fire commands at this receiver.
    private static String token(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE);
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
        PendingResult result = goAsync();
        worker.execute(() -> {
            String status;
            try {
                status = run(ctx, cmd);
            } catch (Exception e) {
                status = e.getMessage();
            }
            Log.i("TvWidget", cmd + " -> " + status);
            setStatus(ctx, status);
            result.finish();
        });
    }

    private static String run(Context ctx, String cmd) throws Exception {
        TvClient tv = TvClient.get(ctx);
        switch (cmd) {
            case "POWER":
                if (tv.connect(2500)) {
                    tv.request("ssap://system/turnOff", null);
                    tv.disconnect();
                    return "Turning off";
                }
                tv.wake();
                return "Waking up…";
            case "VOLUP":
                tv.request("ssap://audio/volumeUp", null);
                return volumeText(tv);
            case "VOLDOWN":
                tv.request("ssap://audio/volumeDown", null);
                return volumeText(tv);
            case "MUTE": {
                boolean muted = isMuted(tv.request("ssap://audio/getVolume", null));
                tv.request("ssap://audio/setMute", new JSONObject().put("mute", !muted));
                return muted ? "Sound on" : "Muted";
            }
            default:
                tv.button(cmd);
                return "Connected";
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
            return "Volume " + (s != null ? s.optInt("volume") : v.optInt("volume"));
        } catch (Exception e) {
            return "Connected";
        }
    }

    private static void setStatus(Context ctx, String status) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, TvWidgetProvider.class));
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_remote);
        v.setTextViewText(R.id.w_status, status == null ? "Something went wrong" : status);
        mgr.partiallyUpdateAppWidget(ids, v);
    }

    private static void buzz(Context ctx) {
        Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE));
        else vib.vibrate(12);
    }
}
