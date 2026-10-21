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

    /** Exact when the phone allows it (sideloaded app on Android 13+ gets USE_EXACT_ALARM), otherwise close to it. */
    private static void setAt(Context ctx, long at, PendingIntent pi) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** When the TV will be turned off (epoch ms), or 0 if no timer is set. */
    static long endsAt(Context ctx) {
        long at = prefs(ctx).getLong("sleepAt", 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    static long set(Context ctx, int minutes) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(ctx).edit().putLong("sleepAt", at).apply();
        setAt(ctx, at, intent(ctx, ACTION_SLEEP, 7));
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

    // ---- Daily auto turn-off (e.g. 2:00 AM): turns the TV off only if it's on ----

    static final String ACTION_AUTO_OFF = "com.claude.tvremote.AUTO_OFF";

    static boolean autoOffEnabled(Context ctx) {
        return prefs(ctx).getBoolean("autoOffOn", false);
    }

    /** Minutes after midnight; defaults to 2:00 AM. */
    static int autoOffMinutes(Context ctx) {
        return prefs(ctx).getInt("autoOffAt", 120);
    }

    static void setAutoOff(Context ctx, boolean enabled, int minutes) {
        prefs(ctx).edit().putBoolean("autoOffOn", enabled).putInt("autoOffAt", minutes).apply();
        scheduleAutoOff(ctx);
    }

    /** Next time the auto turn-off runs (epoch ms), or 0 when it's disabled. */
    static long nextAutoOff(Context ctx) {
        if (!autoOffEnabled(ctx)) return 0;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int m = autoOffMinutes(ctx);
        c.set(java.util.Calendar.HOUR_OF_DAY, m / 60);
        c.set(java.util.Calendar.MINUTE, m % 60);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 30_000) c.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** (Re)arms the next run; called on change, after each run, at boot and on app start. */
    static void scheduleAutoOff(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = intent(ctx, ACTION_AUTO_OFF, 9);
        am.cancel(pi);
        long at = nextAutoOff(ctx);
        if (at > 0) setAt(ctx, at, pi);
    }
}
