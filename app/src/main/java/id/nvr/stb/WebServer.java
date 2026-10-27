package id.nvr.stb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

            // Live view di layar TV STB mengakses lewat 127.0.0.1 -> tidak perlu password.
            boolean local = sock.getInetAddress() != null && sock.getInetAddress().isLoopbackAddress();
            if (!local && !authorized(r)) {
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
        if (m.equals("GET") && (p.equals("/") || p.equals("/index.html"))) {
            serveAsset(r, "index.html", "text/html; charset=utf-8", "Cache-Control: no-cache\r\n"); return; }
        if (m.equals("GET") && p.equals("/hls.js")) {
            serveAsset(r, "hls.light.min.js", "application/javascript", "Cache-Control: max-age=86400\r\n"); return; }
        if (m.equals("GET") && p.startsWith("/live/")) { live(r); return; }
        if (m.equals("GET") && p.equals("/api/livecams")) { liveCams(r); return; }
        if (m.equals("GET") && p.equals("/api/roots")) { apiRoots(r); return; }
        if (m.equals("GET") && p.equals("/api/list")) { apiList(r); return; }
        if (m.equals("GET") && p.equals("/api/status")) { apiStatus(r); return; }
        if ((m.equals("GET") || m.equals("HEAD")) && p.equals("/dl")) { download(r); return; }
        if ((m.equals("PUT") || m.equals("POST")) && p.equals("/api/upload")) { upload(r); return; }
        if (m.equals("POST") && p.equals("/api/mkdir")) { mkdir(r); return; }
        if (m.equals("POST") && p.equals("/api/delete")) { delete(r); return; }
        if (m.equals("GET") && p.equals("/api/config")) { getConfig(r); return; }
        if (m.equals("POST") && p.equals("/api/config")) { setConfig(r); return; }
        if (m.equals("POST") && p.equals("/api/rec")) { setRec(r); return; }
        if (m.equals("GET") && p.equals("/api/storages")) { storages(r); return; }
        if (m.equals("GET") && p.equals("/api/cams")) { recCams(r); return; }
        if (m.equals("GET") && p.equals("/api/day")) { recDay(r); return; }
        if ((m.equals("GET") || m.equals("HEAD")) && p.equals("/play")) { play(r); return; }
        json(r, 404, "{\"error\":\"tidak ditemukan\"}");
    }

    // ---- Halaman utama ----
    private void serveAsset(Req r, String name, String type, String extra) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        send(r, 200, type, bos.toByteArray(), extra);
    }

    // ---- Live view (HLS yang ditulis ffmpeg perekam ke <folder rekaman>/.live/<kamera>/) ----
    private void live(Req r) throws IOException {
        String[] seg = r.path.split("/");          // "", "live", cam, file
        File root = recRoot();
        if (seg.length != 4 || root == null) { json(r, 404, "{\"error\":\"tidak ada\"}"); return; }
        String cam = new File(seg[2]).getName(), file = new File(seg[3]).getName();
        if (cam.startsWith(".") || !(file.endsWith(".m3u8") || file.endsWith(".ts"))) {
            json(r, 404, "{\"error\":\"tidak ada\"}"); return;
        }
        File f = new File(new File(new File(root, ".live"), cam), file);
        if (!f.isFile()) { json(r, 404, "{\"error\":\"live belum tersedia\"}"); return; }
        byte[] body;
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            body = new byte[(int) raf.length()];
            raf.readFully(body);
        } finally {
            raf.close();
        }
        send(r, 200, file.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t", body,
                "Cache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n");
    }

    /** Kamera dari pengaturan + apakah stream live-nya sedang tersedia. */
    private void liveCams(Req r) throws IOException {
        Config c = new Config(ctx);
        File root = recRoot();
        StringBuilder sb = new StringBuilder("[");
        for (Config.Camera cam : c.getCameras()) {
            File m3u8 = root == null ? null : new File(new File(new File(root, ".live"), cam.name), "index.m3u8");
            boolean on = m3u8 != null && m3u8.isFile() && System.currentTimeMillis() - m3u8.lastModified() < 20_000;
            String st = RecorderService.STATUS.get(cam.name);
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"name\":").append(js(cam.name))
              .append(",\"live\":").append(on)
              .append(",\"status\":").append(js(st == null ? "" : st)).append('}');
        }
        json(r, 200, sb.append(']').toString());
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
        serveFile(r, f, "1".equals(r.q("inline")));
    }

    private void serveFile(Req r, File f, boolean inline) throws IOException {
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
        if (!inline) {
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
    //  Pengaturan via web
    // =====================================================================
    private void getConfig(Req r) throws IOException {
        Config c = new Config(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("cams", c.getCamerasRaw());
            o.put("path", c.getStoragePath());
            o.put("seg", c.getSegmentMinutes());
            o.put("minfree", c.getMinFreeGb());
            o.put("days", c.getRetentionDays());
            o.put("audio", c.getAudio());
            o.put("enabled", c.isEnabled());
            o.put("web", c.isWebEnabled());
            o.put("port", c.getWebPort());
            o.put("pass", c.getWebPassword());
            json(r, 200, o.toString());
        } catch (JSONException e) {
            json(r, 500, "{\"error\":\"json\"}");
        }
    }

    private void setConfig(Req r) throws IOException {
        Config c = new Config(ctx);
        JSONObject o;
        try { o = new JSONObject(readBody(r)); }
        catch (Exception e) { json(r, 400, "{\"error\":\"data tidak valid\"}"); return; }

        String path = o.optString("path", c.getStoragePath()).trim();
        boolean enabled = o.optBoolean("enabled", c.isEnabled());
        if (enabled && !Storage.testWrite(path)) {
            json(r, 400, "{\"error\":\"Folder rekaman tidak bisa ditulis: " + path.replace("\"", "") + "\"}");
            return;
        }
        c.save(o.optString("cams", c.getCamerasRaw()), path,
                o.optInt("seg", c.getSegmentMinutes()), o.optInt("minfree", c.getMinFreeGb()),
                o.optInt("days", c.getRetentionDays()), o.optBoolean("audio", c.getAudio()));
        if (enabled && c.getCameras().isEmpty()) { json(r, 400, "{\"error\":\"Isi minimal satu kamera\"}"); return; }
        c.setEnabled(enabled);
        c.saveWeb(o.optBoolean("web", c.isWebEnabled()), o.optInt("port", c.getWebPort()),
                o.optString("pass", c.getWebPassword()));
        json(r, 200, "{\"ok\":true}");
        RecorderService.apply(ctx);    // setelah respons terkirim (web server bisa ikut restart)
    }

    private void setRec(Req r) throws IOException {
        Config c = new Config(ctx);
        boolean on = "1".equals(r.q("on"));
        if (on && (c.getCameras().isEmpty() || !Storage.testWrite(c.getStoragePath()))) {
            json(r, 400, "{\"error\":\"Lengkapi kamera & folder rekaman dulu di Pengaturan\"}");
            return;
        }
        c.setEnabled(on);
        json(r, 200, "{\"ok\":true}");
        RecorderService.apply(ctx);
    }

    private void storages(Req r) throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (String s : Storage.recordingCandidates(ctx)) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"path\":").append(js(s)).append(",\"free\":").append(RecorderService.freeBytes(new File(s))).append('}');
        }
        json(r, 200, sb.append(']').toString());
    }

    private String readBody(Req r) throws IOException {
        long len;
        try { len = Long.parseLong(r.headers.get("content-length")); } catch (Exception e) { return ""; }
        if (len > 1024 * 1024) throw new IOException("body terlalu besar");
        byte[] b = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = r.in.read(b, off, (int) len - off);
            if (n < 0) break;
            off += n;
        }
        return new String(b, 0, off, "UTF-8");
    }

    // =====================================================================
    //  Playback rekaman
    // =====================================================================
    private static final Pattern REC = Pattern.compile("^(.+)_(\\d{8})_(\\d{6})\\.mkv$");
    private static final Object PLAY_LOCK = new Object();

    private File recRoot() {
        String p = new Config(ctx).getStoragePath();
        return p.isEmpty() ? null : new File(p);
    }

    /** Daftar kamera + tanggal yang punya rekaman. */
    private void recCams(Req r) throws IOException {
        File root = recRoot();
        File[] dirs = root == null ? null : root.listFiles();
        StringBuilder sb = new StringBuilder("[");
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) {
                if (!d.isDirectory() || d.getName().startsWith(".")) continue;
                TreeSet<String> days = new TreeSet<>(Collections.<String>reverseOrder());
                String[] names = d.list();
                if (names != null) for (String n : names) {
                    Matcher m = REC.matcher(n);
                    if (m.matches()) days.add(m.group(2));
                }
                if (days.isEmpty()) continue;
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"name\":").append(js(d.getName())).append(",\"days\":[");
                boolean first = true;
                for (String day : days) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(js(day));
                }
                sb.append("]}");
            }
        }
        json(r, 200, sb.append(']').toString());
    }

    /** Daftar file rekaman 1 kamera pada 1 hari, urut waktu. */
    private void recDay(Req r) throws IOException {
        File dir = camDir(r.q("cam"));
        String day = r.q("day");
        if (dir == null) { json(r, 404, "{\"error\":\"kamera tidak ada\"}"); return; }
        String[] names = dir.list();
        List<String> list = new ArrayList<>();
        if (names != null) for (String n : names) {
            Matcher m = REC.matcher(n);
            if (m.matches() && m.group(2).equals(day)) list.add(n);
        }
        Collections.sort(list, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return a.substring(a.length() - 10).compareTo(b.substring(b.length() - 10));
            }
        });
        String newest = newestRec(dir);
        StringBuilder sb = new StringBuilder("{\"seg\":").append(new Config(ctx).getSegmentMinutes()).append(",\"files\":[");
        boolean first = true;
        for (String n : list) {
            Matcher m = REC.matcher(n);
            if (!m.matches()) continue;
            String hms = m.group(3);
            int sec = Integer.parseInt(hms.substring(0, 2)) * 3600
                    + Integer.parseInt(hms.substring(2, 4)) * 60 + Integer.parseInt(hms.substring(4, 6));
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"f\":").append(js(n))
              .append(",\"sec\":").append(sec)
              .append(",\"s\":").append(new File(dir, n).length())
              .append(",\"live\":").append(n.equals(newest))
              .append('}');
        }
        json(r, 200, sb.append("]}").toString());
    }

    /**
     * Putar 1 file rekaman di browser. MKV di-remux (tanpa encode ulang) ke MP4 "faststart",
     * disimpan di cache supaya bisa di-seek, lalu dikirim dengan dukungan Range.
     */
    private void play(Req r) throws IOException {
        File dir = camDir(r.q("cam"));
        String name = new File(r.q("file")).getName();
        if (dir == null || !REC.matcher(name).matches()) { json(r, 404, "{\"error\":\"file tidak ada\"}"); return; }
        File src = new File(dir, name);
        if (!src.isFile()) { json(r, 404, "{\"error\":\"file tidak ada\"}"); return; }

        File cacheDir = new File(recRoot(), ".playcache");
        cacheDir.mkdirs();
        File mp4 = new File(cacheDir, dir.getName() + "__" + name.replace(".mkv", ".mp4"));
        boolean audio = "1".equals(r.q("audio"));
        if (audio) mp4 = new File(cacheDir, mp4.getName().replace(".mp4", "_a.mp4"));

        synchronized (PLAY_LOCK) {   // 1 konversi sekaligus supaya CPU STB tidak kewalahan
            // File yang masih direkam terus bertambah: cache dipakai ulang 60 dtk supaya seek tidak memicu remux ulang.
            boolean stale = mp4.lastModified() < src.lastModified()
                    && System.currentTimeMillis() - mp4.lastModified() > 60_000;
            if (!mp4.isFile() || stale) {
                if (!remux(src, mp4, audio)) {
                    json(r, 500, "{\"error\":\"gagal menyiapkan video (lihat file ffmpeg.log di folder .playcache)\"}");
                    return;
                }
                pruneCache(cacheDir, mp4);
            }
        }
        serveFile(r, mp4, true);
    }

    private boolean remux(File src, File out, boolean audio) {
        File ffmpeg = new File(ctx.getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        File tmp = new File(out.getParentFile(), "tmp_" + out.getName());
        List<String> cmd = new ArrayList<>(Arrays.asList(
                ffmpeg.getAbsolutePath(), "-nostdin", "-y", "-hide_banner", "-loglevel", "error",
                "-i", src.getAbsolutePath(), "-map", "0:v:0"));
        if (audio) { cmd.add("-map"); cmd.add("0:a:0?"); }
        else cmd.add("-an");
        cmd.addAll(Arrays.asList("-c", "copy", "-movflags", "+faststart", "-f", "mp4", tmp.getAbsolutePath()));
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final File log = new File(out.getParentFile(), "ffmpeg.log");
            new Thread(new Runnable() {      // simpan pesan error ffmpeg (API 19 belum punya redirectOutput)
                @Override public void run() {
                    try {
                        InputStream is = p.getInputStream();
                        FileOutputStream fo = new FileOutputStream(log);
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = is.read(b)) > 0) fo.write(b, 0, n);
                        fo.close();
                    } catch (Exception ignored) { }
                }
            }, "remux-log").start();
            long deadline = System.currentTimeMillis() + 5 * 60_000;
            while (true) {
                try { int code = p.exitValue();
                      if (code == 0 && tmp.length() > 0) { out.delete(); return tmp.renameTo(out); }
                      tmp.delete(); return false; }
                catch (IllegalThreadStateException running) {
                    if (System.currentTimeMillis() > deadline) { p.destroy(); tmp.delete(); return false; }
                    Thread.sleep(300);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "remux", e);
            tmp.delete();
            return false;
        }
    }

    /** Simpan maksimal 6 video di cache, hapus yang lama. */
    private static void pruneCache(File cacheDir, File keep) {
        File[] fs = cacheDir.listFiles();
        if (fs == null) return;
        List<File> mp4s = new ArrayList<>();
        for (File f : fs) if (f.getName().endsWith(".mp4") && !f.equals(keep)) mp4s.add(f);
        Collections.sort(mp4s, new Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        for (int i = 5; i < mp4s.size(); i++) mp4s.get(i).delete();
    }

    private File camDir(String cam) {
        File root = recRoot();
        if (root == null) return null;
        String n = new File(cam).getName();
        if (n.isEmpty() || n.startsWith(".")) return null;
        File d = new File(root, n);
        return d.isDirectory() ? d : null;
    }

    private static String newestRec(File dir) {
        String newest = null;
        long t = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (REC.matcher(f.getName()).matches() && f.lastModified() > t) { t = f.lastModified(); newest = f.getName(); }
        }
        return newest;
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
