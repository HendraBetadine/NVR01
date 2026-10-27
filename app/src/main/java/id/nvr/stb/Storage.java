package id.nvr.stb;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deteksi storage (internal, SD, HDD/flashdisk USB). */
public class Storage {

    private static final String[] MOUNTS = {
            "/storage", "/mnt", "/mnt/usb", "/mnt/media_rw", "/storage/external_storage", "/mnt/sda"};

    private static boolean looksLikeDisk(String name) {
        String n = name.toLowerCase();
        return n.startsWith("sd") || n.startsWith("usb") || n.contains("udisk")
                || n.matches("[0-9a-f]{4}-[0-9a-f]{4}");
    }

    /** Satu baris /proc/mounts yang kemungkinan disk (flashdisk/HDD/SD). */
    public static class Mount {
        public String dev, path, fs;
        public boolean readOnly;
        public String writable;   // folder yang bisa ditulis, null jika tidak ada
        public String note = "";
    }

    private static final String[] SKIP = {"/mnt/asec", "/mnt/obb", "/mnt/secure", "/mnt/shell", "/mnt/fuse",
            "/storage/emulated", "/mnt/runtime", "/mnt/user", "/mnt/vendor", "/mnt/media_rw"};

    /** Semua mount di /mnt atau /storage yang tampak seperti disk, lengkap dengan hasil uji tulis. */
    public static List<Mount> mounts(Context ctx) {
        List<Mount> out = new ArrayList<>();
        // Minta Android membuat folder milik aplikasi di setiap storage yang dikenalnya (perlu di Android 4.4).
        try { ctx.getExternalFilesDirs(null); } catch (Throwable ignored) { }
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/mounts"));
            String line;
            Set<String> seen = new LinkedHashSet<>();
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 4) continue;
                String path = p[1].replace("\\040", " ");
                String fs = p[2];
                if (!(path.startsWith("/mnt/") || path.startsWith("/storage/"))) continue;
                if (fs.equals("tmpfs") || fs.equals("proc") || fs.equals("sysfs") || fs.equals("devpts")) continue;
                boolean skip = false;
                for (String s : SKIP) if (path.startsWith(s)) skip = true;
                if (skip || !seen.add(path)) continue;
                Mount m = new Mount();
                m.dev = p[0];
                m.path = path;
                m.fs = fs;
                m.readOnly = (("," + p[3] + ",").contains(",ro,"));
                // Coba: <disk>/STBNVR, lalu folder milik aplikasi <disk>/Android/data/<paket>/files
                String a = new File(path, "STBNVR").getAbsolutePath();
                String b = new File(path, "Android/data/" + ctx.getPackageName() + "/files/rekaman").getAbsolutePath();
                if (testWrite(a)) m.writable = a;
                else if (testWrite(b)) m.writable = b;
                if (m.writable == null) {
                    if (m.readOnly) m.note = "hanya-baca (format NTFS? ubah ke FAT32/exFAT)";
                    else if (!new File(path).canRead()) m.note = "tidak bisa dibaca aplikasi";
                    else m.note = "Android 4.4 menolak menulis ke disk ini";
                }
                out.add(m);
            }
            br.close();
        } catch (Exception ignored) { }
        return out;
    }

    /** Folder yang bisa dipakai untuk rekaman (sudah dites bisa ditulis). */
    public static List<String> recordingCandidates(Context ctx) {
        Set<String> cand = new LinkedHashSet<>();
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) for (File f : dirs) {
            if (f != null) cand.add(new File(f, "rekaman").getAbsolutePath());
        }
        for (Mount m : mounts(ctx)) if (m.writable != null) cand.add(m.writable);
        for (String m : MOUNTS) {
            File[] subs = new File(m).listFiles();
            if (subs == null) continue;
            for (File s : subs) {
                if (!looksLikeDisk(s.getName())) continue;
                cand.add(new File(s, "STBNVR").getAbsolutePath());
                // Beberapa STB memasang partisi di dalam folder disk, mis. /mnt/sda/sda1
                File[] parts = s.listFiles();
                if (parts != null) for (File pt : parts) {
                    if (pt.isDirectory() && looksLikeDisk(pt.getName())) cand.add(new File(pt, "STBNVR").getAbsolutePath());
                }
            }
        }
        List<String> ok = new ArrayList<>();
        Set<String> canon = new LinkedHashSet<>();
        for (String c : cand) {
            if (!testWrite(c)) continue;
            try { if (!canon.add(new File(c).getCanonicalPath())) continue; } catch (Exception ignored) { }
            ok.add(c);
        }
        // Urutkan: storage eksternal (USB/SD) di atas, memori internal paling bawah
        List<String> sorted = new ArrayList<>();
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (String c : ok) if (!c.startsWith(internal) && !c.startsWith("/storage/emulated")) sorted.add(c);
        for (String c : ok) if (!sorted.contains(c)) sorted.add(c);
        return sorted;
    }

    public static boolean isInternal(String path) {
        String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
        return path.startsWith(internal) || path.startsWith("/storage/emulated") || path.startsWith("/sdcard");
    }

    /** Folder akar yang boleh dibuka lewat web (label -> folder). */
    public static Map<String, File> webRoots(Context ctx) {
        Map<String, File> roots = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        String rec = new Config(ctx).getStoragePath();
        if (!rec.isEmpty()) add(roots, seen, "Rekaman", new File(rec));
        add(roots, seen, "Internal", Environment.getExternalStorageDirectory());
        for (Mount m : mounts(ctx)) {
            if (m.writable != null) add(roots, seen, "USB " + new File(m.path).getName(), new File(m.writable).getParentFile());
        }
        for (String m : MOUNTS) {
            File[] subs = new File(m).listFiles();
            if (subs == null) continue;
            for (File s : subs) {
                if (looksLikeDisk(s.getName()) && s.isDirectory() && s.canRead()) {
                    add(roots, seen, "USB " + s.getName(), s);
                }
            }
        }
        return roots;
    }

    private static void add(Map<String, File> roots, Set<String> seen, String label, File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            String canon = dir.getCanonicalPath();
            if (!seen.add(canon)) return;
            String l = label;
            int i = 2;
            while (roots.containsKey(l)) l = label + " " + (i++);
            roots.put(l, new File(canon));
        } catch (Exception ignored) { }
    }

    /**
     * Folder bersama di /sdcard/STBNVR/<sub> (tetap ada walau aplikasi di-uninstall).
     * Jika tidak bisa ditulis, pakai folder milik aplikasi.
     */
    public static File shareDir(Context ctx, String sub) {
        File d = new File(new File(Environment.getExternalStorageDirectory(), "STBNVR"), sub);
        if (testWrite(d.getAbsolutePath())) return d;
        File f = ctx.getExternalFilesDir(sub);
        if (f != null && testWrite(f.getAbsolutePath())) return f;
        File in = new File(ctx.getFilesDir(), sub);
        in.mkdirs();
        return in;
    }

    public static boolean testWrite(String path) {
        if (path == null || path.isEmpty()) return false;
        try {
            File dir = new File(path);
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File t = new File(dir, ".test");
            FileOutputStream o = new FileOutputStream(t);
            o.write(1);
            o.close();
            return t.delete();
        } catch (Exception e) {
            return false;
        }
    }
}
