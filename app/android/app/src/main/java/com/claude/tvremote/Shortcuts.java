package com.claude.tvremote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.widget.Toast;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Long-press shortcuts on the app icon: TV on/off, sleep 30 min, and the top favorite apps.
 * The activity is invisible: it runs the action in the background and closes right away.
 */
public class Shortcuts extends Activity {
    static final String EXTRA_CMD = "shortcutCmd";
    static final String EXTRA_APP = "shortcutApp";

    /** Rebuild the dynamic shortcuts; favorites is a JSON array of {id, title, icon (data URL)}. */
    static void update(Context ctx, JSONArray favorites) {
        List<ShortcutInfoCompat> list = new ArrayList<>();
        list.add(make(ctx, "power", "Turn TV on/off", "POWER", null, IconCompat.createWithResource(ctx, R.drawable.ic_qs_tv)));
        list.add(make(ctx, "sleep", "Sleep in 30 min", "SLEEP30", null, IconCompat.createWithResource(ctx, R.drawable.wi_sleep_on)));
        for (int i = 0; favorites != null && i < favorites.length() && list.size() < 4; i++) {
            JSONObject f = favorites.optJSONObject(i);
            if (f == null || f.optString("id").isEmpty()) continue;
            IconCompat icon = fromDataUrl(f.optString("icon"));
            if (icon == null) icon = IconCompat.createWithResource(ctx, R.drawable.ic_qs_tv);
            list.add(make(ctx, "app_" + i, f.optString("title", "App"), "LAUNCH", f.optString("id"), icon));
        }
        ShortcutManagerCompat.setDynamicShortcuts(ctx, list);
    }

    private static ShortcutInfoCompat make(Context ctx, String id, String label, String cmd, String app, IconCompat icon) {
        Intent intent = new Intent(ctx, Shortcuts.class)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_CMD, cmd)
            .putExtra("token", TvWidgetProvider.token(ctx));
        if (app != null) intent.putExtra(EXTRA_APP, app);
        return new ShortcutInfoCompat.Builder(ctx, id).setShortLabel(label).setIcon(icon).setIntent(intent).build();
    }

    private static IconCompat fromDataUrl(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (!dataUrl.startsWith("data:") || comma < 0) return null;
            byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            return bmp == null ? null : IconCompat.createWithBitmap(bmp);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        String cmd = i.getStringExtra(EXTRA_CMD);
        if (cmd != null && TvWidgetProvider.token(this).equals(i.getStringExtra("token"))) run(getApplicationContext(), cmd, i.getStringExtra(EXTRA_APP));
        finish();
    }

    private static void run(Context ctx, String cmd, String app) {
        switch (cmd) {
            case "SLEEP30":
                long at = SleepTimer.set(ctx, 30);
                Toast.makeText(ctx, "TV will turn off at " + android.text.format.DateFormat.getTimeFormat(ctx).format(new java.util.Date(at)), Toast.LENGTH_SHORT).show();
                return;
            case "POWER":
                Toast.makeText(ctx, "TV: working…", Toast.LENGTH_SHORT).show();
                ctx.sendBroadcast(new Intent(ctx, TvWidgetProvider.class).setAction(TvWidgetProvider.ACTION)
                    .putExtra("cmd", "POWER").putExtra("token", TvWidgetProvider.token(ctx)));
                return;
            case "LAUNCH":
                if (app == null) return;
                Toast.makeText(ctx, "Opening on TV…", Toast.LENGTH_SHORT).show();
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        TvClient.get(ctx).request("ssap://system.launcher/launch", new JSONObject().put("id", app));
                    } catch (Exception ignored) {}
                });
                return;
            default:
        }
    }
}
