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

    /** Folder yang bisa dipakai untuk rekaman (sudah dites bisa ditulis). */
    public static List<String> recordingCandidates(Context ctx) {
        Set<String> cand = new LinkedHashSet<>();
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) for (File f : dirs) {
            if (f != null) cand.add(new File(f, "rekaman").getAbsolutePath());
        }
        for (String m : MOUNTS) {
            File[] subs = new File(m).listFiles();
            if (subs == null) continue;
            for (File s : subs) if (looksLikeDisk(s.getName())) cand.add(new File(s, "STBNVR").getAbsolutePath());
        }
        List<String> ok = new ArrayList<>();
        for (String c : cand) if (testWrite(c)) ok.add(c);
        return ok;
    }

    /** Folder akar yang boleh dibuka lewat web (label -> folder). */
    public static Map<String, File> webRoots(Context ctx) {
        Map<String, File> roots = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        String rec = new Config(ctx).getStoragePath();
        if (!rec.isEmpty()) add(roots, seen, "Rekaman", new File(rec));
        add(roots, seen, "Internal", Environment.getExternalStorageDirectory());
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
