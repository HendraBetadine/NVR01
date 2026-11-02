package id.nvr.stb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnosa: mencatat ke file jika thread utama macet (> 4 dtk, cikal bakal "Not responding")
 * dan jika aplikasi crash. Bisa dibaca dari web: Pengaturan → Log kamera → "Aplikasi macet/crash".
 */
public final class Diag {

    private static volatile boolean installed = false;
    private static File dir;

    public static File anrFile(Context ctx) { return new File(ctx.getFilesDir(), "diag-macet.log"); }
    public static File crashFile(Context ctx) { return new File(ctx.getFilesDir(), "diag-crash.log"); }

    public static synchronized void install(Context ctx) {
        if (installed) return;
        installed = true;
        final Context app = ctx.getApplicationContext();
        dir = app.getFilesDir();

        // Crash: catat lalu teruskan ke handler bawaan
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            crash(app, e);
            if (prev != null) prev.uncaughtException(t, e);
        });

        // Penjaga thread utama
        final Handler main = new Handler(Looper.getMainLooper());
        Thread w = new Thread(() -> {
            final boolean[] tick = new boolean[1];
            boolean reported = false;
            while (true) {
                tick[0] = false;
                main.post(() -> tick[0] = true);
                try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                if (tick[0]) { reported = false; continue; }
                if (reported) continue;              // catat sekali per kejadian macet
                reported = true;
                StringBuilder sb = new StringBuilder("Thread utama macet > 4 detik. Posisi:\n");
                for (StackTraceElement s : Looper.getMainLooper().getThread().getStackTrace()) {
                    sb.append("    at ").append(s).append('\n');
                }
                write(anrFile(app), sb.toString());
            }
        }, "diag-anr");
        w.setDaemon(true);
        w.start();
    }

    public static void crash(Context ctx, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        write(crashFile(ctx), "Crash di thread " + Thread.currentThread().getName() + ":\n" + sw);
    }

    private static synchronized void write(File f, String text) {
        try {
            if (f.length() > 96 * 1024) f.delete();
            FileWriter fw = new FileWriter(f, true);
            fw.write("\n=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " ===\n");
            fw.write(text);
            fw.close();
        } catch (Exception ignored) { }
    }
}
