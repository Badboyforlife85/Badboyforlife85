package be.jo.sterlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * HEMS-scherm: links je eigen HEMS-dashboard (webpagina) live, rechts grote cijfers en je Homey-knoppen.
 * Zonder dashboard-adres gebruikt het hele scherm de knoppen en cijfers.
 */
public class HemsActivity extends Activity {
    static final int BG = 0xFF07090D, CARD = 0xFF151B23, TEXT = 0xFFE8EDF2, MUTED = 0xFF8B96A3, BLUE = 0xFF3FA9F5,
            GREEN = 0xFF3DDC84, AMBER = 0xFFFFB547;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs; private float dp;
    private TextView vSolar, vBatt, vPrice;
    private WebView web;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("ster", MODE_PRIVATE); dp = getResources().getDisplayMetrics().density;
        setContentView(build());
    }
    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }
    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    private View build() {
        LinearLayout root = new LinearLayout(this); root.setBackgroundColor(BG); root.setPadding(px(16), px(14), px(16), px(14));
        String url = prefs.getString("hemsUrl", "");
        boolean portrait = getResources().getDisplayMetrics().heightPixels > getResources().getDisplayMetrics().widthPixels;
        root.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        if (!url.isEmpty()) {
            FrameLayout wf = new FrameLayout(this);
            GradientDrawable g = round(CARD, 22); wf.setBackground(g); wf.setClipToOutline(true);
            web = new WebView(this);
            WebSettings ws = web.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
            ws.setUseWideViewPort(true); ws.setLoadWithOverviewMode(true); ws.setSupportZoom(true); ws.setBuiltInZoomControls(true);
            ws.setDisplayZoomControls(false);
            web.setWebViewClient(new WebViewClient()); web.setWebChromeClient(new WebChromeClient());
            web.setBackgroundColor(BG);
            web.loadUrl(url);
            wf.addView(web, new FrameLayout.LayoutParams(-1, -1));
            root.addView(wf, portrait ? new LinearLayout.LayoutParams(-1, 0, 1.3f) : new LinearLayout.LayoutParams(0, -1, 1.7f));
            View gap = new View(this); root.addView(gap, new LinearLayout.LayoutParams(px(14), px(14)));
        }

        ScrollView sv = new ScrollView(this);
        LinearLayout side = new LinearLayout(this); side.setOrientation(LinearLayout.VERTICAL); sv.addView(side);
        root.addView(sv, portrait ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = t("‹", 30, BLUE); back.setOnClickListener(v -> finish()); head.addView(back, new LinearLayout.LayoutParams(px(44), -2));
        TextView title = t("HEMS", 26, TEXT); title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); head.addView(title);
        side.addView(head);

        LinearLayout stats = new LinearLayout(this); stats.setPadding(0, px(10), 0, px(6));
        vSolar = stat(stats, "☀ ZON", AMBER); vBatt = stat(stats, "▮ BATTERIJ", GREEN); vPrice = stat(stats, "€ PRIJS", BLUE);
        side.addView(stats);

        boolean any = false;
        for (int i = 1; i <= 6; i++) {
            String label = prefs.getString("b" + i + "l", ""), flow = prefs.getString("b" + i + "f", label);
            if (label.isEmpty()) continue; any = true;
            TextView btn = t(label, 20, TEXT); btn.setBackground(round(0xFF1E2631, 20)); btn.setPadding(px(14), px(22), px(14), px(22));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2); bl.topMargin = px(10); side.addView(btn, bl);
            btn.setOnClickListener(v -> runFlow(flow, btn));
        }
        if (!any) { TextView h = t("Kies je knoppen op het startscherm via \"+ Flow\"", 14, MUTED); h.setPadding(0, px(16), 0, 0); side.addView(h); }
        if (url.isEmpty()) { TextView h = t("Tip: vul je HEMS-dashboardadres in bij Ster-instellingen om het hier live te zien.", 13, MUTED);
            h.setPadding(0, px(16), 0, 0); side.addView(h); }
        return root;
    }

    private TextView stat(LinearLayout row, String label, int col) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        c.setBackground(round(CARD, 18)); c.setPadding(px(6), px(12), px(6), px(12));
        TextView l = t(label, 11, col); l.setLetterSpacing(0.1f); c.addView(l);
        TextView v = t("–", 24, TEXT); v.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL)); c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f); lp.setMargins(px(4), 0, px(4), 0); row.addView(c, lp);
        return v;
    }

    private final Runnable refresh = new Runnable() { public void run() {
        String u = prefs.getString("hUrl", ""), k = prefs.getString("hKey", "");
        if (!u.isEmpty() && !k.isEmpty()) new Thread(() -> { try {
            String[] v = Smart.variables(u, k, prefs.getString("vSolar", "zon_vermogen"), prefs.getString("vBatt", "batterij_soc"), prefs.getString("vPrice", "stroomprijs"));
            ui.post(() -> { vSolar.setText(v[0].isEmpty() ? "–" : v[0] + " W"); vBatt.setText(v[1].isEmpty() ? "–" : v[1] + " %");
                vPrice.setText(v[2].isEmpty() ? "–" : "€" + v[2]); });
        } catch (Exception ignored) {} }).start();
        ui.postDelayed(this, 30000);
    }};

    private void runFlow(String flow, TextView btn) {
        String u = prefs.getString("hUrl", ""), k = prefs.getString("hKey", "");
        btn.setAlpha(0.5f);
        new Thread(() -> { String msg;
            try { msg = Smart.triggerFlow(u, k, flow); } catch (Exception e) { msg = "Homey niet bereikbaar"; }
            final String m = msg; ui.post(() -> { btn.setAlpha(1f); Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }); }).start();
    }

    private TextView t(String s, float sz, int col) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sz); v.setTextColor(col); return v; }
    private GradientDrawable round(int col, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(col); g.setCornerRadius(px(r)); return g; }
    private int px(float v) { return Math.round(v * dp); }
}
