package com.claude.tvremote;

import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Talks to the LG TV over its secure WebSocket. The TV uses a self-signed
 * certificate, so this client accepts any cert, but only for private LAN addresses.
 */
@CapacitorPlugin(name = "TvSocket")
public class TvSocketPlugin extends Plugin {
    private static final Pattern PRIVATE_IP =
        Pattern.compile("^(10\\.\\d+|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.\\d+\\.\\d+$");

    private OkHttpClient client;
    private final Map<String, WebSocket> sockets = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @Override
    public void load() {
        try {
            X509TrustManager trustAll = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] { trustAll }, new SecureRandom());
            client = new OkHttpClient.Builder()
                .sslSocketFactory(ssl.getSocketFactory(), trustAll)
                .hostnameVerifier((host, session) -> isPrivate(host))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isPrivate(String host) {
        return host != null && PRIVATE_IP.matcher(host).matches();
    }

    private static boolean isLanUrl(String url) {
        try {
            return isPrivate(new URI(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void open(PluginCall call) {
        String id = call.getString("id");
        String url = call.getString("url");
        if (id == null || url == null || !isLanUrl(url)) {
            call.reject("Only TVs on your home network are allowed");
            return;
        }
        WebSocket old = sockets.remove(id);
        if (old != null) old.cancel();

        client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
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
        if (url == null || !isLanUrl(url)) {
            call.reject("Only TV images are allowed");
            return;
        }
        pool.execute(() -> {
            try (Response res = client.newCall(new Request.Builder().url(url).build()).execute()) {
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
        String mac = call.getString("mac", "").replaceAll("[^0-9a-fA-F]", "");
        String broadcast = call.getString("broadcast", "255.255.255.255");
        if (mac.length() != 12) {
            call.reject("Bad MAC address");
            return;
        }
        pool.execute(() -> {
            try (DatagramSocket sock = new DatagramSocket()) {
                byte[] packet = new byte[102];
                for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
                for (int r = 0; r < 16; r++) {
                    for (int i = 0; i < 6; i++) {
                        packet[6 + r * 6 + i] = (byte) Integer.parseInt(mac.substring(i * 2, i * 2 + 2), 16);
                    }
                }
                sock.setBroadcast(true);
                for (String target : new String[] { broadcast, "255.255.255.255" }) {
                    for (int n = 0; n < 3; n++) {
                        sock.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(target), 9));
                    }
                }
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
                    if (addr instanceof Inet4Address && isPrivate(ip)) {
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
        if (!isPrivate(prefix + ".1")) {
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
