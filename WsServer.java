package com.ninjaball.game;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Small single-client WebSocket server for offline LAN multiplayer. */
public class WsServer {
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onClose();
        void onError(String message);
    }

    private final int port;
    private final Listener listener;
    private volatile boolean running;
    private volatile Socket client;
    private ServerSocket serverSocket;
    private Thread thread;
    private final Object writeLock = new Object();

    public WsServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port), 1);
        running = true;
        thread = new Thread(this::acceptLoop, "NinjaBall-WSServer");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop() {
        while (running) {
            Socket s = null;
            try {
                s = serverSocket.accept();
                if (!running) { s.close(); break; }
                if (client != null && !client.isClosed()) { s.close(); continue; }
                client = s;
                s.setTcpNoDelay(true);
                handshake(s);
                listener.onOpen();
                readFrames(s);
            } catch (Exception e) {
                if (running) listener.onError(e.getMessage() == null ? "Connection error" : e.getMessage());
            } finally {
                if (s != null) try { s.close(); } catch (Exception ignored) { }
                if (client == s) client = null;
                if (running) listener.onClose();
            }
        }
    }

    private void handshake(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) != -1) {
            b.write(c);
            byte[] a = b.toByteArray();
            int n = a.length;
            if (n >= 4 && a[n-4]=='\r' && a[n-3]=='\n' && a[n-2]=='\r' && a[n-1]=='\n') break;
            if (n > 16384) throw new IOException("WebSocket header too large");
        }
        String req = b.toString("ISO-8859-1");
        String key = null;
        for (String line : req.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("Sec-WebSocket-Key"))
                key = line.substring(i+1).trim();
        }
        if (key == null) throw new IOException("Invalid WebSocket handshake");
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private void readFrames(Socket s) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        while (running && !s.isClosed()) {
            int a;
            try { a = in.readUnsignedByte(); } catch (EOFException e) { break; }
            int b = in.readUnsignedByte();
            int opcode = a & 15;
            boolean masked = (b & 128) != 0;
            long len = b & 127;
            if (len == 126) len = in.readUnsignedShort();
            else if (len == 127) len = in.readLong();
            if (len < 0 || len > 1024 * 1024) throw new IOException("Frame too large");
            byte[] mask = new byte[4];
            if (masked) in.readFully(mask);
            byte[] payload = new byte[(int)len];
            in.readFully(payload);
            if (masked) for (int i=0;i<payload.length;i++) payload[i] ^= mask[i%4];
            if (opcode == 8) break;
            if (opcode == 9) { writeFrame(s, 10, payload); continue; }
            if (opcode == 1) listener.onText(new String(payload, StandardCharsets.UTF_8));
        }
    }

    public void send(String text) {
        Socket s = client;
        if (s == null || s.isClosed()) return;
        try { writeFrame(s, 1, text.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { kick(); }
    }

    private void writeFrame(Socket s, int opcode, byte[] data) throws IOException {
        synchronized (writeLock) {
            OutputStream out = s.getOutputStream();
            out.write(0x80 | opcode);
            if (data.length < 126) out.write(data.length);
            else if (data.length <= 65535) { out.write(126); out.write((data.length >>> 8) & 255); out.write(data.length & 255); }
            else { out.write(127); for (int i=7;i>=0;i--) out.write((long)data.length >>> (8*i) > 0 ? (data.length >>> (8*i)) & 255 : 0); }
            out.write(data);
            out.flush();
        }
    }

    public void kick() {
        Socket s = client;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    public synchronized void stop() {
        running = false;
        kick();
        if (serverSocket != null) try { serverSocket.close(); } catch (Exception ignored) { }
        serverSocket = null;
    }
}
