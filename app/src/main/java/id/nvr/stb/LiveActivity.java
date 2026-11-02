package id.nvr.stb;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Live view di layar TV ala NVR: grid 1 / 4 / 9 / 16 kamera, dioperasikan dengan remote.
 * - Panah: pindah kamera / tombol      - OK: perbesar kamera / kembali ke grid
 * - Tombol 1,4,9: ganti layout         - CH+/CH- atau ◀ ▶: halaman berikutnya/sebelumnya
 * - Back: keluar dari perbesar, lalu keluar dari live view
 * Video diambil dari stream HLS yang dibuat perekam (lewat web server lokal 127.0.0.1).
 */
public class LiveActivity extends Activity {

    private static final int[] LAYOUTS = {1, 4, 9, 16};

    private Config cfg;
    private final Handler handler = new Handler();
    private LinearLayout gridBox;
    private TextView tvPage, tvClock;
    private final List<Tile> tiles = new ArrayList<>();
    private int layout = 4, page = 0;
    private String solo = null;

    /**
     * Thread latar untuk operasi pemutar yang bisa lama (buka stream, release).
     * Di Android 4.4, MediaPlayer.release() bisa tertahan beberapa detik saat stream sedang
     * menyambung -> kalau di thread utama, aplikasi "Not responding".
     */
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newCachedThreadPool();

    private class Tile {
        FrameLayout frame;
        SurfaceView surface;
        TextView msg;
        String cam;
        volatile MediaPlayer mp;
        volatile int gen;               // naik tiap start/stop; callback lama diabaikan
        volatile boolean surfaceReady;
        volatile int lastPos = -1;
        long lastStart, lastMove;
        boolean playing;
        final Runnable retry = new Runnable() { @Override public void run() { start(); } };

        void start() {
            if (cam == null) return;
            handler.removeCallbacks(retry);
            playing = false;
            lastStart = System.currentTimeMillis();
            lastMove = lastStart;
            lastPos = -1;
            msg.setText("Menyambung…");
            msg.setVisibility(View.VISIBLE);
            final int g = ++gen;
            final MediaPlayer old = mp;
            mp = null;
            if (!surfaceReady) { releaseLater(old); return; }   // surfaceCreated akan memanggil start()
            final SurfaceHolder holder = surface.getHolder();
            final String url = "http://127.0.0.1:" + cfg.getWebPort() + "/live/" + Uri.encode(cam) + "/index.m3u8";
            io.execute(() -> {
                if (old != null) try { old.release(); } catch (Exception ignored) { }
                if (g != gen || isFinishing()) return;
                // Dibuat di thread tanpa Looper -> callback dikirim ke thread utama (ringan).
                MediaPlayer m = new MediaPlayer();
                try {
                    m.setOnPreparedListener(p -> {
                        if (g != gen) return;
                        try { p.setVolume(0f, 0f); p.start(); } catch (Exception ignored) { }
                    });
                    m.setOnInfoListener((p, what, extra) -> {
                        if (g == gen && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            playing = true;
                            msg.setVisibility(View.GONE);
                        }
                        return false;
                    });
                    m.setOnErrorListener((p, what, extra) -> { if (g == gen) fail(); return true; });
                    m.setOnCompletionListener(p -> { if (g == gen) fail(); });   // stream berhenti -> sambung ulang
                    m.setDataSource(url);
                    m.setDisplay(holder);
                    m.setScreenOnWhilePlaying(true);
                    if (g != gen) { m.release(); return; }
                    mp = m;
                    m.prepareAsync();
                } catch (Exception e) {
                    try { m.release(); } catch (Exception ignored) { }
                    runOnUiThread(() -> { if (g == gen) fail(); });
                }
            });
        }

        void fail() {
            playing = false;
            msg.setText("Tidak ada sinyal — menyambung ulang…");
            msg.setVisibility(View.VISIBLE);
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, 4000);
        }

