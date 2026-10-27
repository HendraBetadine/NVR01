package id.nvr.stb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Pengaturan aplikasi, disimpan di SharedPreferences. */
public class Config {

    public static class Camera {
        public final String name;
        public final String url;
        Camera(String name, String url) { this.name = name; this.url = url; }
    }

    private static final String PREF = "nvr";
    private final SharedPreferences sp;

    public Config(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** Satu kamera per baris: nama,rtsp://user:pass@ip:554/stream */
    public String getCamerasRaw() { return sp.getString("cams", ""); }
    public String getStoragePath() { return sp.getString("path", ""); }
    public int getSegmentMinutes() { return sp.getInt("seg", 10); }
    public int getMinFreeGb() { return sp.getInt("minfree", 5); }
    public int getRetentionDays() { return sp.getInt("days", 0); }
    public boolean getAudio() { return sp.getBoolean("audio", true); }
    public boolean isEnabled() { return sp.getBoolean("enabled", false); }

    public void save(String cams, String path, int seg, int minFree, int days, boolean audio) {
        sp.edit()
                .putString("cams", cams)
                .putString("path", path)
                .putInt("seg", Math.max(1, seg))
                .putInt("minfree", Math.max(1, minFree))
                .putInt("days", Math.max(0, days))
                .putBoolean("audio", audio)
                .apply();
    }

    public void setEnabled(boolean on) { sp.edit().putBoolean("enabled", on).apply(); }

    public List<Camera> getCameras() {
        List<Camera> list = new ArrayList<>();
        int n = 1;
        for (String line : getCamerasRaw().split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String name, url;
            int comma = line.indexOf(',');
            if (comma > 0 && !line.substring(0, comma).contains("://")) {
                name = line.substring(0, comma).trim();
                url = line.substring(comma + 1).trim();
            } else {
                name = "cam" + n;
                url = line;
            }
            name = name.replaceAll("[^A-Za-z0-9_-]", "_");
            if (name.isEmpty()) name = "cam" + n;
            list.add(new Camera(name, url));
            n++;
        }
        return list;
    }
}
