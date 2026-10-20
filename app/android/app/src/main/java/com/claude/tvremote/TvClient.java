package com.claude.tvremote;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Native webOS client used by the home-screen widget, which can't rely on the
 * app's web view being alive. Reads the TV address and pairing key that the app
 * saved, keeps the connection for a minute so repeated taps are quick.
 */
final class TvClient {
    static final String PREFS = "tv";

    private static TvClient instance;

    private final SharedPreferences prefs;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private ScheduledFuture<?> idleClose;
    private volatile WebSocket main;
    private volatile WebSocket pointer;
    private volatile boolean ready;
    // True if the last connect attempt reached the TV's socket (even if it never registered).
    private volatile boolean lastOpened;

    static synchronized TvClient get(Context ctx) {
        if (instance == null) instance = new TvClient(ctx.getApplicationContext());
        return instance;
    }

    private TvClient(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True once registered with the TV. A TV in standby accepts the socket but never registers. */
    synchronized boolean connect(long timeoutMs) {
        keepAlive();
        if (ready && main != null) return true;
        String ip = prefs.getString("ip", "");
        if (!TvNet.isPrivate(ip)) return false;
        String key = prefs.getString("key", "");
        String manifest = prefs.getString("manifest", "{}");
        CountDownLatch done = new CountDownLatch(1);
        lastOpened = false;

        WebSocket ws = TvNet.client().newWebSocket(
            new Request.Builder().url("wss://" + ip + ":3001").build(),
            new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    lastOpened = true;
                    try {
                        JSONObject payload = new JSONObject()
                            .put("forcePairing", false)
                            .put("pairingType", "PROMPT")
                            .put("manifest", new JSONObject(manifest));
                        if (!key.isEmpty()) payload.put("client-key", key);
                        ws.send(new JSONObject().put("id", "register").put("type", "register")
                            .put("payload", payload).toString());
                    } catch (JSONException e) {
                        ws.cancel();
                    }
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    try {
                        JSONObject msg = new JSONObject(text);
                        String id = msg.optString("id");
                        String type = msg.optString("type");
                        JSONObject payload = msg.optJSONObject("payload");
                        if ("register".equals(id)) {
                            if ("registered".equals(type)) {
                                ready = true;
                                String newKey = payload == null ? "" : payload.optString("client-key");
                                if (!newKey.isEmpty() && !newKey.equals(key)) prefs.edit().putString("key", newKey).apply();
                                done.countDown();
                            } else if ("error".equals(type)) {
                                done.countDown();
                            }
                            return;
                        }
                        CompletableFuture<JSONObject> f = pending.remove(id);
                        if (f == null) return;
                        if ("error".equals(type)) f.completeExceptionally(new Exception(msg.optString("error")));
                        else f.complete(payload == null ? new JSONObject() : payload);
                    } catch (JSONException ignored) {}
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(1000, null);
                    dropped(ws);
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    dropped(ws);
                    done.countDown();
                }
            });
        main = ws;
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        if (!ready) {
            ws.cancel();
            main = null;
        }
        return ready;
    }

    private void dropped(WebSocket ws) {
        if (main != ws) return;
        main = null;
        ready = false;
        WebSocket p = pointer;
        pointer = null;
        if (p != null) p.cancel();
    }

    synchronized void disconnect() {
        WebSocket m = main;
        ready = false;
        main = null;
        if (m != null) m.close(1000, null);
        WebSocket p = pointer;
        pointer = null;
        if (p != null) p.cancel();
    }

    private synchronized void keepAlive() {
        if (idleClose != null) idleClose.cancel(false);
        idleClose = timer.schedule(this::disconnect, 60, TimeUnit.SECONDS);
    }

    JSONObject request(String uri, JSONObject payload) throws Exception {
        if (!connect(3000)) throw new Exception("TV is off or not on this Wi-Fi");
        String id = "w" + nextId.incrementAndGet();
        CompletableFuture<JSONObject> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            WebSocket ws = main;
            if (ws == null || !ws.send(new JSONObject().put("id", id).put("type", "request").put("uri", uri)
                .put("payload", payload == null ? new JSONObject() : payload).toString())) {
                throw new Exception("Lost connection to TV");
            }
            return f.get(5, TimeUnit.SECONDS);
        } finally {
            pending.remove(id);
        }
    }

    /** Remote buttons (UP, DOWN, ENTER, BACK, HOME...) go through the pointer socket. */
    void button(String name) throws Exception {
        if (pointer == null) {
            String path = request("ssap://com.webos.service.networkinput/getPointerInputSocket", null)
                .getString("socketPath");
            if (!TvNet.isLanUrl(path)) throw new Exception("Unexpected TV response");
            CountDownLatch open = new CountDownLatch(1);
            AtomicBoolean ok = new AtomicBoolean(false);
            WebSocket p = TvNet.client().newWebSocket(new Request.Builder().url(path).build(), new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    ok.set(true);
                    open.countDown();
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    if (pointer == ws) pointer = null;
                    open.countDown();
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    if (pointer == ws) pointer = null;
                }
            });
            open.await(3, TimeUnit.SECONDS);
            if (!ok.get()) {
                p.cancel();
                throw new Exception("Button channel failed");
            }
            pointer = p;
        }
        WebSocket p = pointer;
        if (p == null || !p.send("type:button\nname:" + name + "\n\n")) {
            pointer = null;
            throw new Exception("Button channel failed");
        }
    }

    /**
     * "on", "screen_off", "off" or "unreachable". A TV in standby keeps its socket
     * open (with "Turn on via Wi-Fi") but never registers, which is how "off" is told apart.
     */
    String powerState() {
        // Two tries: a kept-alive connection can outlive the TV turning off, so if it
        // doesn't answer, drop it and check again with a fresh one.
        for (int attempt = 0; attempt < 2; attempt++) {
            if (!connect(3500)) return lastOpened ? "off" : "unreachable";
            try {
                String s = request("ssap://com.webos.service.tvpower/power/getPowerState", null).optString("state", "");
                if (s.matches("(?i).*(screen ?off|active standby).*")) return "screen_off";
                if (s.matches("(?i).*(suspend|power ?off|standby).*")) {
                    disconnect();
                    return "off";
                }
                return "on";
            } catch (Exception e) {
                disconnect();
            }
        }
        return "unreachable";
    }

    /** Like powerState() but without a fresh connect; for use right after a failed command. */
    String stateAfterFailure() {
        return lastOpened ? "off" : "unreachable";
    }

    void wake() throws Exception {
        String ip = prefs.getString("ip", "");
        if (!TvNet.isPrivate(ip)) throw new Exception("Open the app to set up your TV");
        TvNet.wake(prefs.getString("mac", ""), TvNet.broadcastFor(ip));
    }
}
