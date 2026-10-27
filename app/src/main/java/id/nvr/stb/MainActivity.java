package id.nvr.stb;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Satu layar pengaturan sederhana, bisa dioperasikan dengan remote STB. */
public class MainActivity extends Activity {

    private Config cfg;
    private EditText etCams, etPath, etSeg, etMinFree, etDays;
    private CheckBox cbAudio;
    private TextView tvStatus;
    private final Handler handler = new Handler();

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Config(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        title(root, "STB NVR — Perekam CCTV 24/7");

        label(root, "Kamera (satu per baris)  format:  nama,rtsp://user:pass@ip:554/stream");
        etCams = new EditText(this);
        etCams.setMinLines(4);
        etCams.setGravity(Gravity.TOP);
        etCams.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etCams.setHint("depan,rtsp://admin:12345@192.168.1.64:554/Streaming/Channels/102");
        etCams.setText(cfg.getCamerasRaw());
        root.addView(etCams, full());

        label(root, "Folder rekaman (pakai HDD/flashdisk USB)");
        LinearLayout pathRow = new LinearLayout(this);
        etPath = new EditText(this);
        etPath.setSingleLine(true);
        etPath.setText(cfg.getStoragePath());
        pathRow.addView(etPath, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button btPick = new Button(this);
        btPick.setText("Cari storage");
        btPick.setOnClickListener(v -> pickStorage());
        pathRow.addView(btPick);
        root.addView(pathRow, full());

        LinearLayout nums = new LinearLayout(this);
        etSeg = number(nums, "Durasi file (menit)", cfg.getSegmentMinutes());
        etMinFree = number(nums, "Sisakan ruang (GB)", cfg.getMinFreeGb());
        etDays = number(nums, "Simpan maks (hari, 0=penuh)", cfg.getRetentionDays());
        root.addView(nums, full());

        cbAudio = new CheckBox(this);
        cbAudio.setText("Rekam audio (jika kamera punya)");
        cbAudio.setChecked(cfg.getAudio());
        root.addView(cbAudio);

        LinearLayout btns = new LinearLayout(this);
        Button btStart = new Button(this);
        btStart.setText("▶  SIMPAN & MULAI REKAM");
        btStart.setOnClickListener(v -> startRec());
        Button btStop = new Button(this);
        btStop.setText("■  STOP");
        btStop.setOnClickListener(v -> stopRec());
        btns.addView(btStart);
        btns.addView(btStop);
        root.addView(btns);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(18);
        tvStatus.setPadding(0, dp(16), 0, 0);
        root.addView(tvStatus, full());

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (cfg.getStoragePath().isEmpty()) {
            List<String> c = findStorage();
            if (!c.isEmpty()) etPath.setText(c.get(c.size() - 1));
        }
        btStart.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresher); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresher); }

    private void startRec() {
        String path = etPath.getText().toString().trim();
        if (!testWrite(path)) {
            Toast.makeText(this, "Folder tidak bisa ditulis. Pilih lewat \"Cari storage\".", Toast.LENGTH_LONG).show();
            return;
        }
        cfg.save(etCams.getText().toString(), path,
                parse(etSeg, 10), parse(etMinFree, 5), parse(etDays, 0), cbAudio.isChecked());
        if (cfg.getCameras().isEmpty()) {
            Toast.makeText(this, "Isi minimal satu kamera.", Toast.LENGTH_LONG).show();
            return;
        }
        cfg.setEnabled(true);
        RecorderService.stop(this);   // restart supaya pengaturan baru dipakai
        handler.postDelayed(() -> RecorderService.start(this), 1500);
        Toast.makeText(this, "Mulai merekam…", Toast.LENGTH_SHORT).show();
    }

    private void stopRec() {
        cfg.setEnabled(false);
        RecorderService.stop(this);
        Toast.makeText(this, "Perekaman dihentikan.", Toast.LENGTH_SHORT).show();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(RecorderService.running ? "STATUS: MEREKAM\n" : "STATUS: BERHENTI\n");
        synchronized (RecorderService.STATUS) {
            for (Map.Entry<String, String> e : RecorderService.STATUS.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        String path = etPath.getText().toString().trim();
        if (!path.isEmpty()) {
            long free = RecorderService.freeBytes(new File(path));
            sb.append(String.format("\nSisa ruang: %.1f GB", free / 1073741824.0));
        }
        tvStatus.setText(sb.toString());
    }

    // ---- Cari folder yang bisa ditulis (USB/SD/internal) ----
    private List<String> findStorage() {
        Set<String> cand = new LinkedHashSet<>();
        for (File f : getExternalFilesDirs(null)) {
            if (f != null) cand.add(new File(f, "rekaman").getAbsolutePath());
        }
        String[] mounts = {"/storage", "/mnt", "/mnt/usb", "/mnt/media_rw", "/storage/external_storage", "/mnt/sda"};
        for (String m : mounts) {
            File[] subs = new File(m).listFiles();
            if (subs == null) continue;
            for (File s : subs) {
                String n = s.getName().toLowerCase();
                if (n.startsWith("sd") || n.startsWith("usb") || n.contains("udisk") || n.matches("[0-9a-f]{4}-[0-9a-f]{4}")) {
                    cand.add(new File(s, "STBNVR").getAbsolutePath());
                }
            }
        }
        List<String> ok = new ArrayList<>();
        for (String c : cand) if (testWrite(c)) ok.add(c);
        return ok;
    }

    private void pickStorage() {
        final List<String> list = findStorage();
        if (list.isEmpty()) {
            Toast.makeText(this, "Tidak ada storage yang bisa ditulis. Cek HDD/flashdisk.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            long free = RecorderService.freeBytes(new File(list.get(i)));
            items[i] = String.format("%s   (%.1f GB kosong)", list.get(i), free / 1073741824.0);
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih folder rekaman")
                .setItems(items, (d, w) -> etPath.setText(list.get(w)))
                .show();
    }

    private static boolean testWrite(String path) {
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

    // ---- helper UI ----
    private void title(LinearLayout p, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(24);
        t.setTextColor(Color.WHITE);
        t.setPadding(0, 0, 0, dp(12));
        p.addView(t);
    }

    private void label(LinearLayout p, String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(12), 0, 0);
        p.addView(t);
    }

    private EditText number(LinearLayout p, String hint, int val) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        label(col, hint);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(val));
        col.addView(e, full());
        p.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return e;
    }

    private static int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return def; }
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
