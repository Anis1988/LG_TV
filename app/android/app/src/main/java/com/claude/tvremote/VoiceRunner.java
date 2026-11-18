package com.claude.tvremote;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs a spoken command on the TV with the native client, so it works from the
 * always-listening service with the app closed. Returns {ok, message}.
 */
final class VoiceRunner {
    private VoiceRunner() {}

    static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static Result ok(String m) { return new Result(true, m); }
    private static Result fail(String m) { return new Result(false, m); }

    /** Call from a background thread: talks to the TV. */
    static Result run(Context ctx, String spoken) {
        VoiceParser.Cmd c = VoiceParser.parse(spoken);
        TvClient tv = TvClient.get(ctx);
        try {
            switch (c.type) {
                case "NONE":
                    return fail("Say a command, like “turn off” or “volume 20”");
                case "POWER_OFF":
                    if (!tv.connect(2500)) return ok("The TV is already off");
                    tv.request("ssap://system/turnOff", null);
                    tv.disconnect();
                    TvWidgetProvider.showState(ctx, "off", null);
                    return ok("TV turned off");
                case "POWER_ON": {
                    if (tv.connect(2000)) return ok("The TV is already on");
                    tv.wake();
                    for (int i = 0; i < 6; i++) {
                        Thread.sleep(2500);
                        if (tv.connect(1500)) {
                            TvWidgetProvider.showState(ctx, "on", null);
                            tv.openStartApp(ctx);
                            return ok("TV is on");
                        }
                    }
                    return ok("Waking the TV…");
                }
                case "SCREEN_OFF":
                    tv.request("ssap://com.webos.service.tvpower/power/turnOffScreen", new JSONObject().put("standbyMode", "active"));
                    return ok("Screen off, sound stays on");
                case "VOL_UP":
                case "VOL_DOWN":
                case "VOL_SET": {
                    int now = volume(tv);
                    int want = "VOL_SET".equals(c.type) ? c.num
                        : now + ("VOL_UP".equals(c.type) ? c.num : -c.num);
                    want = Math.max(0, Math.min(100, want));
                    boolean limited = false;
                    if (NightVolume.active(ctx) && want > NightVolume.max(ctx)) {
                        want = NightVolume.max(ctx);
                        limited = true;
                    }
                    tv.request("ssap://audio/setVolume", new JSONObject().put("volume", want));
                    return ok(limited ? "Night limit · volume " + want : "Volume " + want);
                }
                case "MUTE":
                    tv.request("ssap://audio/setMute", new JSONObject().put("mute", true));
                    return ok("Muted");
                case "UNMUTE":
                    tv.request("ssap://audio/setMute", new JSONObject().put("mute", false));
                    return ok("Sound on");
                case "PLAY":
                    tv.request("ssap://media.controls/play", null);
                    return ok("Playing");
                case "PAUSE":
                    tv.request("ssap://media.controls/pause", null);
                    return ok("Paused");
                case "STOP":
                    tv.request("ssap://media.controls/stop", null);
                    return ok("Stopped");
                case "REWIND":
                    tv.request("ssap://media.controls/rewind", null);
                    return ok("Rewinding");
                case "FFWD":
                    tv.request("ssap://media.controls/fastForward", null);
                    return ok("Fast forward");
                case "CH_UP":
                    tv.request("ssap://tv/channelUp", null);
                    return ok("Channel up");
                case "CH_DOWN":
                    tv.request("ssap://tv/channelDown", null);
                    return ok("Channel down");
                case "CH_SET":
                    tv.request("ssap://tv/openChannel", new JSONObject().put("channelNumber", String.valueOf(c.num)));
                    return ok("Channel " + c.num);
                case "INPUT":
                    tv.request("ssap://tv/switchInput", new JSONObject().put("inputId", "HDMI_" + c.num));
                    return ok("HDMI " + c.num);
                case "BUTTON": {
                    int times = Math.max(1, c.num);
                    for (int i = 0; i < times; i++) {
                        if (i > 0) Thread.sleep(180);
                        tv.button(c.text);
                    }
                    return ok(buttonLabel(c.text) + (times > 1 ? " ×" + times : ""));
                }
                case "SLEEP": {
                    long at = SleepTimer.set(ctx, c.num);
                    return ok("TV will turn off at " + android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(at)));
                }
                case "SLEEP_CANCEL":
                    SleepTimer.cancel(ctx);
                    return ok("Sleep timer off");
                case "YOUTUBE": {
                    String id = "youtube.leanback.v4";
                    try { tv.request("ssap://system.launcher/close", new JSONObject().put("id", id)); } catch (Exception ignored) {}
                    Thread.sleep(2000);
                    tv.request("ssap://system.launcher/launch", new JSONObject().put("id", id).put("params",
                        new JSONObject().put("contentTarget", "https://www.youtube.com/tv#/search?q=" + java.net.URLEncoder.encode(c.text, "UTF-8"))));
                    return ok("Searching YouTube for “" + c.text + "”");
                }
                case "APP":
                case "APP_GUESS":
                    return launchApp(tv, c.text, "APP".equals(c.type) ? 0.6 : 0.75, spoken);
                default:
                    return fail("Didn't catch that");
            }
        } catch (Exception e) {
            String m = e.getMessage();
            return fail(m == null || m.isEmpty() ? "Couldn't reach the TV" : m);
        }
    }

    private static int volume(TvClient tv) throws Exception {
        JSONObject v = tv.request("ssap://audio/getVolume", null);
        JSONObject s = v.optJSONObject("volumeStatus");
        return s != null ? s.optInt("volume") : v.optInt("volume");
    }

    private static String buttonLabel(String b) {
        switch (b) {
            case "ENTER": return "OK";
            case "HOME": return "Home";
            case "BACK": return "Back";
            case "MENU": return "Settings";
            case "EXIT": return "Exit";
            default: return b.charAt(0) + b.substring(1).toLowerCase(java.util.Locale.US);
        }
    }

    private static Result launchApp(TvClient tv, String name, double threshold, String spoken) throws Exception {
        JSONArray apps = tv.request("ssap://com.webos.applicationManager/listLaunchPoints", null).optJSONArray("launchPoints");
        String bestId = null, bestTitle = null;
        double best = 0;
        for (int i = 0; apps != null && i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a == null) continue;
            double s = VoiceParser.appScore(name, a.optString("title"));
            if (s > best) { best = s; bestId = a.optString("id"); bestTitle = a.optString("title"); }
        }
        if (bestId == null || best < threshold) {
            return fail(threshold > 0.7 ? "Didn't catch that: “" + spoken.trim() + "”" : "No app called “" + name + "” on the TV");
        }
        tv.request("ssap://system.launcher/launch", new JSONObject().put("id", bestId));
        return ok("Opening " + bestTitle);
    }
}
