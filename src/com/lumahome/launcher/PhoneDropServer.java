package com.lumahome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ephemeral LAN-only HTTP server for Zero-Login QR Phone Wallpaper Drop.
 * Runs strictly while the TV QR blade is open and shuts down immediately on close/pause.
 */
final class PhoneDropServer {
    static final int MAX_GALLERY_ITEMS = 30;
    private static final int MAX_UPLOAD_BYTES = 15 * 1024 * 1024;
    private static final String TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";

    interface Listener {
        void onPhoneConnected();
        void onWallpaperReceived(String fileUri, int sessionCount);
        void onWallpaperSelected(String fileUri);
        void onWallpaperDeleted(String fileUri);
        void onSlideshowChanged(int minutes);
    }

    private final Context context;
    private final LauncherStore store;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final String token;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean phoneSeen = new AtomicBoolean(false);
    private final AtomicInteger sessionUploads = new AtomicInteger(0);
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private ServerSocket serverSocket;
    private String lanIp;
    private int boundPort;

    PhoneDropServer(Context context, LauncherStore store, Listener listener) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.listener = listener;
        this.token = randomToken(6);
    }

    boolean start() {
        lanIp = findLanIpv4();
        if (lanIp == null) return false;
        int[] candidatePorts = {8484, 8485, 8486, 0};
        for (int p : candidatePorts) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(p));
                serverSocket = ss;
                boundPort = ss.getLocalPort();
                break;
            } catch (IOException ignored) {}
        }
        if (serverSocket == null) return false;
        running.set(true);
        pool.execute(this::acceptLoop);
        return true;
    }

    void stop() {
        running.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
            serverSocket = null;
        }
        pool.shutdownNow();
    }

    boolean isRunning() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    String getUrl() {
        return "http://" + lanIp + ":" + boundPort + "/?t=" + token;
    }

    String getDisplayAddress() {
        return lanIp + ":" + boundPort;
    }

    static File wallpapersDir(Context context) {
        File dir = new File(context.getFilesDir(), "wallpapers");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static List<File> listSavedFiles(Context context, LauncherStore store) {
        File dir = wallpapersDir(context);
        List<String> uris = store.list("wallpaper_gallery");
        List<File> out = new ArrayList<>();
        List<String> cleaned = new ArrayList<>();
        boolean changed = false;
        for (String u : uris) {
            try {
                File f = new File(Uri.parse(u).getPath());
                if (f.exists() && f.isFile() && f.getParentFile() != null
                        && f.getParentFile().getCanonicalPath().equals(dir.getCanonicalPath())) {
                    out.add(f);
                    cleaned.add(f.toURI().toString());
                } else {
                    changed = true;
                }
            } catch (Exception e) {
                changed = true;
            }
        }
        if (changed) store.list("wallpaper_gallery", cleaned);
        return out;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket client = serverSocket.accept();
                client.setSoTimeout(15000);
                pool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (!running.get()) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             InputStream rawIn = new BufferedInputStream(s.getInputStream(), 16384);
             OutputStream rawOut = new BufferedOutputStream(s.getOutputStream(), 16384)) {

            String requestLine = readAsciiLine(rawIn);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String fullTarget = parts[1];

            int contentLength = 0;
            while (true) {
                String line = readAsciiLine(rawIn);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String hName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String hVal = line.substring(colon + 1).trim();
                    if ("content-length".equals(hName)) {
                        try { contentLength = Integer.parseInt(hVal); } catch (NumberFormatException ignored) {}
                    }
                }
            }

            String path = fullTarget;
            String query = "";
            int qIdx = fullTarget.indexOf('?');
            if (qIdx >= 0) {
                path = fullTarget.substring(0, qIdx);
                query = fullTarget.substring(qIdx + 1);
            }
            Map<String, String> params = parseQuery(query);

            if ("/favicon.ico".equals(path)) {
                sendBytes(rawOut, "204 No Content", "image/x-icon", new byte[0], null);
                return;
            }

            if (!token.equals(params.get("t"))) {
                byte[] msg = ("<!doctype html><html><body style='background:#0B101A;color:#fff;font-family:sans-serif;"
                        + "padding:32px;text-align:center'><h2>Session Expired</h2>"
                        + "<p style='color:#9aa4b2'>Please scan the current QR code shown on your Luma Home TV screen.</p>"
                        + "</body></html>").getBytes(StandardCharsets.UTF_8);
                sendBytes(rawOut, "403 Forbidden", "text/html; charset=utf-8", msg, null);
                return;
            }

            if (phoneSeen.compareAndSet(false, true)) {
                uiHandler.post(listener::onPhoneConnected);
            }

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                byte[] html = buildWebAppHtml().getBytes(StandardCharsets.UTF_8);
                sendBytes(rawOut, "200 OK", "text/html; charset=utf-8", html, "no-store");
                return;
            }

            if ("GET".equals(method) && "/api/list".equals(path)) {
                String json = buildGalleryJson();
                sendBytes(rawOut, "200 OK", "application/json; charset=utf-8",
                        json.getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }

            if ("GET".equals(method) && "/api/thumb".equals(path)) {
                File f = resolveSafeWallpaperFile(params.get("id"));
                if (f == null || !f.exists()) {
                    sendJsonError(rawOut, "404 Not Found", "Not found");
                    return;
                }
                byte[] bytes = readFileBytes(f);
                sendBytes(rawOut, "200 OK", "image/jpeg", bytes, "public, max-age=86400");
                return;
            }

            if ("POST".equals(method) && "/api/upload".equals(path)) {
                if (contentLength <= 0 || contentLength > MAX_UPLOAD_BYTES) {
                    sendJsonError(rawOut, "413 Payload Too Large", "Invalid image size");
                    return;
                }
                byte[] body = readExactBytes(rawIn, contentLength);
                String savedUri = saveUploadedWallpaper(body);
                if (savedUri == null) {
                    sendJsonError(rawOut, "400 Bad Request", "Unsupported image format");
                    return;
                }
                int count = sessionUploads.incrementAndGet();
                uiHandler.post(() -> listener.onWallpaperReceived(savedUri, count));
                String fileName = new File(Uri.parse(savedUri).getPath()).getName();
                String ok = "{\"ok\":true,\"id\":\"" + escapeJson(fileName) + "\",\"count\":" + count + "}";
                sendBytes(rawOut, "200 OK", "application/json; charset=utf-8",
                        ok.getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }

            if ("POST".equals(method) && "/api/select".equals(path)) {
                File f = resolveSafeWallpaperFile(params.get("id"));
                if (f == null || !f.exists()) {
                    sendJsonError(rawOut, "404 Not Found", "Wallpaper not found");
                    return;
                }
                final String uri = f.toURI().toString();
                uiHandler.post(() -> listener.onWallpaperSelected(uri));
                sendBytes(rawOut, "200 OK", "application/json; charset=utf-8",
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }

            if ("POST".equals(method) && "/api/delete".equals(path)) {
                File f = resolveSafeWallpaperFile(params.get("id"));
                if (f == null) {
                    sendJsonError(rawOut, "404 Not Found", "Wallpaper not found");
                    return;
                }
                final String uri = f.toURI().toString();
                uiHandler.post(() -> listener.onWallpaperDeleted(uri));
                sendBytes(rawOut, "200 OK", "application/json; charset=utf-8",
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }

            if ("POST".equals(method) && "/api/slideshow".equals(path)) {
                int min = 0;
                try { min = Integer.parseInt(params.getOrDefault("min", "0")); } catch (Exception ignored) {}
                final int clamped = (min == 5 || min == 15 || min == 30 || min == 60 || min == 360) ? min : 0;
                uiHandler.post(() -> listener.onSlideshowChanged(clamped));
                sendBytes(rawOut, "200 OK", "application/json; charset=utf-8",
                        ("{\"ok\":true,\"slideshowMin\":" + clamped + "}").getBytes(StandardCharsets.UTF_8), "no-store");
                return;
            }

            sendJsonError(rawOut, "404 Not Found", "Unknown endpoint");
        } catch (Exception ignored) {}
    }

    private synchronized String saveUploadedWallpaper(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length < 32) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        boolean isJpeg = (rawBytes[0] & 0xFF) == 0xFF && (rawBytes[1] & 0xFF) == 0xD8;
        byte[] finalJpeg = null;

        if (isJpeg && bounds.outWidth <= 1920 && bounds.outHeight <= 1080 && rawBytes.length <= 2_200_000) {
            finalJpeg = rawBytes;
        } else {
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.length, opts);
            if (decoded == null) return null;
            try {
                Bitmap target = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(target);
                cv.drawColor(0xFF0B101A);
                float scale = Math.max(1920f / decoded.getWidth(), 1080f / decoded.getHeight());
                float dw = decoded.getWidth() * scale;
                float dh = decoded.getHeight() * scale;
                RectF dst = new RectF((1920f - dw) / 2f, (1080f - dh) / 2f, (1920f + dw) / 2f, (1080f + dh) / 2f);
                cv.drawBitmap(decoded, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                ByteArrayOutputStream bos = new ByteArrayOutputStream(360 * 1024);
                target.compress(Bitmap.CompressFormat.JPEG, 90, bos);
                target.recycle();
                finalJpeg = bos.toByteArray();
            } finally {
                decoded.recycle();
            }
        }

        File dir = wallpapersDir(context);
        String name = "wp-" + System.currentTimeMillis() + "-" + (100 + new SecureRandom().nextInt(900)) + ".jpg";
        File outFile = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(finalJpeg);
            fos.flush();
        } catch (IOException e) {
            return null;
        }

        String newUri = outFile.toURI().toString();
        List<String> gallery = new ArrayList<>();
        for (File existing : listSavedFiles(context, store)) {
            gallery.add(existing.toURI().toString());
        }
        gallery.add(0, newUri);

        while (gallery.size() > MAX_GALLERY_ITEMS) {
            String removedUri = gallery.remove(gallery.size() - 1);
            try {
                File old = new File(Uri.parse(removedUri).getPath());
                if (old.exists()) old.delete();
            } catch (Exception ignored) {}
        }
        store.list("wallpaper_gallery", gallery);
        return newUri;
    }

    private String buildGalleryJson() {
        List<File> files = listSavedFiles(context, store);
        String activeBg = store.value("background", "ember");
        String activeUri = "photo".equals(activeBg) ? store.value("background_image", "") : "";
        String activeId = "";
        if (!activeUri.isEmpty()) {
            try { activeId = new File(Uri.parse(activeUri).getPath()).getName(); } catch (Exception ignored) {}
        }
        int slideshowMin = store.number("wallpaper_interval_min", 0);
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"active\":\"").append(escapeJson(activeId)).append("\",");
        sb.append("\"slideshowMin\":").append(slideshowMin).append(",\"items\":[");
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(escapeJson(f.getName())).append("\",\"kb\":")
              .append(Math.max(1, f.length() / 1024)).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private File resolveSafeWallpaperFile(String id) {
        if (id == null || id.isEmpty() || !id.matches("^[A-Za-z0-9._-]+$")) return null;
        File dir = wallpapersDir(context);
        File candidate = new File(dir, id);
        try {
            if (!candidate.getParentFile().getCanonicalPath().equals(dir.getCanonicalPath())) return null;
        } catch (IOException e) {
            return null;
        }
        return candidate;
    }

    private static String findLanIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            if (nets == null) return null;
            for (NetworkInterface nif : Collections.list(nets)) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                String name = nif.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet")) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                            return ip;
                        }
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String randomToken(int len) {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(TOKEN_CHARS.charAt(rng.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r' && buf.size() < 4096) buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static byte[] readExactBytes(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) throw new IOException("Premature EOF");
            off += n;
        }
        return data;
    }

    private static byte[] readFileBytes(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                int n = fis.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null || q.isEmpty()) return map;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } else if (!pair.isEmpty()) {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {}
        }
        return map;
    }

    private static void sendJsonError(OutputStream out, String status, String msg) throws IOException {
        byte[] body = ("{\"ok\":false,\"error\":\"" + escapeJson(msg) + "\"}").getBytes(StandardCharsets.UTF_8);
        sendBytes(out, status, "application/json; charset=utf-8", body, "no-store");
    }

    private static void sendBytes(OutputStream out, String status, String contentType, byte[] body, String cache) throws IOException {
        StringBuilder hdr = new StringBuilder(256);
        hdr.append("HTTP/1.1 ").append(status).append("\r\n");
        hdr.append("Content-Type: ").append(contentType).append("\r\n");
        hdr.append("Content-Length: ").append(body.length).append("\r\n");
        if (cache != null) hdr.append("Cache-Control: ").append(cache).append("\r\n");
        hdr.append("Connection: close\r\n\r\n");
        out.write(hdr.toString().getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildWebAppHtml() {
        return "<!doctype html>\n"
            + "<html lang='en'><head><meta charset='utf-8'>\n"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>\n"
            + "<meta name='theme-color' content='#0B101A'>\n"
            + "<title>Luma Home · Wallpaper Drop</title>\n"
            + "<style>\n"
            + "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}\n"
            + "body{margin:0;min-height:100vh;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;\n"
            + "  background:radial-gradient(circle at 18% 0%,#24324c 0%,#0b101a 55%,#06080f 100%);color:#f3f6fc;padding:20px 16px 48px}\n"
            + ".wrap{max-width:460px;margin:0 auto}\n"
            + ".badge{display:inline-flex;align-items:center;gap:8px;padding:6px 13px;border-radius:99px;\n"
            + "  background:rgba(16,185,129,.14);border:1px solid rgba(52,211,153,.35);color:#6ee7b7;font-size:12.5px;font-weight:600}\n"
            + ".dot{width:8px;height:8px;border-radius:50%;background:#34d399;box-shadow:0 0 10px #34d399}\n"
            + "h1{margin:14px 0 4px;font-size:26px;font-weight:700;letter-spacing:-.02em}\n"
            + ".sub{margin:0 0 18px;color:#98a4b8;font-size:14.5px;line-height:1.45}\n"
            + ".glass{background:linear-gradient(180deg,rgba(255,255,255,.10),rgba(255,255,255,.04));\n"
            + "  border:1px solid rgba(255,255,255,.16);border-radius:22px;padding:18px;margin-bottom:16px;\n"
            + "  box-shadow:0 16px 36px rgba(0,0,0,.38),inset 0 1px 0 rgba(255,255,255,.22)}\n"
            + ".seg-label{font-size:11.5px;font-weight:700;letter-spacing:.08em;color:#8fa0b8;text-transform:uppercase;margin-bottom:9px}\n"
            + ".seg{display:flex;gap:8px;margin-bottom:16px}\n"
            + ".seg button{flex:1;padding:10px 8px;border-radius:13px;border:1px solid rgba(255,255,255,.14);\n"
            + "  background:rgba(255,255,255,.05);color:#cbd5e1;font-size:13px;font-weight:600;cursor:pointer;transition:.16s}\n"
            + ".seg button.on{background:linear-gradient(180deg,rgba(56,189,248,.28),rgba(56,189,248,.12));\n"
            + "  border-color:rgba(125,211,252,.75);color:#fff;box-shadow:0 0 18px rgba(56,189,248,.22)}\n"
            + ".drop-btn{display:flex;flex-direction:column;align-items:center;justify-content:center;width:100%;\n"
            + "  padding:26px 18px;border-radius:18px;border:1.5px dashed rgba(125,211,252,.55);\n"
            + "  background:linear-gradient(180deg,rgba(56,189,248,.16),rgba(56,189,248,.05));color:#fff;\n"
            + "  cursor:pointer;text-align:center;transition:transform .12s}\n"
            + ".drop-btn:active{transform:scale(.98)}\n"
            + ".drop-icon{font-size:32px;margin-bottom:8px}\n"
            + ".drop-title{font-size:17px;font-weight:700;margin-bottom:4px}\n"
            + ".drop-hint{font-size:12.5px;color:#9cc2db;line-height:1.35}\n"
            + ".prog{display:none;margin-top:14px}\n"
            + ".bar-bg{height:8px;border-radius:99px;background:rgba(255,255,255,.1);overflow:hidden}\n"
            + ".bar-fg{height:100%;width:0%;background:linear-gradient(90deg,#38bdf8,#34d399);transition:width .2s}\n"
            + ".prog-txt{font-size:13px;color:#bae6fd;margin-top:7px;text-align:center;font-weight:500}\n"
            + ".pills{display:flex;flex-wrap:wrap;gap:7px}\n"
            + ".pill{padding:8px 12px;border-radius:11px;border:1px solid rgba(255,255,255,.14);\n"
            + "  background:rgba(255,255,255,.05);color:#cbd5e1;font-size:13px;font-weight:600;cursor:pointer}\n"
            + ".pill.on{background:rgba(56,189,248,.24);border-color:#7dd3fc;color:#fff}\n"
            + ".grid{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin-top:10px}\n"
            + ".card{position:relative;aspect-ratio:16/9;border-radius:13px;overflow:hidden;\n"
            + "  border:2px solid rgba(255,255,255,.12);background:#111827;cursor:pointer}\n"
            + ".card.active{border-color:#34d399;box-shadow:0 0 16px rgba(52,211,153,.35)}\n"
            + ".card img{width:100%;height:100%;object-fit:cover;display:block}\n"
            + ".live-tag{position:absolute;left:7px;bottom:7px;padding:3px 8px;border-radius:7px;\n"
            + "  background:rgba(6,78,59,.88);border:1px solid #34d399;color:#a7f3d0;font-size:10.5px;font-weight:700}\n"
            + ".del-btn{position:absolute;top:6px;right:6px;width:26px;height:26px;border-radius:50%;border:none;\n"
            + "  background:rgba(15,23,42,.82);color:#f8fafc;font-size:15px;line-height:26px;text-align:center;cursor:pointer}\n"
            + ".empty{color:#7c8aa0;font-size:13.5px;text-align:center;padding:14px 0 4px}\n"
            + ".toast{position:fixed;left:50%;bottom:22px;transform:translateX(-50%);padding:10px 18px;border-radius:99px;\n"
            + "  background:rgba(15,23,42,.94);border:1px solid rgba(125,211,252,.5);color:#fff;font-size:13.5px;\n"
            + "  font-weight:600;box-shadow:0 10px 25px rgba(0,0,0,.5);opacity:0;pointer-events:none;transition:opacity .22s}\n"
            + ".toast.show{opacity:1}\n"
            + "</style></head><body>\n"
            + "<div class='wrap'>\n"
            + "  <div class='badge'><span class='dot'></span>Connected to Luma Home TV · Local Wi-Fi</div>\n"
            + "  <h1>Wallpaper Drop</h1>\n"
            + "  <p class='sub'>Send photos straight to your TV in 1080p. Zero sign-in — stays 100% on your home Wi-Fi.</p>\n"
            + "  <div class='glass'>\n"
            + "    <div class='seg-label'>TV Framing</div>\n"
            + "    <div class='seg'>\n"
            + "      <button id='fitCover' class='on' onclick=\"setFit('cover')\">Fill TV (16:9)</button>\n"
            + "      <button id='fitContain' onclick=\"setFit('contain')\">Full Photo + Blur</button>\n"
            + "    </div>\n"
            + "    <input id='picker' type='file' accept='image/*' multiple hidden onchange='handleFiles(this.files)'>\n"
            + "    <div class='drop-btn' onclick=\"document.getElementById('picker').click()\">\n"
            + "      <div class='drop-icon'>🖼️</div>\n"
            + "      <div class='drop-title'>Choose Photos to Send</div>\n"
            + "      <div class='drop-hint'>Camera Roll, Google Photos, Google Drive, or iCloud<br>Select one or multiple photos</div>\n"
            + "    </div>\n"
            + "    <div id='prog' class='prog'>\n"
            + "      <div class='bar-bg'><div id='bar' class='bar-fg'></div></div>\n"
            + "      <div id='progTxt' class='prog-txt'>Preparing…</div>\n"
            + "    </div>\n"
            + "  </div>\n"
            + "  <div class='glass'>\n"
            + "    <div class='seg-label'>Auto-Rotate Slideshow</div>\n"
            + "    <div class='pills' id='slidePills'>\n"
            + "      <button class='pill' data-m='0' onclick='setSlide(0)'>Off</button>\n"
            + "      <button class='pill' data-m='5' onclick='setSlide(5)'>5 min</button>\n"
            + "      <button class='pill' data-m='15' onclick='setSlide(15)'>15 min</button>\n"
            + "      <button class='pill' data-m='30' onclick='setSlide(30)'>30 min</button>\n"
            + "      <button class='pill' data-m='60' onclick='setSlide(60)'>1 hr</button>\n"
            + "      <button class='pill' data-m='360' onclick='setSlide(360)'>6 hr</button>\n"
            + "    </div>\n"
            + "  </div>\n"
            + "  <div class='glass'>\n"
            + "    <div class='seg-label'>Saved on TV (<span id='galCount'>0</span>)</div>\n"
            + "    <div id='gallery'></div>\n"
            + "  </div>\n"
            + "</div>\n"
            + "<div id='toast' class='toast'></div>\n"
            + "<script>\n"
            + "const TOKEN='" + token + "';\n"
            + "let fitMode='cover';\n"
            + "function setFit(m){\n"
            + "  fitMode=m;\n"
            + "  document.getElementById('fitCover').className=m==='cover'?'on':'';\n"
            + "  document.getElementById('fitContain').className=m==='contain'?'on':'';\n"
            + "}\n"
            + "function showToast(msg){\n"
            + "  const t=document.getElementById('toast');\n"
            + "  t.textContent=msg; t.classList.add('show');\n"
            + "  clearTimeout(window._tt); window._tt=setTimeout(()=>t.classList.remove('show'),2400);\n"
            + "}\n"
            + "async function handleFiles(fileList){\n"
            + "  if(!fileList||!fileList.length)return;\n"
            + "  const files=Array.from(fileList);\n"
            + "  const prog=document.getElementById('prog'),bar=document.getElementById('bar'),txt=document.getElementById('progTxt');\n"
            + "  prog.style.display='block'; bar.style.width='4%';\n"
            + "  let sent=0;\n"
            + "  for(let i=0;i<files.length;i++){\n"
            + "    txt.textContent='Sending '+(i+1)+' of '+files.length+' to TV…';\n"
            + "    try{\n"
            + "      const blob=await render1080pJpeg(files[i],fitMode);\n"
            + "      const res=await fetch('/api/upload?t='+TOKEN,{method:'POST',headers:{'Content-Type':'image/jpeg'},body:blob});\n"
            + "      if(res.ok)sent++;\n"
            + "    }catch(e){}\n"
            + "    bar.style.width=Math.round(((i+1)/files.length)*100)+'%';\n"
            + "  }\n"
            + "  document.getElementById('picker').value='';\n"
            + "  setTimeout(()=>{prog.style.display='none';bar.style.width='0%';},900);\n"
            + "  if(sent>0){showToast(sent===1?'Live on your TV!':sent+' wallpapers sent to TV!');loadGallery();}\n"
            + "  else showToast('Could not read that image format');\n"
            + "}\n"
            + "function render1080pJpeg(file,mode){\n"
            + "  return new Promise((resolve,reject)=>{\n"
            + "    const url=URL.createObjectURL(file);\n"
            + "    const img=new Image();\n"
            + "    img.onload=()=>{\n"
            + "      URL.revokeObjectURL(url);\n"
            + "      const W=1920,H=1080;\n"
            + "      const cv=document.createElement('canvas'); cv.width=W; cv.height=H;\n"
            + "      const ctx=cv.getContext('2d');\n"
            + "      ctx.fillStyle='#0B101A'; ctx.fillRect(0,0,W,H);\n"
            + "      const iw=img.naturalWidth||img.width, ih=img.naturalHeight||img.height;\n"
            + "      if(mode==='contain'){\n"
            + "        const bgS=Math.max(W/iw,H/ih)*1.12;\n"
            + "        const bw=iw*bgS,bh=ih*bgS;\n"
            + "        ctx.save();\n"
            + "        try{ctx.filter='blur(34px) brightness(0.52)';}catch(e){ctx.globalAlpha=0.38;}\n"
            + "        ctx.drawImage(img,(W-bw)/2,(H-bh)/2,bw,bh);\n"
            + "        ctx.restore();\n"
            + "        ctx.fillStyle='rgba(8,12,20,0.28)'; ctx.fillRect(0,0,W,H);\n"
            + "        const fgS=Math.min(W/iw,H/ih);\n"
            + "        const fw=iw*fgS,fh=ih*fgS;\n"
            + "        ctx.drawImage(img,(W-fw)/2,(H-fh)/2,fw,fh);\n"
            + "      }else{\n"
            + "        const s=Math.max(W/iw,H/ih);\n"
            + "        const dw=iw*s,dh=ih*s;\n"
            + "        ctx.drawImage(img,(W-dw)/2,(H-dh)/2,dw,dh);\n"
            + "      }\n"
            + "      cv.toBlob(b=>b?resolve(b):reject(new Error('blob')),'image/jpeg',0.91);\n"
            + "    };\n"
            + "    img.onerror=()=>{URL.revokeObjectURL(url);reject(new Error('img'));};\n"
            + "    img.src=url;\n"
            + "  });\n"
            + "}\n"
            + "async function selectWp(id){\n"
            + "  await fetch('/api/select?t='+TOKEN+'&id='+encodeURIComponent(id),{method:'POST'});\n"
            + "  showToast('Applied on TV'); loadGallery();\n"
            + "}\n"
            + "async function deleteWp(ev,id){\n"
            + "  ev.stopPropagation();\n"
            + "  await fetch('/api/delete?t='+TOKEN+'&id='+encodeURIComponent(id),{method:'POST'});\n"
            + "  showToast('Removed from TV'); loadGallery();\n"
            + "}\n"
            + "async function setSlide(m){\n"
            + "  await fetch('/api/slideshow?t='+TOKEN+'&min='+m,{method:'POST'});\n"
            + "  showToast(m===0?'Slideshow off':'Slideshow every '+(m>=60?(m/60)+' hr':m+' min'));\n"
            + "  loadGallery();\n"
            + "}\n"
            + "async function loadGallery(){\n"
            + "  try{\n"
            + "    const r=await fetch('/api/list?t='+TOKEN);\n"
            + "    if(!r.ok)return;\n"
            + "    const d=await r.json();\n"
            + "    document.querySelectorAll('#slidePills .pill').forEach(b=>{\n"
            + "      b.classList.toggle('on',Number(b.dataset.m)===d.slideshowMin);\n"
            + "    });\n"
            + "    document.getElementById('galCount').textContent=d.items.length;\n"
            + "    const g=document.getElementById('gallery');\n"
            + "    if(!d.items.length){g.innerHTML=\"<div class='empty'>No phone wallpapers saved yet — send your first photo above!</div>\";return;}\n"
            + "    g.className='grid';\n"
            + "    g.innerHTML=d.items.map(it=>{\n"
            + "      const act=it.id===d.active;\n"
            + "      return \"<div class='card \"+(act?'active':'')+\"' onclick=\\\"selectWp('\"+it.id+\"')\\\">\"\n"
            + "        +\"<img loading='lazy' src='/api/thumb?t=\"+TOKEN+\"&id=\"+encodeURIComponent(it.id)+\"'>\"\n"
            + "        +(act?\"<span class='live-tag'>LIVE ON TV</span>\":\"\")\n"
            + "        +\"<button class='del-btn' onclick=\\\"deleteWp(event,'\"+it.id+\"')\\\">×</button></div>\";\n"
            + "    }).join('');\n"
            + "  }catch(e){}\n"
            + "}\n"
            + "loadGallery();\n"
            + "</script></body></html>";
    }
}
