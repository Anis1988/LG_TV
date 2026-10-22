package com.claude.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarms are cleared by a phone restart or an app update; set them up again. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        SleepTimer.scheduleAutoOff(ctx);
        SleepTimer.scheduleReminder(ctx);
        SleepTimer.scheduleNightStart(ctx);
        SleepTimer.scheduleRefresh(ctx);
        long sleepAt = SleepTimer.endsAt(ctx);
        if (sleepAt > 0) SleepTimer.set(ctx, (int) Math.max(1, (sleepAt - System.currentTimeMillis()) / 60_000));
    }
}
