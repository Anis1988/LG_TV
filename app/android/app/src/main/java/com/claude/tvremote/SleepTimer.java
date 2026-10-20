package com.claude.tvremote;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Sleep timer and the background status refresh. Both run through
 * BackgroundReceiver, so they work with the app closed.
 */
final class SleepTimer {
    static final String ACTION_SLEEP = "com.claude.tvremote.SLEEP_FIRE";
    static final String ACTION_REFRESH = "com.claude.tvremote.STATUS_REFRESH";
    private static final long REFRESH_EVERY = AlarmManager.INTERVAL_FIFTEEN_MINUTES;

    private SleepTimer() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent intent(Context ctx, String action, int code) {
        Intent i = new Intent(ctx, BackgroundReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** When the TV will be turned off (epoch ms), or 0 if no timer is set. */
    static long endsAt(Context ctx) {
        long at = prefs(ctx).getLong("sleepAt", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    static long set(Context ctx, int minutes) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(ctx).edit().putLong("sleepAt", at).apply();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        // Inexact is fine for a sleep timer and needs no special alarm permission.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx, ACTION_SLEEP, 7));
        TvWidgetProvider.redraw(ctx);
        return at;
    }

    static void cancel(Context ctx) {
        prefs(ctx).edit().remove("sleepAt").apply();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(intent(ctx, ACTION_SLEEP, 7));
        TvWidgetProvider.redraw(ctx);
    }

    static void clearFired(Context ctx) {
        prefs(ctx).edit().remove("sleepAt").apply();
    }

    /** Re-check the TV every ~15 min while the phone is awake (non-wakeup, so no battery cost at night). */
    static void scheduleRefresh(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + REFRESH_EVERY, REFRESH_EVERY,
            intent(ctx, ACTION_REFRESH, 8));
    }

    static void cancelRefresh(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(intent(ctx, ACTION_REFRESH, 8));
    }
}
