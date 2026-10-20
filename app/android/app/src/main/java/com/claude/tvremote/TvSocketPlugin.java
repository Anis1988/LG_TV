package com.claude.tvremote;

import android.content.Context;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Gives the app's web page sockets to the TV, Wake-on-LAN, a Wi-Fi scan and hardware volume keys. */
@CapacitorPlugin(name = "TvSocket")
public class TvSocketPlugin extends Plugin {
    static volatile TvSocketPlugin instance;

    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean volumeKeys = false;

    @Override
    public void load() {
        instance = this;
    }

    /** Called by MainActivity; returns true if the key press was sent to the TV instead. */
    boolean onVolumeKey(boolean up, boolean pressed) {
        if (!volumeKeys) return false;
        if (pressed) {
            JSObject data = new JSObject();
            data.put("key", up ? "volumeUp" : "volumeDown");
            notifyListeners("hwkey", data);
        }
        return true;
    }

    @PluginMethod
    public void setVolumeKeys(PluginCall call) {
        volumeKeys = call.getBoolean("enabled", false);
        call.resolve();
    }

    /** Saves TV address, pairing key and MAC so the widget can use them. */
    @PluginMethod
    public void saveConfig(PluginCall call) {
        getContext().getSharedPreferences(TvClient.PREFS, Context.MODE_PRIVATE).edit()
            .putString("ip", call.getString("ip", ""))
            .putString("key", call.getString("key", ""))
            .putString("mac", call.getString("mac", ""))
            .putString("manifest", call.getString("manifest", "{}"))
            .apply();
        call.resolve();
    }

    @PluginMethod
    public void open(PluginCall call) {
        String id = call.getString("id");
        String url = call.getString("url");
        if (id == null || url == null || !TvNet.isLanUrl(url)) {
            call.reject("Only TVs on your home network are allowed");
            return;
        }
        WebSocket old = sockets.remove(id);
        if (old != null) old.cancel();

        TvNet.client().newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            private boolean opened = false;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                opened = true;
                sockets.put(id, ws);
                call.resolve();
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (sockets.get(id) != ws) return;
                JSObject data = new JSObject();
                data.put("id", id);
                data.put("data", text);
                notifyListeners("message", data);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
                closed(ws);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if (!opened) call.reject("Could not reach the TV");
                else closed(ws);
            }

            // Only report sockets that are still current, not ones we replaced.
            private void closed(WebSocket ws) {
                if (!sockets.remove(id, ws)) return;
                JSObject data = new JSObject();
                data.put("id", id);
                notifyListeners("closed", data);
            }
        });
    }

    @PluginMethod
    public void send(PluginCall call) {
        WebSocket ws = sockets.get(call.getString("id", ""));
        if (ws == null || !ws.send(call.getString("data", ""))) {
            call.reject("Not connected");
            return;
        }
        call.resolve();
    }

    @PluginMethod
    public void close(PluginCall call) {
        WebSocket ws = sockets.remove(call.getString("id", ""));
        if (ws != null) ws.close(1000, null);
        call.resolve();
    }

    @PluginMethod
    public void fetchImage(PluginCall call) {
        String url = call.getString("url");
        if (url == null || !TvNet.isLanUrl(url)) {
            call.reject("Only TV images are allowed");
            return;
        }
        pool.execute(() -> {
            try (Response res = TvNet.client().newCall(new Request.Builder().url(url).build()).execute()) {
                if (!res.isSuccessful() || res.body() == null) throw new Exception("HTTP " + res.code());
                String type = res.header("Content-Type", "image/png");
                String b64 = Base64.encodeToString(res.body().bytes(), Base64.NO_WRAP);
                JSObject out = new JSObject();
                out.put("dataUrl", "data:" + type + ";base64," + b64);
                call.resolve(out);
            } catch (Exception e) {
                call.reject("Image failed: " + e.getMessage());
            }
        });
    }

    /** Wake-on-LAN magic packet. */
    @PluginMethod
    public void wake(PluginCall call) {
        String mac = call.getString("mac", "");
        String broadcast = call.getString("broadcast", "255.255.255.255");
        pool.execute(() -> {
            try {
                TvNet.wake(mac, broadcast);
                call.resolve();
            } catch (Exception e) {
                call.reject("Wake failed: " + e.getMessage());
            }
        });
    }

    /** First three parts of this phone's Wi-Fi address, e.g. "192.168.50". */
    @PluginMethod
    public void localPrefix(PluginCall call) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    String ip = addr.getHostAddress();
                    if (addr instanceof Inet4Address && TvNet.isPrivate(ip)) {
                        JSObject out = new JSObject();
                        out.put("prefix", ip.substring(0, ip.lastIndexOf('.')));
                        call.resolve(out);
                        return;
                    }
                }
            }
            call.reject("Not on Wi-Fi");
        } catch (Exception e) {
            call.reject("Not on Wi-Fi");
        }
    }

    /** Finds devices on the subnet with the webOS port open. */
    @PluginMethod
    public void scan(PluginCall call) {
        String prefix = call.getString("prefix", "");
        int port = call.getInt("port", 3001);
        if (!TvNet.isPrivate(prefix + ".1")) {
            call.reject("Not on a home network");
            return;
        }
        pool.execute(() -> {
            ExecutorService probes = Executors.newFixedThreadPool(64);
            List<String> hosts = Collections.synchronizedList(new ArrayList<>());
            for (int i = 1; i < 255; i++) {
                String host = prefix + "." + i;
                probes.execute(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), 700);
                        hosts.add(host);
                    } catch (Exception ignored) {}
                });
            }
            probes.shutdown();
            try {
                probes.awaitTermination(15, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            JSObject out = new JSObject();
            out.put("hosts", new JSArray(new ArrayList<>(hosts)));
            call.resolve(out);
        });
    }
}
