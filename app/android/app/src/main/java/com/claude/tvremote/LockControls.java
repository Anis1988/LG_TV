package com.claude.tvremote;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/** Notification with volume, mute and power while the TV is on; shown on the lock screen too. */
final class LockControls {
    // Default importance (not "low") so it isn't filed under Silent, which lock screens often hide.
    private static final String CHANNEL = "tv_controls_v2";
    private static final int ID = 42;

    private LockControls() {}

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE).getBoolean("lockControls", false);
    }

    static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE).edit().putBoolean("lockControls", on).apply();
        String state = ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE).getString("widgetState", "unknown");
        update(ctx, state);
    }

    private static final int REMINDER_ID = 43;

    private static boolean canNotify(Context ctx) {
        return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED;
    }

    /** "TV is still on" at bedtime, with a one-tap Turn off. Removed once the TV is off. */
    static void bedtimeReminder(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !canNotify(ctx)) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("bedtime") == null) {
            NotificationChannel ch = new NotificationChannel("bedtime", "Bedtime reminder", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Tells you when the TV is still on at bedtime");
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(ctx, 400, new Intent(ctx, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OVER_LOCK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "bedtime")
            .setSmallIcon(R.drawable.ic_qs_tv)
            .setContentTitle("The TV is still on")
            .setContentText("Turn it off for the night?")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Turn off TV", TvWidgetProvider.commandIntent(ctx, "POWER", 401));
        nm.notify(REMINDER_ID, b.build());
    }

    /** Show the controls while the TV is on, remove them when it's off. */
    static void update(Context ctx, String state) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean on = "on".equals(state) || "screen_off".equals(state);
        if ("checking".equals(state)) return;
        if (!on) nm.cancel(REMINDER_ID);
        if (!enabled(ctx) || !on) {
            nm.cancel(ID);
            return;
        }
        if (!canNotify(ctx)) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.deleteNotificationChannel("tv_controls");
            NotificationChannel ch = new NotificationChannel(CHANNEL, "TV controls", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Volume, mute and power while the TV is on");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
        // Tapping the notification opens the full remote on top of the lock screen.
        Intent openIntent = new Intent(ctx, MainActivity.class)
            .putExtra(MainActivity.EXTRA_OVER_LOCK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(ctx, 300, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Mini remote: a row of buttons when collapsed, arrows + OK when expanded.
        RemoteViews small = new RemoteViews(ctx.getPackageName(), R.layout.notif_small);
        bind(ctx, small, new int[] { R.id.n_voldown_s, R.id.n_volup_s, R.id.n_back_s, R.id.n_ok_s, R.id.n_home_s },
            new String[] { "VOLDOWN", "VOLUP", "BACK", "ENTER", "HOME" }, 310);
        RemoteViews big = new RemoteViews(ctx.getPackageName(), R.layout.notif_big);
        bind(ctx, big, new int[] { R.id.n_up, R.id.n_down, R.id.n_left, R.id.n_right, R.id.n_ok,
                R.id.n_back, R.id.n_home, R.id.n_volup, R.id.n_voldown, R.id.n_mute },
            new String[] { "UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "HOME", "VOLUP", "VOLDOWN", "MUTE" }, 320);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_tv)
            .setContentTitle("LG TV is on")
            .setContentText("screen_off".equals(state) ? "Screen off · sound on" : "Tap to open the full remote")
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "Turn off TV", TvWidgetProvider.commandIntent(ctx, "POWER", 304));
        nm.notify(ID, b.build());
    }

    private static void bind(Context ctx, RemoteViews v, int[] ids, String[] cmds, int firstCode) {
        for (int i = 0; i < ids.length; i++) {
            v.setOnClickPendingIntent(ids[i], TvWidgetProvider.commandIntent(ctx, cmds[i], firstCode + i));
        }
    }
}
