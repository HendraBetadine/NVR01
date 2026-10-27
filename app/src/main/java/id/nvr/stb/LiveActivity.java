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
import android.widget.TextView;
import android.widget.VideoView;

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

    private class Tile {
        FrameLayout frame;
        VideoView video;
        TextView msg;
        String cam;
        long lastStart;
        boolean playing;
        final Runnable retry = new Runnable() { @Override public void run() { start(); } };

        void start() {
            if (cam == null) return;
            handler.removeCallbacks(retry);
            playing = false;
            lastStart = System.currentTimeMillis();
            msg.setText("Menyambung…");
            msg.setVisibility(View.VISIBLE);
            try {
                video.stopPlayback();
                video.setVideoURI(Uri.parse("http://127.0.0.1:" + cfg.getWebPort() + "/live/"
                        + Uri.encode(cam) + "/index.m3u8"));
                video.start();
            } catch (Exception e) {
                fail();
            }
        }

        void fail() {
            playing = false;
            msg.setText("Tidak ada sinyal");
            msg.setVisibility(View.VISIBLE);
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, 5000);
        }

        void stop() {
            handler.removeCallbacks(retry);
            try { video.stopPlayback(); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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

    /** Tile yang lama tidak mulai main -> sambung ulang. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            for (Tile t : tiles) {
                if (t.cam != null && !t.playing && now - t.lastStart > 20_000) t.start();
            }
            handler.postDelayed(this, 10_000);
        }
    };

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
        for (Tile t : tiles) t.start();
        if (!tiles.isEmpty()) tiles.get(0).frame.requestFocus();
    }

    private Tile makeTile(final String cam) {
        final Tile t = new Tile();
        t.cam = cam;
        t.frame = new FrameLayout(this);
        t.frame.setBackgroundColor(Color.rgb(11, 13, 16));
        t.frame.setFocusable(true);

        if (cam != null) {
            t.video = new VideoView(this);
            t.video.setFocusable(false);
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            t.frame.addView(t.video, vlp);
            t.video.setOnPreparedListener(mp -> {
                mp.setVolume(0f, 0f);
                t.playing = true;
                t.msg.setVisibility(View.GONE);
            });
            t.video.setOnErrorListener((mp, what, extra) -> { t.fail(); return true; });
            t.video.setOnCompletionListener(mp -> t.fail());   // stream live berhenti -> sambung ulang
            t.video.setOnInfoListener((mp, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    t.playing = true;
                    t.msg.setVisibility(View.GONE);
                }
                return false;
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
