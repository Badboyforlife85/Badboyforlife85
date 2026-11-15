package be.jo.sterlauncher;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV-scherm voor een Xtream Codes-abonnement (de login die IPTV-apps vragen).
 * Login komt uit Ster-instellingen of – als ingesteld – live uit Homey-variabelen,
 * zodat je ze van op je GSM kan aanpassen.
 */
public class TvActivity extends Activity {
    static final int BG = 0xFF07090D, CARD = 0xFF151B23, SEL = 0xFF1F3A55, TEXT = 0xFFE8EDF2, MUTED = 0xFF8B96A3, BLUE = 0xFF3FA9F5;

    static class Ch { String id, name, icon, cat; }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> logos = new LruCache<>(300);
    private SharedPreferences prefs;
    private float dp;
    private String host, user, pass;
    private final List<String[]> cats = new ArrayList<>();
    private final List<Ch> all = new ArrayList<>();
    private Set<String> favs;
    private String curCat = "★", query = "";
    private LinearLayout catList; private GridLayout grid; private TextView status;
    private FrameLayout root; private VideoView video; private TextView osd;
    private List<Ch> shown = new ArrayList<>(); private int playing = -1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("ster", MODE_PRIVATE);
        favs = new HashSet<>(prefs.getStringSet("tvFavs", new HashSet<>()));
        dp = getResources().getDisplayMetrics().density;
        setContentView(build());
        Radio.stop();
        loadLogin();
    }

    @Override protected void onPause() { super.onPause(); if (video != null) video.stopPlayback(); finishIfDriving(); }
    @Override public void onBackPressed() { if (video != null) closePlayer(); else super.onBackPressed(); }
    @Override protected void onDestroy() { super.onDestroy(); pool.shutdownNow(); }

    private void finishIfDriving() { if (Home.drivingNow) finish(); }

    // ---------- Login ----------
    private void loadLogin() {
        status.setText("Inloggen…");
        pool.execute(() -> {
            String h = prefs.getString("tvHost", ""), u = prefs.getString("tvUser", ""), p = prefs.getString("tvPass", "");
            // Externe modus: login uit Homey-variabelen (iptv_host / iptv_user / iptv_pass)
            if (prefs.getBoolean("tvFromHomey", false)) {
                try {
                    String[] v = Smart.variables(prefs.getString("hUrl", ""), prefs.getString("hKey", ""), "iptv_host", "iptv_user", "iptv_pass");
                    if (!v[0].isEmpty()) h = v[0];
                    if (!v[1].isEmpty()) u = v[1];
                    if (!v[2].isEmpty()) p = v[2];
                    prefs.edit().putString("tvHost", h).putString("tvUser", u).putString("tvPass", p).apply(); // laatst werkende bewaren
                } catch (Exception e) { ui.post(() -> toast("Homey niet bereikbaar – laatst bekende login gebruikt")); }
            }
            host = h.replaceAll("/+$", ""); user = u; pass = p;
            if (host.isEmpty() || user.isEmpty()) { ui.post(() -> status.setText("Vul je TV-login in via ✦ Ster-instellingen")); return; }
            if (!host.startsWith("http")) host = "http://" + host;
            loadChannels();
        });
    }

    private String api(String action) throws Exception {
        return get(host + "/player_api.php?username=" + Uri.encode(user) + "&password=" + Uri.encode(pass) + "&action=" + action);
    }

    private void loadChannels() {
        try {
            JSONArray c = new JSONArray(api("get_live_categories"));
            JSONArray s = new JSONArray(api("get_live_streams"));
            List<String[]> nc = new ArrayList<>(); List<Ch> na = new ArrayList<>();
            for (int i = 0; i < c.length(); i++) { JSONObject o = c.getJSONObject(i);
                nc.add(new String[]{o.optString("category_id"), o.optString("category_name")}); }
            for (int i = 0; i < s.length(); i++) { JSONObject o = s.getJSONObject(i); Ch ch = new Ch();
                ch.id = o.optString("stream_id"); ch.name = o.optString("name").trim(); ch.icon = o.optString("stream_icon");
                ch.cat = o.optString("category_id"); na.add(ch); }
            ui.post(() -> { cats.clear(); cats.addAll(nc); all.clear(); all.addAll(na);
                if (favs.isEmpty()) curCat = cats.isEmpty() ? "*" : cats.get(0)[0];
                fillCats(); fillGrid(); status.setText(all.size() + " zenders"); });
        } catch (Exception e) {
            ui.post(() -> status.setText("Inloggen mislukt – klopt je login nog? (" + e.getMessage() + ")"));
        }
    }

    // ---------- UI ----------
    private View build() {
        root = new FrameLayout(this); root.setBackgroundColor(BG);
        LinearLayout main = new LinearLayout(this); main.setPadding(px(20), px(16), px(20), px(16));
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        main.addView(left, new LinearLayout.LayoutParams(px(250), -1));
        TextView back = txt("‹  Terug", 18, BLUE); back.setPadding(px(6), px(6), 0, px(12));
        back.setOnClickListener(v -> finish()); left.addView(back);
        ScrollView cs = new ScrollView(this); catList = new LinearLayout(this); catList.setOrientation(LinearLayout.VERTICAL);
        cs.addView(catList); left.addView(cs, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL); right.setPadding(px(18), 0, 0, 0);
        main.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(this); search.setHint("Zoek zender…"); search.setHintTextColor(MUTED); search.setTextColor(TEXT);
        search.setSingleLine(true); search.setBackground(round(CARD, 20)); search.setPadding(px(18), px(10), px(18), px(10));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString().toLowerCase(Locale.ROOT).trim(); fillGrid(); }
            public void afterTextChanged(Editable s) {}
        });
        top.addView(search, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView reload = txt("⟳", 24, TEXT); reload.setGravity(Gravity.CENTER);
        reload.setOnClickListener(v -> loadLogin()); top.addView(reload, new LinearLayout.LayoutParams(px(56), px(48)));
        right.addView(top);
        status = txt("", 13, MUTED); status.setPadding(px(4), px(8), 0, px(8)); right.addView(status);
        ScrollView gs = new ScrollView(this); grid = new GridLayout(this); grid.setColumnCount(4);
        gs.addView(grid); right.addView(gs, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    private void fillCats() {
        catList.removeAllViews();
        addCat("★", "★  Favorieten"); addCat("*", "Alle zenders");
        for (String[] c : cats) addCat(c[0], c[1]);
    }
    private void addCat(String id, String name) {
        TextView t = txt(name, 16, TEXT); t.setSingleLine(true); t.setPadding(px(14), px(12), px(10), px(12));
        t.setBackground(round(id.equals(curCat) ? SEL : 0x00000000, 14));
        t.setOnClickListener(v -> { curCat = id; fillCats(); fillGrid(); });
        catList.addView(t);
    }

    private void fillGrid() {
        grid.removeAllViews(); shown = new ArrayList<>();
        for (Ch c : all) {
            if (!query.isEmpty()) { if (!c.name.toLowerCase(Locale.ROOT).contains(query)) continue; }
            else if (curCat.equals("★")) { if (!favs.contains(c.id)) continue; }
            else if (!curCat.equals("*") && !curCat.equals(c.cat)) continue;
            shown.add(c);
            if (shown.size() >= 400) break;   // trage radio's blijven vlot
        }
        if (shown.isEmpty() && curCat.equals("★") && query.isEmpty())
            status.setText("Nog geen favorieten – druk lang op een zender om hem toe te voegen");
        for (int i = 0; i < shown.size(); i++) grid.addView(cell(shown.get(i), i));
    }

    private View cell(Ch c, int idx) {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER);
        v.setBackground(round(CARD, 18)); v.setPadding(px(8), px(12), px(8), px(10));
        ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.addView(iv, new LinearLayout.LayoutParams(-1, px(54)));
        logo(c.icon, iv);
        TextView t = txt((favs.contains(c.id) ? "★ " : "") + c.name, 14, TEXT); t.setGravity(Gravity.CENTER); t.setMaxLines(2);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = px(6); v.addView(t, tl);
        GridLayout.LayoutParams gl = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        gl.width = 0; gl.setMargins(px(6), px(6), px(6), px(6)); v.setLayoutParams(gl);
        v.setOnClickListener(x -> play(idx));
        v.setOnLongClickListener(x -> { if (!favs.remove(c.id)) favs.add(c.id);
            prefs.edit().putStringSet("tvFavs", new HashSet<>(favs)).apply();
            toast(favs.contains(c.id) ? "★ Toegevoegd aan favorieten" : "Verwijderd uit favorieten"); fillGrid(); return true; });
        return v;
    }

    // ---------- Afspelen ----------
    private void play(int idx) {
        if (Home.drivingNow) { toast("TV kijken kan niet tijdens het rijden"); return; }
        if (idx < 0 || idx >= shown.size()) return;
        playing = idx; Ch c = shown.get(idx);
        if (video == null) {
            FrameLayout f = new FrameLayout(this); f.setBackgroundColor(0xFF000000);
            video = new VideoView(this);
            FrameLayout.LayoutParams vl = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER); f.addView(video, vl);
            osd = txt("", 20, TEXT); osd.setBackground(round(0xAA000000, 16)); osd.setPadding(px(18), px(10), px(18), px(10));
            FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); ol.setMargins(px(20), px(20), 0, 0);
            f.addView(osd, ol);
            // Links/rechts tikken = vorige/volgende zender, midden = info tonen, terug = sluiten
            f.setOnTouchListener((x, e) -> { if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                float w = x.getWidth(), px = e.getX();
                if (px < w * 0.25f) play(Math.max(0, playing - 1)); else if (px > w * 0.75f) play(Math.min(shown.size() - 1, playing + 1));
                else showOsd(); } return true; });
            root.addView(f, new FrameLayout.LayoutParams(-1, -1));
        }
        osdText(c.name + "  ·  laden…");
        final String base = host + "/live/" + Uri.encode(user) + "/" + Uri.encode(pass) + "/" + c.id;
        video.setOnPreparedListener(mp -> { osdText(c.name); mp.start(); });
        video.setOnErrorListener((mp, w, e) -> {   // eerst HLS (.m3u8), lukt dat niet: .ts
            if (!String.valueOf(video.getTag()).endsWith(".ts")) { start(base + ".ts"); return true; }
            osdText(c.name + "  ·  niet beschikbaar"); return true; });
        start(base + ".m3u8");
    }
    private void start(String url) { video.setTag(url); video.setVideoURI(Uri.parse(url)); video.start(); }
    private void osdText(String s) { osd.setText(s); showOsd(); }
    private void showOsd() { osd.setVisibility(View.VISIBLE); osd.removeCallbacks(hideOsd); osd.postDelayed(hideOsd, 3500); }
    private final Runnable hideOsd = () -> { if (osd != null) osd.setVisibility(View.GONE); };
    private void closePlayer() { video.stopPlayback(); root.removeView((View) video.getParent()); video = null; osd = null; }

    // ---------- Hulp ----------
    private void logo(String url, ImageView iv) {
        if (url == null || !url.startsWith("http")) return;
        Bitmap b = logos.get(url); if (b != null) { iv.setImageBitmap(b); return; }
        iv.setTag(url);
        pool.execute(() -> { try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setConnectTimeout(5000); c.setReadTimeout(8000);
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 2;
            Bitmap bm; try (InputStream in = c.getInputStream()) { bm = BitmapFactory.decodeStream(in, null, o); }
            if (bm != null) { logos.put(url, bm); ui.post(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); }); }
        } catch (Exception ignored) {} });
    }
    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(30000); c.setRequestProperty("User-Agent", "SterLauncher");
        int code = c.getResponseCode(); if (code >= 400) throw new Exception("server " + code);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n); return b.toString("UTF-8");
        }
    }
    private TextView txt(String s, float sz, int col) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sz); t.setTextColor(col);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); return t; }
    private GradientDrawable round(int col, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(col); g.setCornerRadius(px(r)); return g; }
    private int px(float v) { return Math.round(v * dp); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
