package com.claude.tvremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Fires the sleep timer and the periodic on/off check (not exported; only our own alarms reach it). */
public class BackgroundReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!SleepTimer.ACTION_SLEEP.equals(action) && !SleepTimer.ACTION_REFRESH.equals(action)) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            TvClient tv = TvClient.get(ctx);
            if (SleepTimer.ACTION_SLEEP.equals(action)) {
                SleepTimer.clearFired(ctx);
                String state = "off";
                try {
                    if (tv.connect(6000)) {
                        tv.request("ssap://system/turnOff", null);
                        tv.disconnect();
                    } else {
                        state = tv.stateAfterFailure();
                    }
                } catch (Exception e) {
                    Log.w("TvSleep", "Turn off failed: " + e.getMessage());
                }
                TvWidgetProvider.showState(ctx, state, null);
            } else {
                TvWidgetProvider.showState(ctx, tv.powerState(), null);
            }
            result.finish();
        }).start();
    }
}
