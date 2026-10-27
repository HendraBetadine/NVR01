package id.nvr.stb;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service perekam 24/7.
 * - 1 proses ffmpeg per kamera, remux RTSP -> MKV tanpa transcoding (-c copy).
 * - Jika kamera putus / ffmpeg macet, otomatis sambung ulang.
 * - Menghapus rekaman terlama saat ruang kosong menipis (loop seperti NVR).
 */
public class RecorderService extends Service {

    private static final String TAG = "STBNVR";
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "nvr";
    private static final long STALL_MS = 60_000;        // file tidak bertambah 60 dtk = macet
    private static final long CLEANUP_EVERY_MS = 120_000;

    /** Status per kamera untuk ditampilkan di layar. */
    public static final Map<String, String> STATUS = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    public static volatile boolean running = false;

    public static volatile String webInfo = "";

    private static final String ACTION_APPLY = "id.nvr.stb.APPLY";

    private final List<Worker> workers = new ArrayList<>();
    private Thread cleaner;
    private final android.os.Handler retryHandler = new android.os.Handler();
    private final Runnable retryStart = new Runnable() {
        @Override public void run() {
            Config cfg = new Config(RecorderService.this);
            if (!cfg.isEnabled() || running) return;
            startRecording(cfg);
            if (!running) retryHandler.postDelayed(this, 20_000);
        }
    };
    private WebServer web;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    /** Nyalakan service (dipanggil saat boot / alarm penjaga). Tidak me-restart yang sudah jalan. */
    public static void start(Context ctx) {
        Config cfg = new Config(ctx);
        if (!cfg.isEnabled() && !cfg.isWebEnabled()) return;
        launch(ctx, new Intent(ctx, RecorderService.class));
        scheduleKeepAlive(ctx);
    }

    /** Terapkan pengaturan terbaru (rekaman & web server) — dipanggil dari layar pengaturan. */
    public static void apply(Context ctx) {
        Config cfg = new Config(ctx);
        if (!cfg.isEnabled() && !cfg.isWebEnabled()) {
            cancelKeepAlive(ctx);
            ctx.stopService(new Intent(ctx, RecorderService.class));
            return;
        }
        launch(ctx, new Intent(ctx, RecorderService.class).setAction(ACTION_APPLY));
        scheduleKeepAlive(ctx);
    }

