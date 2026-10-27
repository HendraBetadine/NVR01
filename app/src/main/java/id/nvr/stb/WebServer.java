package id.nvr.stb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web server mini tanpa library tambahan (jalan di Android 4.4).
 * Fitur: lihat folder, upload (streaming langsung ke disk), download (dengan Range),
 * buat folder, hapus. Dilindungi password (HTTP Basic, user: admin).
 */
public class WebServer {

    private static final String TAG = "STBNVR-web";
    private static final int BUF = 64 * 1024;

    private final Context ctx;
    private final int port;
    private final String password;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    public WebServer(Context ctx, int port, String password) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
        this.password = password == null ? "" : password;
    }

    public int getPort() { return port; }
    public String getPassword() { return password; }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        pool = Executors.newFixedThreadPool(8);
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "web-accept");
        t.start();
        Log.i(TAG, "web server di port " + port);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                s.setSoTimeout(120_000);
                pool.execute(new Runnable() {
                    @Override public void run() { handle(s); }
                });
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept", e);
            }
        }
    }

    // =====================================================================
    //  Request handling
    // =====================================================================
    private static class Req {
        String method, path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        InputStream in;
        OutputStream out;
        String q(String k) { String v = query.get(k); return v == null ? "" : v; }
    }

    private void handle(Socket sock) {
        try {
            Req r = new Req();
            r.in = new BufferedInputStream(sock.getInputStream(), BUF);
            r.out = new BufferedOutputStream(sock.getOutputStream(), BUF);

            String line = readLine(r.in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            r.method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];
            while ((line = readLine(r.in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
            }
            int qm = target.indexOf('?');
            r.path = decode(qm >= 0 ? target.substring(0, qm) : target);
            if (qm >= 0) {
                for (String kv : target.substring(qm + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) r.query.put(decode(kv.substring(0, e)), decode(kv.substring(e + 1)));
                    else if (!kv.isEmpty()) r.query.put(decode(kv), "");
                }
            }

            if (!authorized(r)) {
                send(r, 401, "text/plain; charset=utf-8", "Butuh password".getBytes("UTF-8"),
                        "WWW-Authenticate: Basic realm=\"STB NVR\"\r\n");
                return;
            }
            route(r);
            r.out.flush();
        } catch (Exception e) {
            Log.w(TAG, "request", e);
        } finally {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private boolean authorized(Req r) {
        if (password.isEmpty()) return true;
        String a = r.headers.get("authorization");
        if (a == null || !a.startsWith("Basic ")) return false;
        try {
            String dec = new String(Base64.decode(a.substring(6).trim(), Base64.DEFAULT), "UTF-8");
            int c = dec.indexOf(':');
            return c >= 0 && dec.substring(c + 1).equals(password);
        } catch (Exception e) {
            return false;
        }
    }

    private void route(Req r) throws IOException {
        String m = r.method, p = r.path;
        if (m.equals("GET") && (p.equals("/") || p.equals("/index.html"))) { serveIndex(r); return; }
        if (m.equals("GET") && p.equals("/api/roots")) { apiRoots(r); return; }
        if (m.equals("GET") && p.equals("/api/list")) { apiList(r); return; }
        if (m.equals("GET") && p.equals("/api/status")) { apiStatus(r); return; }
        if ((m.equals("GET") || m.equals("HEAD")) && p.equals("/dl")) { download(r); return; }
        if ((m.equals("PUT") || m.equals("POST")) && p.equals("/api/upload")) { upload(r); return; }
        if (m.equals("POST") && p.equals("/api/mkdir")) { mkdir(r); return; }
        if (m.equals("POST") && p.equals("/api/delete")) { delete(r); return; }
        json(r, 404, "{\"error\":\"tidak ditemukan\"}");
    }

    // ---- Halaman utama ----
    private void serveIndex(Req r) throws IOException {
        InputStream is = ctx.getAssets().open("index.html");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        send(r, 200, "text/html; charset=utf-8", bos.toByteArray(), "Cache-Control: no-cache\r\n");
    }

    // ---- API ----
    private void apiRoots(Req r) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (Map.Entry<String, File> e : Storage.webRoots(ctx).entrySet()) {
            if (sb.length() > 1) sb.append(',');
            File f = e.getValue();
            sb.append("{\"name\":").append(js(e.getKey()))
              .append(",\"path\":").append(js(f.getAbsolutePath()))
              .append(",\"free\":").append(RecorderService.freeBytes(f))
              .append(",\"total\":").append(totalBytes(f))
              .append(",\"writable\":").append(f.canWrite())
              .append('}');
        }
        json(r, 200, sb.append(']').toString());
    }

    private void apiList(Req r) throws IOException {
        File dir = resolve(r.q("root"), r.q("path"));
        if (dir == null || !dir.isDirectory()) { json(r, 404, "{\"error\":\"folder tidak ada\"}"); return; }
        File[] files = dir.listFiles();
        List<File> list = files == null ? new ArrayList<File>() : new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        StringBuilder sb = new StringBuilder("{\"free\":").append(RecorderService.freeBytes(dir))
                .append(",\"writable\":").append(dir.canWrite()).append(",\"items\":[");
        boolean first = true;
        for (File f : list) {
            if (f.getName().startsWith(".")) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":").append(js(f.getName()))
              .append(",\"d\":").append(f.isDirectory())
              .append(",\"s\":").append(f.isDirectory() ? 0 : f.length())
              .append(",\"t\":").append(f.lastModified())
              .append('}');
        }
        json(r, 200, sb.append("]}").toString());
    }

    private void apiStatus(Req r) throws IOException {
        StringBuilder sb = new StringBuilder("{\"recording\":").append(RecorderService.running).append(",\"cams\":[");
        synchronized (RecorderService.STATUS) {
            boolean first = true;
            for (Map.Entry<String, String> e : RecorderService.STATUS.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":").append(js(e.getKey())).append(",\"status\":").append(js(e.getValue())).append('}');
            }
        }
        json(r, 200, sb.append("]}").toString());
    }

    // ---- Download (mendukung Range supaya bisa resume / seek) ----
    private void download(Req r) throws IOException {
        File f = resolve(r.q("root"), r.q("path"));
        if (f == null || !f.isFile()) { json(r, 404, "{\"error\":\"file tidak ada\"}"); return; }
        long len = f.length(), start = 0, end = len - 1;
        boolean partial = false;
        String range = r.headers.get("range");
        if (range != null && range.startsWith("bytes=") && len > 0) {
            try {
                String spec = range.substring(6).split(",")[0].trim();
                int dash = spec.indexOf('-');
                String a = spec.substring(0, dash).trim(), b = spec.substring(dash + 1).trim();
                if (a.isEmpty()) { start = Math.max(0, len - Long.parseLong(b)); }
                else {
                    start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Math.min(Long.parseLong(b), len - 1);
                }
                if (start > end || start >= len) {
                    send(r, 416, "text/plain", new byte[0], "Content-Range: bytes */" + len + "\r\n");
                    return;
                }
                partial = true;
            } catch (Exception ignored) { start = 0; end = len - 1; }
        }
        long count = len == 0 ? 0 : end - start + 1;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(mime(f.getName())).append("\r\n");
        h.append("Content-Length: ").append(count).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
        if (!"1".equals(r.q("inline"))) {
            h.append("Content-Disposition: attachment; filename=\"").append(f.getName().replace("\"", "")).append("\"\r\n");
        }
        h.append("Connection: close\r\n\r\n");
        r.out.write(h.toString().getBytes("UTF-8"));
        if (r.method.equals("HEAD") || count == 0) return;

        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            raf.seek(start);
            byte[] buf = new byte[BUF];
            long left = count;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                r.out.write(buf, 0, n);
                left -= n;
            }
        } finally {
            raf.close();
        }
    }

    // ---- Upload: body request ditulis langsung ke disk (tidak lewat memori/temp) ----
    private void upload(Req r) throws IOException {
        File dir = resolve(r.q("root"), r.q("path"));
        String name = new File(r.q("name")).getName().trim();
        if (dir == null || !dir.isDirectory()) { json(r, 404, "{\"error\":\"folder tujuan tidak ada\"}"); return; }
        if (name.isEmpty() || name.equals(".") || name.equals("..")) { json(r, 400, "{\"error\":\"nama file kosong\"}"); return; }
        String cl = r.headers.get("content-length");
        long len;
        try { len = Long.parseLong(cl); } catch (Exception e) { json(r, 411, "{\"error\":\"Content-Length wajib\"}"); return; }
        if (len > RecorderService.freeBytes(dir)) { json(r, 507, "{\"error\":\"ruang disk tidak cukup\"}"); return; }

        if ("100-continue".equalsIgnoreCase(r.headers.get("expect"))) {
            r.out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
            r.out.flush();
        }

        File part = new File(dir, "." + name + ".part");
        File target = new File(dir, name);
        long left = len;
        FileOutputStream fos;
        try {
            fos = new FileOutputStream(part);
        } catch (IOException e) {
            json(r, 403, "{\"error\":\"folder ini tidak bisa ditulis\"}");
            return;
        }
        try {
            byte[] buf = new byte[BUF];
            while (left > 0) {
                int n = r.in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                fos.write(buf, 0, n);
                left -= n;
            }
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (left > 0) {
            part.delete();
            json(r, 400, "{\"error\":\"upload terputus\"}");
            return;
        }
        if (target.exists()) target.delete();
        if (!part.renameTo(target)) {
            part.delete();
            json(r, 500, "{\"error\":\"gagal menyimpan file\"}");
            return;
        }
        json(r, 200, "{\"ok\":true}");
    }

    private void mkdir(Req r) throws IOException {
        File dir = resolve(r.q("root"), r.q("path"));
        String name = new File(r.q("name")).getName().trim();
        if (dir == null || name.isEmpty() || name.startsWith(".")) { json(r, 400, "{\"error\":\"nama tidak valid\"}"); return; }
        File nd = new File(dir, name);
        json(r, nd.mkdirs() || nd.isDirectory() ? 200 : 403,
                nd.isDirectory() ? "{\"ok\":true}" : "{\"error\":\"gagal membuat folder\"}");
    }

    private void delete(Req r) throws IOException {
        File base = Storage.webRoots(ctx).get(r.q("root"));
        File f = resolve(r.q("root"), r.q("path"));
        if (f == null || base == null || f.equals(base)) { json(r, 400, "{\"error\":\"tidak boleh\"}"); return; }
        json(r, deleteRec(f) ? 200 : 403, f.exists() ? "{\"error\":\"gagal menghapus\"}" : "{\"ok\":true}");
    }

    private static boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        return f.delete();
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    /** Ubah (root, path relatif) jadi File; tolak kalau keluar dari folder akar. */
    private File resolve(String rootName, String rel) {
        try {
            File base = Storage.webRoots(ctx).get(rootName);
            if (base == null) return null;
            File f = new File(base, rel == null ? "" : rel).getCanonicalFile();
            String b = base.getPath();
            if (f.getPath().equals(b) || f.getPath().startsWith(b.endsWith("/") ? b : b + "/")) return f;
        } catch (Exception ignored) { }
        return null;
    }

    private static long totalBytes(File f) {
        try { return new android.os.StatFs(f.getAbsolutePath()).getTotalBytes(); } catch (Exception e) { return 0; }
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain; charset=utf-8";
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16 * 1024) throw new IOException("header terlalu panjang");
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static String decode(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String js(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private void json(Req r, int code, String body) throws IOException {
        send(r, code, "application/json; charset=utf-8", body.getBytes("UTF-8"), "Cache-Control: no-store\r\n");
    }

    private static void send(Req r, int code, String type, byte[] body, String extra) throws IOException {
        String status;
        switch (code) {
            case 200: status = "OK"; break;
            case 400: status = "Bad Request"; break;
            case 401: status = "Unauthorized"; break;
            case 403: status = "Forbidden"; break;
            case 404: status = "Not Found"; break;
            case 411: status = "Length Required"; break;
            case 416: status = "Range Not Satisfiable"; break;
            case 507: status = "Insufficient Storage"; break;
            default: status = "Error";
        }
        String h = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + (extra == null ? "" : extra)
                + "Connection: close\r\n\r\n";
        r.out.write(h.getBytes("UTF-8"));
        if (!"HEAD".equals(r.method)) r.out.write(body);
        r.out.flush();
    }

    @SuppressWarnings("unused")
    private static String fmtDate(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t));
    }
}
