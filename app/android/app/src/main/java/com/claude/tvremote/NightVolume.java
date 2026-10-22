package com.claude.tvremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.util.Calendar;
import org.json.JSONObject;

/** Night volume limit: between two times of day the TV volume is kept at or below a maximum. */
final class NightVolume {
    private NightVolume() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context ctx) { return prefs(ctx).getBoolean("nightOn", false); }
    static int max(Context ctx) { return prefs(ctx).getInt("nightMax", 15); }
    /** Minutes after midnight; default 22:00 to 07:00. */
    static int start(Context ctx) { return prefs(ctx).getInt("nightStart", 22 * 60); }
    static int end(Context ctx) { return prefs(ctx).getInt("nightEnd", 7 * 60); }

    static void save(Context ctx, boolean on, int max, int start, int end) {
        prefs(ctx).edit().putBoolean("nightOn", on).putInt("nightMax", max)
            .putInt("nightStart", start).putInt("nightEnd", end).apply();
        SleepTimer.scheduleNightStart(ctx);
    }

    /** True right now if the limit is on and we're inside the window (which may cross midnight). */
    static boolean active(Context ctx) {
        if (!enabled(ctx)) return false;
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int s = start(ctx), e = end(ctx);
        return s <= e ? (now >= s && now < e) : (now >= s || now < e);
    }

    /** If the limit applies and the TV is louder, turn it down to the limit. Returns the volume after. */
    static int enforce(Context ctx, TvClient tv) {
        if (!active(ctx)) return -1;
        try {
            JSONObject v = tv.request("ssap://audio/getVolume", null);
            JSONObject s = v.optJSONObject("volumeStatus");
            int vol = s != null ? s.optInt("volume") : v.optInt("volume");
            int limit = max(ctx);
            if (vol > limit) {
                tv.request("ssap://audio/setVolume", new JSONObject().put("volume", limit));
                Log.i("TvNight", "Volume " + vol + " lowered to night limit " + limit);
                return limit;
            }
            return vol;
        } catch (Exception e) {
            return -1;
        }
    }
}
