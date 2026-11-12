package com.ninjaball.game;

import android.app.Activity;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class Bridge {
    private final Context context;
    private final WebView web;
    private final LanDiscovery discovery = new LanDiscovery();
    private WsServer server;

    public Bridge(Context context, WebView web) {
        this.context = context.getApplicationContext();
        this.web = web;
    }

    private void emit(final String event, final String payload) {
        if (web == null) return;
        final String e = JSONObject.quote(event);
        final String p = JSONObject.quote(payload == null ? "" : payload);
        web.post(() -> web.evaluateJavascript(
                "if(window.__nn)window.__nn(" + e + "," + p + ");", null));
    }

    @JavascriptInterface
    public synchronized String startServer(int port) {
        try {
            stopServer();
            server = new WsServer(port, new WsServer.Listener() {
                @Override public void onOpen() { emit("client_open", ""); }
                @Override public void onText(String text) { emit("client_msg", text); }
                @Override public void onClose() { emit("client_close", ""); }
                @Override public void onError(String message) { emit("error", message); }
            });
            server.start();
            JSONArray ips = new JSONArray();
            List<String[]> localIps = LanDiscovery.localIps();
            for (String[] item : localIps) ips.put(item[1]);
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("port", port);
            result.put("ips", ips);
            return result.toString();
        } catch (Exception ex) {
            JSONObject result = new JSONObject();
            try { result.put("ok", false); result.put("err", ex.getMessage() == null ? "Server error" : ex.getMessage()); }
            catch (Exception ignored) { }
            return result.toString();
        }
    }

    @JavascriptInterface
    public synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @JavascriptInterface
    public void send(String text) {
        WsServer s = server;
        if (s != null) s.send(text);
    }

    @JavascriptInterface
    public void kick() {
        WsServer s = server;
        if (s != null) s.kick();
    }

    @JavascriptInterface
    public void startBeacon(String name) {
        discovery.startBeacon(name, 8765);
    }

    @JavascriptInterface
    public void stopBeacon() { discovery.stopBeacon(); }

    @JavascriptInterface
    public void startDiscovery() {
        discovery.startListen((ip, port, name) -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("ip", ip);
                obj.put("port", port);
                obj.put("name", name);
                emit("found", obj.toString());
            } catch (Exception ignored) { }
        });
    }

    @JavascriptInterface
    public void stopDiscovery() { discovery.stopListen(); }

    public void shutdown() {
        stopBeacon();
        stopDiscovery();
        stopServer();
    }
}
