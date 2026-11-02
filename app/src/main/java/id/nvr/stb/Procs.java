package id.nvr.stb;

/**
 * Menghentikan ffmpeg dengan sopan.
 *
 * Process.destroy() di Android mengirim SIGKILL: ffmpeg mati seketika, tidak sempat mengirim
 * RTSP TEARDOWN ke kamera -> sesi "menggantung" di kamera ± 1 menit. Kamera yang membatasi jumlah
 * koneksi (mis. Tapo) lalu menolak koneksi baru dengan 403 "Operation not permitted".
 * SIGTERM membuat ffmpeg menutup koneksi kamera & file rekaman dengan benar.
 */
public final class Procs {

    public static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean alive(Process p) {
        if (p == null) return false;
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    /** Kirim SIGTERM (tanpa menunggu). */
    public static void signalStop(Process p) {
        if (p == null) return;
        int pid = pidOf(p);
        if (pid > 0) {
            try { android.os.Process.sendSignal(pid, 15); return; } catch (Throwable ignored) { }
        }
        p.destroy();
    }

    /** Tunggu proses selesai maks waitMs; jika masih hidup, paksa (SIGKILL). */
    public static void awaitOrKill(Process p, long waitMs) {
        if (p == null) return;
        long end = System.currentTimeMillis() + waitMs;
        while (alive(p) && System.currentTimeMillis() < end) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        if (alive(p)) p.destroy();
    }

    /** SIGTERM lalu tunggu (maks waitMs), terakhir SIGKILL. */
    public static void terminate(Process p, long waitMs) {
        signalStop(p);
        awaitOrKill(p, waitMs);
    }
}
