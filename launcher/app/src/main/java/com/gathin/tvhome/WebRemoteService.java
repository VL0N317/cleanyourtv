package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebRemoteService extends Service {
    private static final String TAG = "TvHome.WebRemote";
    private static final int PORT = 8080;
    private static final String CHANNEL_ID = "tvhome_web_remote";
    private static final int NOTIFICATION_ID = 1001;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private byte[] cachedHtml;
    private volatile boolean running = false;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, WebRemoteService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WebRemoteService", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground();
        loadHtml();
        startServer();
        if (threadPool != null) {
            threadPool.execute(LinuxInputClient::init);
        } else {
            new Thread(LinuxInputClient::init).start();
        }
    }

    private void promoteToForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "TV Remote Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Fast local Wi-Fi TV remote service");
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.createNotificationChannel(channel);
                }
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID)
                    : new Notification.Builder(this);

            Notification notification = builder
                    .setContentTitle("Hivendor Remote Server")
                    .setContentText("Listening on port 8080")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();

            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to promote to foreground service", e);
        }
    }

    private void loadHtml() {
        try (InputStream is = getAssets().open("remote.html");
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            cachedHtml = baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load remote.html", e);
            cachedHtml = "<h1>Remote HTML not found</h1>".getBytes(StandardCharsets.UTF_8);
        }
    }

    private void sendKey(int keyCode) {
        if (keyCode == 3) {
            // Home key: direct intent to return to launcher immediately
            sendHome();
            return;
        }
        if (!LinuxInputClient.sendKey(keyCode)) {
            threadPool.execute(() -> AdbClient.execute("input keyevent " + keyCode));
        }
    }

    private void sendHome() {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            threadPool.execute(() -> AdbClient.execute("am start -a android.intent.action.MAIN -c android.intent.category.HOME"));
        }
    }

    private void sendText(String text) {
        threadPool.execute(() -> {
            String escaped = text.replace("'", "'\\''");
            AdbClient.execute("input text '" + escaped + "'");
        });
    }

    private void launchApp(String pkg) {
        try {
            Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) {
                intent = getPackageManager().getLaunchIntentForPackage(pkg);
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(intent);
                return;
            }
        } catch (Exception ignored) { }
        threadPool.execute(() -> AdbClient.execute("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1"));
    }

    private void startServer() {
        if (running && serverSocket != null && !serverSocket.isClosed()) return;
        running = true;
        if (threadPool == null || threadPool.isShutdown()) {
            threadPool = Executors.newCachedThreadPool();
        }
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"));
                Log.i(TAG, "Web Remote listening on 0.0.0.0:" + PORT);
                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        socket.setTcpNoDelay(true);
                        threadPool.execute(() -> handleClient(socket));
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "ServerSocket error", e);
            }
        }, "WebRemoteListener").start();
    }

    private void handleClient(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line = reader.readLine();
            if (line == null || line.isEmpty()) return;

            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/wallpaper")) {
                int contentLength = 0;
                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    if (headerLine.toLowerCase().startsWith("content-length:")) {
                        try {
                            contentLength = Integer.parseInt(headerLine.substring(15).trim());
                        } catch (Exception ignored) {}
                    }
                }
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int r = reader.read(buf, read, contentLength - read);
                        if (r == -1) break;
                        read += r;
                    }
                    String body = new String(buf, 0, read);
                    handleWallpaperUpload(body, out);
                    return;
                }
                sendJson(out, "{\"status\":\"error\",\"message\":\"empty body\"}");
                return;
            } else if ("/presets".equals(path)) {
                File dir = new File(getExternalFilesDir(null), "wallpapers");
                org.json.JSONArray arr = new org.json.JSONArray();
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg") || name.endsWith(".png"));
                    if (files != null) {
                        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                        for (File f : files) {
                            arr.put(f.getName());
                        }
                    }
                }
                sendJson(out, arr.toString());
                return;
            } else if ("/".equals(path) || "/index.html".equals(path)) {
                sendResponse(out, 200, "text/html; charset=utf-8", cachedHtml);
            } else if (path.startsWith("/key?")) {
                int key = parseQueryInt(path, "c", -1);
                if (key >= 0) {
                    sendKey(key);
                }
                sendJson(out, "{\"status\":\"ok\"}");
            } else if (path.startsWith("/text?")) {
                String text = parseQueryString(path, "q");
                if (text != null && !text.isEmpty()) {
                    sendText(text);
                }
                sendJson(out, "{\"status\":\"ok\"}");
            } else if (path.startsWith("/mouse?")) {
                int dx = parseQueryInt(path, "dx", 0);
                int dy = parseQueryInt(path, "dy", 0);
                int click = parseQueryInt(path, "click", 0);
                int scroll = parseQueryInt(path, "scroll", 0);
                if (click == 1) {
                    LinuxInputClient.sendMouseClick();
                } else if (scroll != 0) {
                    LinuxInputClient.sendMouseScroll(scroll);
                } else if (dx != 0 || dy != 0) {
                    LinuxInputClient.sendMouseMove(dx, dy);
                }
                sendJson(out, "{\"status\":\"ok\"}");
            } else if (path.startsWith("/launch?")) {
                String pkg = parseQueryString(path, "pkg");
                if (pkg != null && !pkg.isEmpty()) {
                    launchApp(pkg);
                }
                sendJson(out, "{\"status\":\"ok\"}");
            } else if ("/status".equals(path)) {
                sendJson(out, "{\"status\":\"connected\",\"device\":\"Hivendor 5G PRO\"}");
            } else {
                sendResponse(out, 404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private int parseQueryInt(String path, String key, int def) {
        String val = parseQueryString(path, key);
        if (val == null) return def;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private String parseQueryString(String path, String key) {
        int qIdx = path.indexOf('?');
        if (qIdx < 0) return null;
        String query = path.substring(qIdx + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                if (k.equals(key)) {
                    try {
                        return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    } catch (Exception ignored) {
                        return pair.substring(eq + 1);
                    }
                }
            }
        }
        return null;
    }

    private void sendJson(OutputStream out, String json) throws IOException {
        sendResponse(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void sendResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + status + " OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private void handleWallpaperUpload(String body, OutputStream out) {
        try {
            org.json.JSONObject json = new org.json.JSONObject(body);
            File dest = new File(getExternalFilesDir(null), "wallpaper.jpg");

            if (json.has("preset")) {
                String preset = json.getString("preset");
                File presetFile = new File(new File(getExternalFilesDir(null), "wallpapers"), preset);
                if (presetFile.exists()) {
                    copyFile(presetFile, dest);
                    MainActivity.updateWallpaper(dest);
                    sendJson(out, "{\"status\":\"ok\",\"type\":\"preset\"}");
                    return;
                }
            } else if (json.has("image")) {
                String b64 = json.getString("image");
                int idx = b64.indexOf(",");
                if (idx != -1) b64 = b64.substring(idx + 1);
                byte[] data = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(data);
                    fos.flush();
                }
                MainActivity.updateWallpaper(dest);
                sendJson(out, "{\"status\":\"ok\",\"type\":\"custom\"}");
                return;
            }
            sendJson(out, "{\"status\":\"error\",\"message\":\"invalid payload\"}");
        } catch (Exception e) {
            Log.e(TAG, "Wallpaper upload error", e);
            try {
                sendJson(out, "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}");
            } catch (IOException ignored) {}
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promoteToForeground();
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            if (threadPool != null) threadPool.shutdownNow();
        } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
