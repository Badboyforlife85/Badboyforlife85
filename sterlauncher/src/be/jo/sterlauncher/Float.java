package be.jo.sterlauncher;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Zwevend Ster-paneel bovenop Waze/Maps: klok, muziek, Homey-knoppen en terug naar Ster.
 * Tik op ◂ om in te klappen tot een kleine knop; versleep het paneel naar waar je wil.
 */
public class Float extends Service {
    static final int CARD = 0xEE11161D, TEXT = 0xFFE8EDF2, MUTED = 0xFF8B96A3, BLUE = 0xFF3FA9F5;

    static void startIfAllowed(Context c) {
        SharedPreferences p = c.getSharedPreferences("ster", MODE_PRIVATE);
        if (!p.getBoolean("floatOn", true)) return;
        if (!Settings.canDrawOverlays(c)) return;
        try { c.startService(new Intent(c, Float.class)); } catch (Exception ignored) {}
    }

    private WindowManager wm;
    private LinearLayout panel, body;
    private TextView clock, play, track;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float dp;
    private boolean collapsed = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (panel == null) show();
        return START_NOT_STICKY;
    }

    private void show() {
        dp = getResources().getDisplayMetrics().density;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        SharedPreferences prefs = getSharedPreferences("ster", MODE_PRIVATE);

        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(CARD); bg.setCornerRadius(px(22)); bg.setStroke(px(1), 0xFF2A333F);
        panel.setBackground(bg); panel.setPadding(px(8), px(10), px(8), px(10));

        TextView toggle = t("◂", 20, MUTED); toggle.setOnClickListener(v -> collapse(!collapsed));
        panel.addView(toggle, new LinearLayout.LayoutParams(px(96), px(36)));

        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(body);
        clock = t("", 26, TEXT); clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); body.addView(clock);
        track = t("", 11, MUTED); track.setMaxLines(2); body.addView(track, new LinearLayout.LayoutParams(px(96), -2));

        TextView prev = btn("⏮"), next = btn("⏭"); play = btn("▶");
        GradientDrawable pb = new GradientDrawable(); pb.setShape(GradientDrawable.OVAL); pb.setColor(BLUE); play.setBackground(pb);
        play.setTextColor(Color.WHITE);
        prev.setOnClickListener(v -> media(0)); play.setOnClickListener(v -> media(1)); next.setOnClickListener(v -> media(2));
        body.addView(prev); body.addView(play); body.addView(next);

        for (int i = 1; i <= 3; i++) {
            String label = prefs.getString("b" + i + "l", ""), flow = prefs.getString("b" + i + "f", label);
            if (label.isEmpty()) continue;
            TextView b = t(label, 12, TEXT); b.setMaxLines(2);
            GradientDrawable g = new GradientDrawable(); g.setColor(0xFF1E2631); g.setCornerRadius(px(14)); b.setBackground(g);
            b.setPadding(px(6), px(10), px(6), px(10));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(px(96), -2); bl.topMargin = px(6);
            body.addView(b, bl);
            b.setOnClickListener(v -> runFlow(flow));
        }
        TextView home = t("⌂  Ster", 14, BLUE); home.setPadding(0, px(10), 0, px(4));
        home.setOnClickListener(v -> { Intent h = new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(h); stopSelf(); });
        body.addView(home);
        TextView close = t("✕", 16, MUTED); close.setOnClickListener(v -> stopSelf()); body.addView(close);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(-2, -2, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END; lp.x = px(12); lp.y = prefs.getInt("floatY", px(80));
        dragOn(toggle);
        wm.addView(panel, lp);
        ui.post(tick);
    }

    private void collapse(boolean c) {
        collapsed = c; body.setVisibility(c ? View.GONE : View.VISIBLE);
        ((TextView) panel.getChildAt(0)).setText(c ? "▸ Ster" : "◂");
    }

    /** Verslepen aan de bovenrand; positie wordt onthouden. */
    private void dragOn(View handle) {
        final float[] start = new float[3];
        handle.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: start[0] = e.getRawY(); start[1] = lp.y; start[2] = 0; return false;
                case MotionEvent.ACTION_MOVE:
                    float dy = e.getRawY() - start[0];
                    if (Math.abs(dy) > px(8)) { start[2] = 1; lp.y = (int) Math.max(0, start[1] + dy); wm.updateViewLayout(panel, lp); }
                    return start[2] == 1;
                case MotionEvent.ACTION_UP:
                    if (start[2] == 1) { getSharedPreferences("ster", MODE_PRIVATE).edit().putInt("floatY", lp.y).apply(); return true; }
                    return false;
            }
            return false;
        });
    }

    private final Runnable tick = new Runnable() { public void run() {
        if (clock == null) return;
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        MediaController m = controller();
        if (m != null && m.getMetadata() != null) {
            String title = m.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
            track.setText(title != null ? title : "");
            PlaybackState s = m.getPlaybackState();
            play.setText(s != null && s.getState() == PlaybackState.STATE_PLAYING ? "❚❚" : "▶");
        } else if (Radio.playing()) { track.setText(Radio.current()); play.setText("❚❚"); }
        else track.setText("");
        ui.postDelayed(this, 1000);
    }};

    private MediaController controller() {
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            for (MediaController c : l) { PlaybackState s = c.getPlaybackState(); if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c; }
            return l.isEmpty() ? null : l.get(0);
        } catch (Exception e) { return null; }
    }

    private void media(int a) {
        if (Radio.playing() && a == 1) { Radio.stop(); return; }
        MediaController c = controller(); if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if (a == 0) t.skipToPrevious(); else if (a == 2) t.skipToNext();
        else { PlaybackState s = c.getPlaybackState(); if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play(); }
    }

    private void runFlow(String flow) {
        SharedPreferences p = getSharedPreferences("ster", MODE_PRIVATE);
        String u = p.getString("hUrl", ""), k = p.getString("hKey", "");
        Toast.makeText(this, "Bezig: " + flow + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> { String msg;
            try { msg = Smart.triggerFlow(u, k, flow); } catch (Exception e) { msg = "Homey niet bereikbaar"; }
            final String m = msg; ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }).start();
    }

    @Override public void onDestroy() {
        super.onDestroy(); ui.removeCallbacks(tick);
        if (panel != null) try { wm.removeView(panel); } catch (Exception ignored) {}
        panel = null; clock = null;
    }

    private TextView t(String s, float size, int col) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(col); v.setGravity(Gravity.CENTER); return v; }
    private TextView btn(String s) {
        TextView v = t(s, 18, 0xFFC9D1D9);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(0xFF1E2631); v.setBackground(g);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(52), px(52)); l.topMargin = px(6); v.setLayoutParams(l); return v;
    }
    private int px(float v) { return Math.round(v * dp); }
}
