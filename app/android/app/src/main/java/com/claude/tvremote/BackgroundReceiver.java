package com.claude.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires the sleep timer, the daily auto turn-off and the periodic on/off check
 * (not exported; only our own alarms reach it).
 */
public class BackgroundReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!SleepTimer.ACTION_SLEEP.equals(action) && !SleepTimer.ACTION_REFRESH.equals(action)
            && !SleepTimer.ACTION_AUTO_OFF.equals(action) && !SleepTimer.ACTION_REMINDER.equals(action)
            && !SleepTimer.ACTION_NIGHT_START.equals(action)) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            TvClient tv = TvClient.get(ctx);
            if (SleepTimer.ACTION_SLEEP.equals(action)) {
                SleepTimer.clearFired(ctx);
                TvWidgetProvider.showState(ctx, turnOff(tv), null);
            } else if (SleepTimer.ACTION_AUTO_OFF.equals(action)) {
                // Only act if the TV is actually on (or screen-off); if it's off, leave it alone.
                String state = tv.powerState();
                if ("on".equals(state) || "screen_off".equals(state)) state = turnOff(tv);
                Log.i("TvAutoOff", "Auto turn-off ran, TV now " + state);
                TvWidgetProvider.showState(ctx, state, null);
                SleepTimer.scheduleAutoOff(ctx);
            } else if (SleepTimer.ACTION_REMINDER.equals(action)) {
                String state = tv.powerState();
                if ("on".equals(state) || "screen_off".equals(state)) LockControls.bedtimeReminder(ctx);
                TvWidgetProvider.showState(ctx, state, null);
                SleepTimer.scheduleReminder(ctx);
            } else {
                // Periodic check (and the night-window start): refresh status, keep volume under the night limit.
                String state = tv.powerState();
                if ("on".equals(state) || "screen_off".equals(state)) NightVolume.enforce(ctx, tv);
                TvWidgetProvider.showState(ctx, state, null);
                if (SleepTimer.ACTION_NIGHT_START.equals(action)) SleepTimer.scheduleNightStart(ctx);
            }
            result.finish();
        }).start();
    }

    private static String turnOff(TvClient tv) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (tv.connect(6000)) {
                    tv.request("ssap://system/turnOff", null);
                    tv.disconnect();
                    return "off";
                }
                return tv.stateAfterFailure();
            } catch (Exception e) {
                Log.w("TvBackground", "Turn off failed: " + e.getMessage());
                tv.disconnect();
            }
        }
        return tv.powerState();
    }
}