        void stop() {
            handler.removeCallbacks(retry);
            gen++;
            MediaPlayer old = mp;
            mp = null;
            releaseLater(old);
        }
    }

    private void releaseLater(final MediaPlayer m) {
        if (m == null) return;
        io.execute(() -> { try { m.release(); } catch (Exception ignored) { } });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.install(this);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        cfg = new Config(this);
        layout = getSharedPreferences("live", MODE_PRIVATE).getInt("layout", 4);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // ---- Bar atas ----
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.rgb(26, 31, 38));
        bar.setPadding(dp(8), dp(2), dp(8), dp(2));
        TextView title = new TextView(this);
        title.setText("STB NVR · Live  ");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        bar.addView(title);
        for (final int l : LAYOUTS) {
            Button bt = smallButton(String.valueOf(l));
            bt.setOnClickListener(v -> setLayout(l));
            bar.addView(bt);
        }
        Button prev = smallButton("◀");
        prev.setOnClickListener(v -> changePage(-1));
        bar.addView(prev);
        tvPage = new TextView(this);
        tvPage.setTextColor(Color.LTGRAY);
        tvPage.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(tvPage);
        Button next = smallButton("▶");
        next.setOnClickListener(v -> changePage(1));
        bar.addView(next);
        Button settings = smallButton("⚙ Pengaturan");
        settings.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        bar.addView(settings);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        tvClock = new TextView(this);
        tvClock.setTextColor(Color.WHITE);
        tvClock.setTextSize(16);
        bar.addView(tvClock);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gridBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecorderService.start(this);
        build();
        handler.post(clock);
        handler.postDelayed(watchdog, 10_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clock);
        handler.removeCallbacks(watchdog);
        for (Tile t : tiles) t.stop();
    }

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            tvClock.setText(new SimpleDateFormat("EEE, dd MMM yyyy  HH:mm:ss", new Locale("id", "ID")).format(new Date()));
            handler.postDelayed(this, 1000);
        }
    };

    /** Tile yang tidak mulai main, atau gambarnya berhenti bergerak -> sambung ulang. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            final List<Tile> snap = new ArrayList<>(tiles);
            // Posisi video dibaca di latar (panggilan ke mediaserver bisa lambat), lalu diputuskan di thread utama.
            io.execute(() -> {
                final int[] pos = new int[snap.size()];
                for (int i = 0; i < snap.size(); i++) {
                    MediaPlayer m = snap.get(i).mp;
                    pos[i] = -1;
                    if (m != null) try { pos[i] = m.getCurrentPosition(); } catch (Exception ignored) { }
                }
                runOnUiThread(() -> {
                    long now = System.currentTimeMillis();
                    for (int i = 0; i < snap.size(); i++) {
                        Tile t = snap.get(i);
                        if (t.cam == null || !tiles.contains(t)) continue;
                        if (!t.playing) {
                            if (now - t.lastStart > 20_000) t.start();
                            continue;
                        }
                        if (pos[i] != t.lastPos) { t.lastPos = pos[i]; t.lastMove = now; }
                        else if (now - t.lastMove > 12_000) {
                            t.start();
                            t.msg.setText("Gambar macet — menyambung ulang…");
                        }
                    }
                });
            });
            handler.postDelayed(this, 3_000);
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Tile t : tiles) t.stop();
        io.shutdown();   // release yang sudah antre tetap dijalankan
    }

    // ---- Susun grid ----
    private void build() {
        for (Tile t : tiles) t.stop();
        tiles.clear();
        gridBox.removeAllViews();

        List<String> cams = new ArrayList<>();
        for (Config.Camera c : cfg.getCameras()) cams.add(c.name);

        int n = solo != null ? 1 : layout;
        int cols = (int) Math.round(Math.sqrt(n));
        int pages = Math.max(1, (cams.size() + n - 1) / n);
        if (page >= pages) page = pages - 1;
        tvPage.setText(solo != null ? "perbesar" : (page + 1) + "/" + pages);

        if (!cfg.isWebEnabled()) {
            gridBox.addView(message("Live view butuh Web server aktif.\nAktifkan di Pengaturan."),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }
        if (cams.isEmpty()) {
            gridBox.addView(message("Belum ada kamera.\nTambahkan di Pengaturan."),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        for (int row = 0; row < cols; row++) {
            LinearLayout line = new LinearLayout(this);
            gridBox.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                String cam = solo != null ? solo : (page * n + i < cams.size() ? cams.get(page * n + i) : null);
                Tile t = makeTile(cam);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(dp(1), dp(1), dp(1), dp(1));
                line.addView(t.frame, lp);
                tiles.add(t);
            }
        }
        // Pemutar dimulai otomatis begitu permukaan video siap (surfaceCreated).
        if (!tiles.isEmpty()) tiles.get(0).frame.requestFocus();
    }

    private Tile makeTile(final String cam) {
        final Tile t = new Tile();
        t.cam = cam;
        t.frame = new FrameLayout(this);
        t.frame.setBackgroundColor(Color.rgb(11, 13, 16));
        t.frame.setFocusable(true);

        if (cam != null) {
            t.surface = new SurfaceView(this);
            t.surface.setFocusable(false);
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            t.frame.addView(t.surface, vlp);
            t.surface.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder h) {
                    t.surfaceReady = true;
                    if (tiles.contains(t)) t.start();
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) {
                    t.surfaceReady = false;
                    t.stop();
                }
            });

            TextView lbl = new TextView(this);
            lbl.setText("● " + cam);
            lbl.setTextColor(Color.WHITE);
            lbl.setTextSize(13);
            lbl.setBackgroundColor(Color.argb(140, 0, 0, 0));
            lbl.setPadding(dp(6), dp(2), dp(6), dp(2));
            t.frame.addView(lbl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
        }

        t.msg = new TextView(this);
        t.msg.setTextColor(Color.GRAY);
        t.msg.setGravity(Gravity.CENTER);
        t.msg.setText(cam == null ? "—" : "Menyambung…");
        t.frame.addView(t.msg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Bingkai kuning saat dipilih dengan remote
        final GradientDrawable border = new GradientDrawable();
        border.setColor(Color.TRANSPARENT);
        border.setStroke(dp(3), Color.rgb(245, 181, 74));
        t.frame.setOnFocusChangeListener((v, has) -> t.frame.setForeground(has ? border : null));
        t.frame.setOnClickListener(v -> toggleSolo(cam));
        return t;
    }

    private TextView message(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(Color.LTGRAY);
        tv.setTextSize(20);
        tv.setGravity(Gravity.CENTER);
        return tv;
    }

    private Button smallButton(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(14);
        b.setMinWidth(dp(48));
        b.setMinimumWidth(dp(48));
        return b;
    }

    private void setLayout(int l) {
        layout = l;
        solo = null;
        page = 0;
        getSharedPreferences("live", MODE_PRIVATE).edit().putInt("layout", l).apply();
        build();
    }

    private void changePage(int d) {
        solo = null;
        page = Math.max(0, page + d);
        build();
    }

    private void toggleSolo(String cam) {
        if (cam == null) return;
        solo = solo == null ? cam : null;
        build();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_1: setLayout(1); return true;
            case KeyEvent.KEYCODE_4: setLayout(4); return true;
            case KeyEvent.KEYCODE_9: setLayout(9); return true;
            case KeyEvent.KEYCODE_6: setLayout(16); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                changePage(1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                changePage(-1); return true;
            case KeyEvent.KEYCODE_BACK:
                if (solo != null) { solo = null; build(); return true; }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
