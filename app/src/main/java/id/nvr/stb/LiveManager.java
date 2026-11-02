package id.nvr.stb;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live view on-demand: 1 proses ffmpeg terpisah per kamera yang sedang DITONTON.
 * - Menyala saat playlist diminta, mati 30 dtk setelah tidak ada yang menonton.
 * - Jika ffmpeg mati / playlist berhenti diperbarui -> otomatis dijalankan ulang.
 * - Perekaman tidak terpengaruh sama sekali (proses terpisah).
 * - File HLS di memori internal (cache), bukan di flashdisk.
 */
public class LiveManager {

    private static final String TAG = "STBNVR-live";
    private static final long IDLE_MS = 30_000;     // berhenti jika tidak ditonton
    private static final long STALL_MS = 15_000;    // playlist tidak diperbarui -> restart

    private static class Proc {
        final String cam, url;
        Process p;
        long lastAccess, started, nextStart;
        int fails;
        Proc(String cam, String url) { this.cam = cam; this.url = url; }
    }

    private static final Map<String, Proc> procs = new HashMap<>();
    private static Context app;
    private static Thread reaper;

    public static File root(Context ctx) { return new File(ctx.getCacheDir(), "live"); }
    public static File camDir(Context ctx, String cam) { return new File(root(ctx), cam); }
    public static File playlist(Context ctx, String cam) { return new File(camDir(ctx, cam), "index.m3u8"); }

    /** Dipanggil tiap kali stream kamera diminta. Return false jika kamera tidak dikenal. */
    public static synchronized boolean touch(Context ctx, String cam) {
        app = ctx.getApplicationContext();
        Proc pr = procs.get(cam);
        if (pr == null) {
            String url = null;
            for (Config.Camera c : new Config(app).getCameras()) if (c.name.equals(cam)) url = c.url;
            if (url == null) return false;
            pr = new Proc(cam, url);
            procs.put(cam, pr);
        }
        pr.lastAccess = System.currentTimeMillis();
        if (pr.started == 0) start(pr);          // pertama kali; restart berikutnya diatur reaper
        if (reaper == null || !reaper.isAlive()) {
            reaper = new Thread(LiveManager::reapLoop, "live-reaper");
            reaper.start();
        }
        return true;
    }

    /** Hentikan semua (mis. daftar kamera berubah / service berhenti). */
    public static synchronized void stopAll() {
        for (Proc pr : procs.values()) kill(pr);
        procs.clear();
    }

    public static synchronized boolean isStreaming(Context ctx, String cam) {
        Proc pr = procs.get(cam);
        File m = playlist(ctx, cam);
        return pr != null && alive(pr) && m.isFile() && System.currentTimeMillis() - m.lastModified() < STALL_MS;
    }

    // ---------------------------------------------------------------------
    private static boolean alive(Proc pr) {
        if (pr.p == null) return false;
        try { pr.p.exitValue(); return false; } catch (IllegalThreadStateException running) { return true; }
    }

    private static void kill(Proc pr) {
        if (pr.p != null) { pr.p.destroy(); pr.p = null; }
    }

    private static void start(final Proc pr) {
        kill(pr);
        pr.nextStart = 0;
        pr.started = System.currentTimeMillis();
        File dir = camDir(app, pr.cam);
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();

        String ffmpeg = new File(app.getApplicationInfo().nativeLibraryDir, "libffmpeg.so").getAbsolutePath();
        List<String> cmd = new ArrayList<>(Arrays.asList(
                ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "warning",
                "-rtsp_transport", "tcp",
                "-timeout", "10000000",
                "-fflags", "+genpts+discardcorrupt",
                // Pakai timestamp kamera (bukan jam STB) -> ritme frame rata, gerakan halus.
                "-i", pr.url,
                "-map", "0:v:0", "-an",
                "-c", "copy",
                "-f", "hls",
                "-hls_time", "2",
                "-hls_list_size", "5",
                // epoch: nomor segmen tetap naik walau ffmpeg di-restart -> player tidak bingung
                "-hls_start_number_source", "epoch",
                "-hls_flags", "delete_segments+temp_file+omit_endlist",
                "-hls_segment_filename", new File(dir, "s%d.ts").getAbsolutePath(),
                new File(dir, "index.m3u8").getAbsolutePath()));
        try {
            final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            pr.p = p;
            final File log = new File(root(app), pr.cam + ".log");
            new Thread(() -> {
                try {
                    if (log.length() > 128 * 1024) log.delete();
                    FileOutputStream fo = new FileOutputStream(log, true);
                    fo.write(("\n--- live start " + new java.util.Date() + " ---\n").getBytes("UTF-8"));
                    InputStream is = p.getInputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = is.read(b)) > 0) { fo.write(b, 0, n); fo.flush(); }
                    fo.close();
                } catch (Exception ignored) { }
            }, "live-log-" + pr.cam).start();
            Log.i(TAG, "live start " + pr.cam);
        } catch (Exception e) {
            Log.w(TAG, "live gagal start " + pr.cam, e);
            pr.p = null;
        }
    }

    private static void reapLoop() {
        while (true) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            synchronized (LiveManager.class) {
                long now = System.currentTimeMillis();
                List<String> drop = new ArrayList<>();
                for (Proc pr : procs.values()) {
                    if (now - pr.lastAccess > IDLE_MS) {           // tidak ada yang menonton
                        kill(pr);
                        drop.add(pr.cam);
                        continue;
                    }
                    boolean running = alive(pr);
                    File m = playlist(app, pr.cam);
                    boolean stalled = running && now - pr.started > 20_000
                            && (!m.isFile() || now - m.lastModified() > STALL_MS);
                    if (stalled) {
                        Log.w(TAG, "live macet, restart " + pr.cam);
                        kill(pr);
                        running = false;
                    }
                    if (!running) {
                        if (pr.nextStart == 0) {
                            // Gagal cepat berulang -> jeda makin lama (0, 5, 10 … maks 30 dtk)
                            boolean quick = now - pr.started < 20_000;
                            pr.fails = quick ? Math.min(pr.fails + 1, 6) : 0;
                            pr.nextStart = now + 5_000L * pr.fails;
                        }
                        if (now >= pr.nextStart) start(pr);
                    }
                }
                for (String c : drop) procs.remove(c);
                if (procs.isEmpty()) { reaper = null; return; }
            }
        }
    }
}
