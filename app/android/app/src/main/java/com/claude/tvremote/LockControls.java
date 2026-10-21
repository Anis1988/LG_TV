package com.claude.tvremote;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
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

    /** Show the controls while the TV is on, remove them when it's off. */
    static void update(Context ctx, String state) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        boolean on = "on".equals(state) || "screen_off".equals(state);
        if ("checking".equals(state)) return;
        if (!enabled(ctx) || !on) {
            nm.cancel(ID);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
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
        PendingIntent open = PendingIntent.getActivity(ctx, 300, new Intent(ctx, MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_qs_tv)
            .setContentTitle("LG TV is on")
            .setContentText("screen_off".equals(state) ? "Screen off · sound on" : "Tap to open the remote")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, "Vol −", TvWidgetProvider.commandIntent(ctx, "VOLDOWN", 301))
            .addAction(0, "Vol +", TvWidgetProvider.commandIntent(ctx, "VOLUP", 302))
            // Android shows at most 3 buttons on a notification.
            .addAction(0, "Turn off TV", TvWidgetProvider.commandIntent(ctx, "POWER", 304));
        nm.notify(ID, b.build());
    }
}