    private static void launch(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    // ---- Alarm penjaga: tiap 15 menit memastikan service tetap hidup ----
    private static PendingIntent keepAliveIntent(Context ctx) {
        Intent i = new Intent(ctx, RecorderService.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(ctx, 0, i, flags);
        return PendingIntent.getService(ctx, 0, i, flags);
    }

    private static void scheduleKeepAlive(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, keepAliveIntent(ctx));
    }

    private static void cancelKeepAlive(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(ALARM_SERVICE);
        am.cancel(keepAliveIntent(ctx));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification n = buildNotification("Merekam 24/7");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stbnvr:rec");
        wakeLock.acquire();
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "stbnvr:wifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Config cfg = new Config(this);
        boolean applyNow = intent != null && ACTION_APPLY.equals(intent.getAction());

        // --- Rekaman ---
        if (applyNow) stopRecording();
        if (cfg.isEnabled() && !running) startRecording(cfg);
        // Setelah boot, HDD USB sering baru ter-mount beberapa detik kemudian -> coba lagi tiap 20 dtk.
        retryHandler.removeCallbacks(retryStart);
        if (cfg.isEnabled() && !running) retryHandler.postDelayed(retryStart, 20_000);
        if (!cfg.isEnabled() && running) stopRecording();

        // --- Web server ---
        boolean webChanged = web != null
                && (web.getPort() != cfg.getWebPort() || !web.getPassword().equals(cfg.getWebPassword()));
        if (web != null && (!cfg.isWebEnabled() || webChanged)) { web.stop(); web = null; webInfo = ""; }
        if (cfg.isWebEnabled() && web == null) {
            try {
                WebServer w = new WebServer(this, cfg.getWebPort(), cfg.getWebPassword());
                w.start();
                web = w;
                webInfo = "Web server aktif di port " + cfg.getWebPort();
            } catch (Exception e) {
                webInfo = "Web server gagal: " + e.getMessage();
            }
        }

        if (!cfg.isEnabled() && !cfg.isWebEnabled()) { stopSelf(); return START_NOT_STICKY; }
        updateNotification((running ? "Merekam" : "Tidak merekam")
                + (web != null ? " · web :" + cfg.getWebPort() : ""));
        return START_STICKY;   // dihidupkan ulang sistem jika ter-kill
    }

    private void stopRecording() {
        running = false;
        for (Worker w : workers) w.shutdown();
        workers.clear();
        if (cleaner != null) { cleaner.interrupt(); cleaner = null; }
        STATUS.clear();
    }

    private void startRecording(final Config cfg) {
        File ffmpeg = new File(getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        STATUS.clear();

        if (!ffmpeg.exists()) {
            STATUS.put("ERROR", "libffmpeg.so tidak ada di APK. Baca README langkah 1.");
            return;
        }
        String root = cfg.getStoragePath();
        File rootDir = new File(root);
        if (root.isEmpty() || !(rootDir.isDirectory() || rootDir.mkdirs()) || !rootDir.canWrite()) {
            STATUS.put("ERROR", "Menunggu HDD / folder rekaman bisa ditulis: " + root + " (dicoba tiap 20 dtk)");
            return;
        }
        List<Config.Camera> cams = cfg.getCameras();
        if (cams.isEmpty()) {
            STATUS.put("ERROR", "Belum ada kamera.");
            return;
        }

        running = true;
        for (Config.Camera c : cams) {
            Worker w = new Worker(c, ffmpeg.getAbsolutePath(), new File(rootDir, c.name),
                    new File(new File(rootDir, ".live"), c.name), cfg.getSegmentMinutes(), cfg.getAudio());
            workers.add(w);
            w.start();
        }
        cleaner = new Thread(() -> cleanupLoop(rootDir, cfg.getMinFreeGb(), cfg.getRetentionDays()), "cleaner");
        cleaner.start();
    }

    @Override
    public void onDestroy() {
        retryHandler.removeCallbacks(retryStart);
        stopRecording();
        if (web != null) { web.stop(); web = null; }
        webInfo = "";
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // =====================================================================
    //  Worker: menjalankan & menjaga 1 proses ffmpeg untuk 1 kamera
    // =====================================================================
    private static class Worker extends Thread {
        private final Config.Camera cam;
        private final String ffmpeg;
        private final File dir;
        private final File liveDir;
        private final int segMinutes;
        private final boolean audio;
        private volatile Process proc;
        private volatile boolean stop = false;
        // Mode "tee": 1 koneksi RTSP -> file rekaman MKV + stream live HLS.
        private boolean useTee = true;
        private boolean triedFallback = false;

        Worker(Config.Camera cam, String ffmpeg, File dir, File liveDir, int segMinutes, boolean audio) {
            super("cam-" + cam.name);
            this.cam = cam; this.ffmpeg = ffmpeg; this.dir = dir; this.liveDir = liveDir;
            this.segMinutes = segMinutes; this.audio = audio;
        }

        private List<String> buildCmd() {
            List<String> cmd = new ArrayList<>(Arrays.asList(
                    ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "warning",
                    "-rtsp_transport", "tcp",
                    "-timeout", "10000000",            // 10 dtk tanpa data = putus
                    "-fflags", "+genpts+discardcorrupt",
                    // Banyak kamera mengirim timestamp yang melompat mundur -> file MKV berhenti
                    // ("non monotonically increasing dts"). Pakai waktu terima di STB, selalu maju.
                    "-use_wallclock_as_timestamps", "1",
                    "-i", cam.url,
                    "-map", "0:v:0"));
            if (audio) { cmd.add("-map"); cmd.add("0:a:0?"); }
            cmd.add("-c");
            cmd.add("copy");
            String mkv = new File(dir, cam.name + "_%Y%m%d_%H%M%S.mkv").getAbsolutePath();
            if (useTee) {
                liveDir.mkdirs();
                File[] old = liveDir.listFiles();
                if (old != null) for (File f : old) f.delete();
                cmd.add("-f");
                cmd.add("tee");
                // onfail=abort: kalau penulis rekaman gagal, ffmpeg langsung keluar & disambung ulang
                cmd.add("[f=segment:onfail=abort:segment_time=" + (segMinutes * 60)
                        + ":segment_format=matroska:segment_atclocktime=1:reset_timestamps=1:strftime=1]" + mkv
                        + "|[f=hls:select=v:onfail=ignore:hls_time=2:hls_list_size=4"
                        + ":hls_flags=delete_segments+temp_file]"
                        + new File(liveDir, "index.m3u8").getAbsolutePath());
            } else {
                cmd.addAll(Arrays.asList(
                        "-f", "segment",
                        "-segment_time", String.valueOf(segMinutes * 60),
                        "-segment_format", "matroska",
                        "-segment_atclocktime", "1",
                        "-reset_timestamps", "1",
                        "-strftime", "1",
                        mkv));
            }
            return cmd;
        }

        void shutdown() {
            stop = true;
            Process p = proc;
            if (p != null) p.destroy();
            interrupt();
        }

        @Override
        public void run() {
            int fails = 0;
            while (!stop) {
                dir.mkdirs();
                List<String> cmd = buildCmd();

                long started = System.currentTimeMillis();
                STATUS.put(cam.name, "Menyambung…");
                try {
                    final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    proc = p;
                    Thread logger = new Thread(() -> drainLog(p), "log-" + cam.name);
                    logger.start();
                    STATUS.put(cam.name, useTee ? "● Merekam" : "● Merekam (tanpa live)");

                    // Watchdog: kalau file terbaru tidak bertambah, matikan ffmpeg.
                    while (!stop) {
                        if (waitFor(proc, 15_000)) break;            // ffmpeg keluar sendiri
                        long age = System.currentTimeMillis() - newestFileTime(dir);
                        if (System.currentTimeMillis() - started > STALL_MS && age > STALL_MS) {
                            Log.w(TAG, cam.name + ": macet, restart");
                            proc.destroy();
                            break;
                        }
                    }
                    proc.waitFor();
                } catch (Exception e) {
                    Log.e(TAG, cam.name + " error", e);
                }
                proc = null;
                if (stop) break;

                // Jeda sebelum sambung ulang (5 dtk, naik sampai 60 dtk jika gagal terus)
                boolean quickFail = System.currentTimeMillis() - started < 30_000;
                fails = quickFail ? Math.min(fails + 1, 12) : 0;
                // Pengaman: kalau mode live (tee) cepat gagal, coba mode rekam saja.
                // Kalau mode rekam saja juga gagal, masalahnya di kamera -> kembali ke mode live.
                if (quickFail) {
                    if (useTee) { useTee = false; triedFallback = true; }
                    else if (triedFallback) { useTee = true; triedFallback = false; }
                }
                long wait = 5_000L * Math.max(1, fails);
                STATUS.put(cam.name, "✕ Putus, coba lagi " + (wait / 1000) + " dtk (lihat log)");
                try { Thread.sleep(wait); } catch (InterruptedException ie) { break; }
            }
        }

        /** Simpan output ffmpeg ke <folder kamera>/ffmpeg.log (maks ~256 KB). */
        private void drainLog(Process p) {
            File log = new File(dir, "ffmpeg.log");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                if (log.length() > 256 * 1024) log.delete();
                FileWriter fw = new FileWriter(log, true);
                fw.write("\n--- start " + new java.util.Date() + " ---\n");
                String line;
                while ((line = r.readLine()) != null) {
                    fw.write(line);
                    fw.write('\n');
                    fw.flush();
                }
                fw.close();
            } catch (Exception ignored) { }
        }

        private static boolean waitFor(Process p, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                try { p.exitValue(); return true; }        // API 19 belum punya waitFor(timeout)
                catch (IllegalThreadStateException still) { Thread.sleep(1000); }
            }
            return false;
        }
    }

    private static long newestFileTime(File dir) {
        long newest = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.getName().endsWith(".mkv")) newest = Math.max(newest, f.lastModified());
        }
        return newest;
    }

    // =====================================================================
    //  Pembersihan: hapus file terlama jika ruang kosong < batas
    // =====================================================================
    private void cleanupLoop(File root, int minFreeGb, int retentionDays) {
        long minFree = minFreeGb * 1024L * 1024L * 1024L;
        while (running) {
            try {
                List<File> files = new ArrayList<>();
                File[] camDirs = root.listFiles();
                if (camDirs != null) for (File d : camDirs) {
                    File[] fs = d.listFiles();
                    if (fs == null) continue;
                    File newest = null;
                    for (File f : fs) {
                        if (!f.getName().endsWith(".mkv")) continue;
                        if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
                    }
                    for (File f : fs) {
                        // Jangan hapus file yang sedang ditulis
                        if (f.getName().endsWith(".mkv") && f != newest) files.add(f);
                    }
                }
                Collections.sort(files, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });

                long cutoff = retentionDays > 0
                        ? System.currentTimeMillis() - retentionDays * 86_400_000L : 0;
                for (File f : files) {
                    boolean tooOld = cutoff > 0 && f.lastModified() < cutoff;
                    boolean lowSpace = freeBytes(root) < minFree;
                    if (!tooOld && !lowSpace) break;
                    if (f.delete()) Log.i(TAG, "hapus " + f.getName());
                }
                updateNotification("Merekam · sisa " + (freeBytes(root) / (1024 * 1024 * 1024)) + " GB");
                Thread.sleep(CLEANUP_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.e(TAG, "cleanup", e);
            }
        }
    }

    public static long freeBytes(File f) {
        try {
            StatFs s = new StatFs(f.getAbsolutePath());
            return s.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- Notifikasi foreground ----
    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Perekaman", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        return b.setContentTitle("STB NVR")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text));
    }
}
