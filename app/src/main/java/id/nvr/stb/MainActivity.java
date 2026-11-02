package id.nvr.stb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Satu layar pengaturan sederhana, bisa dioperasikan dengan remote STB. */
public class MainActivity extends Activity {

    private Config cfg;
    private EditText etCams, etPath, etSeg, etMinFree, etDays, etPort, etPass;
    private CheckBox cbAudio, cbWeb;
    private TextView tvStatus, tvWeb;
    private Button btLiveRef;
    /** Thread latar untuk akses disk/jaringan (jangan pernah di thread utama). */
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newCachedThreadPool();
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
        Diag.install(this);
        cfg = new Config(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        title(root, "STB NVR — Perekam CCTV 24/7");

        // ---- Live view ----
        LinearLayout liveRow = new LinearLayout(this);
        Button btLive = new Button(this);
        btLive.setText("📺  LIVE VIEW");
        btLive.setTextSize(20);
        btLive.setOnClickListener(v -> startActivity(new Intent(this, LiveActivity.class)));
        liveRow.addView(btLive);
        final CheckBox cbLiveBoot = new CheckBox(this);
        cbLiveBoot.setText("Tampilkan live view saat box menyala");
        cbLiveBoot.setChecked(cfg.isLiveOnBoot());
        cbLiveBoot.setOnCheckedChangeListener((v, on) -> cfg.setLiveOnBoot(on));
        liveRow.addView(cbLiveBoot);
        root.addView(liveRow, full());
        btLiveRef = btLive;

        // ---- Web server (paling atas, supaya alamatnya langsung terlihat) ----
        tvWeb = new TextView(this);
        tvWeb.setTextSize(20);
        tvWeb.setTextColor(Color.rgb(120, 200, 255));
        root.addView(tvWeb, full());

        LinearLayout webRow = new LinearLayout(this);
        cbWeb = new CheckBox(this);
        cbWeb.setText("Web server");
        cbWeb.setChecked(cfg.isWebEnabled());
        webRow.addView(cbWeb);
        etPort = number(webRow, "Port", cfg.getWebPort());
        LinearLayout passCol = new LinearLayout(this);
        passCol.setOrientation(LinearLayout.VERTICAL);
        label(passCol, "Password web (user: admin)");
        etPass = new EditText(this);
        etPass.setSingleLine(true);
        etPass.setText(cfg.getWebPassword());
        passCol.addView(etPass, full());
        webRow.addView(passCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button btWeb = new Button(this);
        btWeb.setText("Terapkan");
        btWeb.setOnClickListener(v -> applyWeb());
        webRow.addView(btWeb);
        root.addView(webRow, full());

        // ---- Kamera ----
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
        btStop.setText("■  STOP REKAM");
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
            io.execute(() -> {          // pindai storage di latar (flashdisk bisa lambat)
                final List<String> c = Storage.recordingCandidates(this);
                runOnUiThread(() -> { if (!c.isEmpty() && etPath.getText().length() == 0) etPath.setText(c.get(0)); });
            });
        }
        RecorderService.start(this);   // nyalakan web server (dan rekaman jika aktif)
        (cfg.isEnabled() ? btLiveRef : btStart).requestFocus();
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresher); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(refresher); }

    private void saveWeb() {
        cfg.saveWeb(cbWeb.isChecked(), parse(etPort, 8080), etPass.getText().toString().trim());
    }

    private void applyWeb() {
        saveWeb();
        RecorderService.apply(this);
        Toast.makeText(this, cfg.isWebEnabled() ? "Web server diterapkan" : "Web server dimatikan", Toast.LENGTH_SHORT).show();
    }

    private void startRec() {
        final String path = etPath.getText().toString().trim();
        Toast.makeText(this, "Memeriksa folder rekaman…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            final boolean ok = Storage.testWrite(path);
            runOnUiThread(() -> {
                if (!ok) Toast.makeText(this, "Folder tidak bisa ditulis. Pilih lewat \"Cari storage\".", Toast.LENGTH_LONG).show();
                else startRecChecked(path);
            });
        });
    }

    private void startRecChecked(String path) {
        cfg.save(etCams.getText().toString(), path,
                parse(etSeg, 10), parse(etMinFree, 5), parse(etDays, 0), cbAudio.isChecked());
        if (cfg.getCameras().isEmpty()) {
            Toast.makeText(this, "Isi minimal satu kamera.", Toast.LENGTH_LONG).show();
            return;
        }
        saveWeb();
        cfg.setEnabled(true);
        RecorderService.apply(this);
        Toast.makeText(this, "Mulai merekam…", Toast.LENGTH_SHORT).show();
    }

    private void stopRec() {
        cfg.setEnabled(false);
        RecorderService.apply(this);
        Toast.makeText(this, "Perekaman dihentikan.", Toast.LENGTH_SHORT).show();
    }

    // Data yang butuh akses disk/jaringan dihitung di latar; layar hanya menampilkan hasil terakhir.
    private volatile List<String> ipsCache = new ArrayList<>();
    private volatile long freeCache = -1;
    private final java.util.concurrent.atomic.AtomicBoolean probing = new java.util.concurrent.atomic.AtomicBoolean();

    private void probeInBackground(final String path) {
        if (!probing.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                ipsCache = localIps();
                freeCache = path.isEmpty() ? -1 : RecorderService.freeBytes(new File(path));
            } finally {
                probing.set(false);
            }
        });
    }

    private void refreshStatus() {
        probeInBackground(etPath.getText().toString().trim());
        // Alamat web
        if (cfg.isWebEnabled()) {
            String info = RecorderService.webInfo;
            StringBuilder w = new StringBuilder();
            for (String ip : ipsCache) w.append("http://").append(ip).append(':').append(cfg.getWebPort()).append("   ");
            tvWeb.setText(info.startsWith("Web server gagal") ? info
                    : "Buka di browser:  " + (w.length() > 0 ? w.toString().trim() : "(belum ada jaringan)")
                      + "\nUser: admin   Password: " + (cfg.getWebPassword().isEmpty() ? "(tanpa password)" : cfg.getWebPassword()));
        } else {
            tvWeb.setText("Web server mati");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(RecorderService.running ? "STATUS: MEREKAM\n" : "STATUS: TIDAK MEREKAM\n");
        synchronized (RecorderService.STATUS) {
            for (Map.Entry<String, String> e : RecorderService.STATUS.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        if (freeCache >= 0) sb.append(String.format("\nSisa ruang: %.1f GB", freeCache / 1073741824.0));
        tvStatus.setText(sb.toString());
    }

    private static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return ips;
    }

    private void pickStorage() {
        Toast.makeText(this, "Mencari storage…", Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            final List<String> list = Storage.recordingCandidates(this);
            // Disk yang terdeteksi tapi tidak bisa ditulis -> tampilkan alasannya
            final StringBuilder bad = new StringBuilder();
            for (Storage.Mount m : Storage.mounts(this)) {
                if (m.writable == null) bad.append("• ").append(m.path).append(" (").append(m.fs).append("): ").append(m.note).append('\n');
            }
            final long[] free = new long[list.size()];
            for (int i = 0; i < list.size(); i++) free[i] = RecorderService.freeBytes(new File(list.get(i)));
            runOnUiThread(() -> { if (!isFinishing()) showStoragePicker(list, bad, free); });
        });
    }

    private void showStoragePicker(final List<String> list, StringBuilder bad, long[] free) {
        if (list.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Tidak ada storage yang bisa ditulis")
                    .setMessage(bad.length() > 0 ? "Terdeteksi tapi tidak bisa ditulis:\n" + bad
                            : "Tidak ada flashdisk/HDD terdeteksi. Cabut-colok lalu coba lagi.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            items[i] = String.format("%s%s   (%.1f GB kosong)", Storage.isInternal(list.get(i)) ? "[INTERNAL] " : "[USB] ",
                    list.get(i), free[i] / 1073741824.0);
        }
        AlertDialog.Builder dlg = new AlertDialog.Builder(this)
                .setTitle("Pilih folder rekaman")
                .setItems(items, (d, w) -> etPath.setText(list.get(w)));
        if (bad.length() > 0) dlg.setNegativeButton("Disk lain tidak muncul?", (d, w) ->
                new AlertDialog.Builder(this).setTitle("Disk yang tidak bisa ditulis").setMessage(bad.toString())
                        .setPositiveButton("OK", null).show());
        dlg.show();
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
