package be.jo.sterlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.Manifest;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Calendar;

/**
 * Ster Launcher – een strak startscherm voor Android-autoradio's (Mercedes-stijl).
 * Eén bestand, geen externe libraries: snel en licht op trage head units.
 */
public class Home extends Activity {

    // ---------- Design ----------
    static final int BG_TOP = 0xFF05070A, BG_BOTTOM = 0xFF121821;
    static final int CARD = 0xFF151B23, CARD_PRESSED = 0xFF1E2631, STROKE = 0xFF2A333F;
    static final int TEXT = 0xFFE8EDF2, MUTED = 0xFF8B96A3, SILVER = 0xFFC9D1D9;
    static final int BLUE = 0xFF3FA9F5, GREEN = 0xFF3DDC84, RED = 0xFFFF5A5F, AMBER = 0xFFFFB547;

    /** Een tegel: naam, symbool, kleur, kandidaat-pakketten, zoekwoorden in app-namen, en een reserve-actie. */
    static class Tile {
        final String id, label, glyph; final int color; final String[] pkgs, keywords; final Runnable fallback;
        Tile(String id, String label, String glyph, int color, String[] pkgs, String[] keywords, Runnable fallback) {
            this.id = id; this.label = label; this.glyph = glyph; this.color = color;
            this.pkgs = pkgs; this.keywords = keywords; this.fallback = fallback;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PackageManager pm;
    private float dp;
    private TextView clock, date, btChip, wifiChip, trackTitle, trackArtist, playBtn;
    private FrameLayout root;
    private View drawer;
    private final List<Tile> tiles = new ArrayList<>();
    // v2
    private TextView speedText, routeTitle, routeSub, homeyLine;
    private LinearLayout homeyButtons;
    private View nightOverlay;
    private final Map<String, View> tileViews = new HashMap<>();
    private boolean driving = false, night = false;
    static volatile boolean drivingNow = false;
    private long lastHomey = 0;
    private double lat = 51.14, lon = 3.41;  // standaard: Meetjesland
    private LocationManager lm;
    private static final String[] DISTRACTING = {"tv", "youtube", "browser", "store"};
    private final LocationListener gps = new LocationListener() {
        public void onLocationChanged(Location l) {
            lat = l.getLatitude(); lon = l.getLongitude();
            float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
            updateDriving(kmh);
        }
        public void onStatusChanged(String p, int s, Bundle b) {}
        public void onProviderEnabled(String p) {}
        public void onProviderDisabled(String p) {}
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("ster", MODE_PRIVATE);
        pm = getPackageManager();
        dp = getResources().getDisplayMetrics().density;
        defineTiles();
        setContentView(buildUi());
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { startGps(); }

    private void startGps() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gps, Looper.getMainLooper());
            Location last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) { lat = last.getLatitude(); lon = last.getLongitude(); }
        } catch (Exception ignored) {}
    }

    @Override protected void onResume() { super.onResume(); startGps(); lastHomey = 0; ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); try { lm.removeUpdates(gps); } catch (Exception ignored) {} }
    @Override public void onBackPressed() { closeDrawer(); }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); Installer.onResult(this, i); }
    @Override protected void onDestroy() { super.onDestroy(); Radio.stop(); }  // Home-scherm sluit nooit af

    // ---------- Tegels ----------
    private void defineTiles() {
        tiles.add(new Tile("waze", "Waze", "➤", BLUE, a("com.waze"), a("waze"), () -> store("com.waze")));
        tiles.add(new Tile("maps", "Kaarten", "◎", GREEN, a("com.google.android.apps.maps"), a("maps", "kaart"),
                () -> store("com.google.android.apps.maps")));
        tiles.add(new Tile("phone", "Telefoon", "✆", GREEN,
                a("com.syu.bt", "com.fyt.bt", "com.microntek.bluetooth", "com.android.bluetooth.phone", "com.zjinnova.zlink"),
                a("bluetooth", "bt phone", "telefoon", "phone"), this::openBluetooth));
        tiles.add(new Tile("carplay", "CarPlay / AA", "▣", SILVER,
                a("com.zjinnova.zlink", "com.zlink.carplay", "com.tlink", "com.suding.speedplay", "com.carlinkit"),
                a("zlink", "tlink", "carplay", "carlink", "android auto", "autokit"),
                () -> toast("Geen CarPlay-app gevonden. Vraag de ZLink-update aan je verkoper.")));
        tiles.add(new Tile("music", "Spotify", "♫", GREEN, a("com.spotify.music"), a("spotify", "music", "muziek"),
                () -> store("com.spotify.music")));
        tiles.add(new Tile("tv", "TV", "▶", RED, a(), a(),
                () -> open(new Intent(this, TvActivity.class))));
        tiles.add(new Tile("youtube", "YouTube", "►", RED, a("com.google.android.youtube"), a("youtube"),
                () -> store("com.google.android.youtube")));
        tiles.add(new Tile("browser", "Browser", "◍", BLUE, a("com.android.chrome", "org.mozilla.firefox", "com.brave.browser"),
                a("chrome", "browser", "firefox"), () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.be")))));
        tiles.add(new Tile("store", "Play Store", "◆", AMBER, a("com.android.vending"), a("play store"),
                () -> toast("Play Store is niet geïnstalleerd op deze radio.")));
        tiles.add(new Tile("radio", "Radio", "≋", AMBER, a("com.syu.radio", "com.microntek.radio"), a("radio", "fm"),
                () -> toast("Geen radio-app gevonden.")));
        tiles.add(new Tile("carset", "Auto-instellingen", "⚙", SILVER,
                a("com.syu.settings", "com.syu.carinfo", "com.fyt.car"), a("car setting", "auto", "car info", "factory"),
                () -> open(new Intent(Settings.ACTION_SETTINGS))));
        tiles.add(new Tile("pack", "Apps installeren", "⇩", GREEN, a(), a(), this::installPack));
        tiles.add(new Tile("ster", "Ster-instellingen", "✦", BLUE, a(), a(), this::openSettings));
        tiles.add(new Tile("apps", "Alle apps", "⋮⋮", MUTED, a(), a(), this::openDrawer));
    }

    private static String[] a(String... s) { return s; }

    /** Zoekt de juiste app: eerst je eigen keuze (lang drukken), dan bekende pakketten, dan op naam. */
    private Intent resolve(Tile t) {
        String chosen = prefs.getString("tile_" + t.id, null);
        if (chosen != null) { Intent i = pm.getLaunchIntentForPackage(chosen); if (i != null) return i; }
        for (String p : t.pkgs) { Intent i = pm.getLaunchIntentForPackage(p); if (i != null) return i; }
        if (t.keywords.length > 0) for (ResolveInfo r : allApps()) {
            String name = r.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            for (String k : t.keywords) if (name.contains(k)) {
                Intent i = pm.getLaunchIntentForPackage(r.activityInfo.packageName); if (i != null) return i;
            }
        }
        return null;
    }

    private void launch(Tile t) {
        if (t.id.equals("apps")) { openDrawer(); return; }
        if (t.id.equals("ster")) { openSettings(); return; }
        if (t.id.equals("pack")) { installPack(); return; }
        Intent i = resolve(t);
        if (i != null) open(i); else t.fallback.run();
    }

    // ---------- Opbouw scherm ----------
    private View buildUi() {
        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{BG_TOP, BG_BOTTOM});
        root.setBackground(bg);

        LinearLayout main = new LinearLayout(this);
        boolean portrait = getResources().getDisplayMetrics().heightPixels > getResources().getDisplayMetrics().widthPixels;
        main.setOrientation(portrait ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        main.setPadding(px(28), px(22), px(28), px(22));
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // Linkerkolom: klok, status, muziek
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        main.addView(left, portrait ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -1, 0.9f));

        clock = text("", portrait ? 72 : 96, TEXT); clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        left.addView(clock);
        date = text("", 20, MUTED); left.addView(date);

        View line = new View(this); line.setBackgroundColor(BLUE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(56), px(3)); lp.setMargins(0, px(14), 0, px(14));
        left.addView(line, lp);

        LinearLayout chips = new LinearLayout(this);
        btChip = chip("Bluetooth"); wifiChip = chip("Wifi");
        btChip.setOnClickListener(v -> openBluetooth());
        wifiChip.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        chips.addView(btChip); chips.addView(wifiChip);
        left.addView(chips);

        speedText = text("", 44, TEXT); speedText.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        speedText.setVisibility(View.GONE);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(-2, -2); spl.topMargin = px(10);
        left.addView(speedText, spl);
        View spacer = new View(this); left.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        left.addView(routeCard(), new LinearLayout.LayoutParams(-1, -2));
        View g2 = new View(this); left.addView(g2, new LinearLayout.LayoutParams(1, px(12)));
        left.addView(mediaCard(), new LinearLayout.LayoutParams(-1, -2));

        View gap = new View(this); main.addView(gap, new LinearLayout.LayoutParams(portrait ? 1 : px(28), portrait ? px(14) : 1));

        // Rechterkolom: tegels
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false);
        main.addView(sv, portrait ? new LinearLayout.LayoutParams(-1, 0, 1f) : new LinearLayout.LayoutParams(0, -1, 2.1f));
        LinearLayout rightCol = new LinearLayout(this); rightCol.setOrientation(LinearLayout.VERTICAL);
        sv.addView(rightCol, new ViewGroup.LayoutParams(-1, -2));
        rightCol.addView(homeyCard());
        rightCol.addView(radioCard());
        GridLayout grid = new GridLayout(this); grid.setColumnCount(portrait ? 3 : 4);
        rightCol.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        tileViews.clear();
        for (Tile t : tiles) { View tv = tileView(t); tileViews.put(t.id, tv); grid.addView(tv); }
        nightOverlay = new View(this); nightOverlay.setBackgroundColor(0xFF000000); nightOverlay.setAlpha(0f);
        nightOverlay.setClickable(false); nightOverlay.setFocusable(false);
        root.addView(nightOverlay, new FrameLayout.LayoutParams(-1, -1));
        driving = false; night = false;
        return root;
    }

    private View tileView(Tile t) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL); v.setGravity(Gravity.CENTER);
        v.setBackground(card(CARD, 26));
        v.setPadding(px(8), px(16), px(8), px(14));

        TextView icon = new TextView(this);
        Drawable appIcon = iconFor(t);
        if (appIcon != null) {
            ImageView iv = new ImageView(this); iv.setImageDrawable(appIcon);
            v.addView(iv, new LinearLayout.LayoutParams(px(56), px(56)));
        } else {
            icon.setText(t.glyph); icon.setTextSize(28); icon.setTextColor(t.color); icon.setGravity(Gravity.CENTER);
            GradientDrawable c = new GradientDrawable(); c.setShape(GradientDrawable.OVAL);
            c.setColor((t.color & 0x00FFFFFF) | 0x22000000); c.setStroke(px(1), (t.color & 0x00FFFFFF) | 0x66000000);
            icon.setBackground(c);
            v.addView(icon, new LinearLayout.LayoutParams(px(56), px(56)));
        }
        TextView label = text(t.label, 15, TEXT); label.setGravity(Gravity.CENTER); label.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = px(10);
        v.addView(label, ll);

        GridLayout.LayoutParams gl = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        gl.width = 0; gl.setMargins(px(7), px(7), px(7), px(7));
        v.setLayoutParams(gl);
        pressable(v);
        v.setOnClickListener(x -> launch(t));
        v.setOnLongClickListener(x -> { if (!t.id.equals("apps") && !t.id.equals("ster") && !t.id.equals("pack")) pickAppFor(t); return true; });
        return v;
    }

    private Drawable iconFor(Tile t) {
        if (t.id.equals("apps") || t.id.equals("ster") || t.id.equals("pack")) return null;
        try { Intent i = resolve(t); if (i != null && i.getComponent() != null) return pm.getActivityIcon(i.getComponent()); }
        catch (Exception ignored) {}
        return null;
    }

    private View mediaCard() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(card(CARD, 26)); c.setPadding(px(20), px(16), px(20), px(14));
        TextView head = text("NU AAN HET SPELEN", 11, BLUE); head.setLetterSpacing(0.15f); c.addView(head);
        trackTitle = text("Geen muziek actief", 20, TEXT); trackTitle.setSingleLine(true); c.addView(trackTitle);
        trackArtist = text("Tik op Spotify of Radio", 14, MUTED); trackArtist.setSingleLine(true); c.addView(trackArtist);

        LinearLayout ctl = new LinearLayout(this); ctl.setGravity(Gravity.CENTER); ctl.setPadding(0, px(10), 0, 0);
        TextView prev = ctlBtn("⏮"), next = ctlBtn("⏭"); playBtn = ctlBtn("▶");
        playBtn.setBackground(circle(BLUE)); playBtn.setTextColor(Color.WHITE);
        prev.setOnClickListener(v -> media(0)); playBtn.setOnClickListener(v -> media(1)); next.setOnClickListener(v -> media(2));
        ctl.addView(prev); ctl.addView(playBtn); ctl.addView(next);
        c.addView(ctl);
        c.setOnClickListener(v -> { MediaController m = controller(); if (m != null) {
            Intent i = pm.getLaunchIntentForPackage(m.getPackageName()); if (i != null) open(i); } });
        return c;
    }

    private TextView ctlBtn(String s) {
        TextView t = text(s, 22, SILVER); t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(px(58), px(58)); l.setMargins(px(10), 0, px(10), 0);
        t.setLayoutParams(l); t.setBackground(circle(CARD_PRESSED)); return t;
    }

    // ---------- App-lade met zoeken ----------
    private void openDrawer() {
        if (drawer != null) return;
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(0xF2070A0E); d.setPadding(px(28), px(22), px(28), px(22));
        d.setClickable(true);

        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        EditText search = new EditText(this);
        search.setHint("Zoek een app…"); search.setHintTextColor(MUTED); search.setTextColor(TEXT); search.setTextSize(18);
        search.setSingleLine(true); search.setBackground(card(CARD, 22)); search.setPadding(px(20), px(12), px(20), px(12));
        top.addView(search, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = ctlBtn("✕"); close.setOnClickListener(v -> closeDrawer()); top.addView(close);
        d.addView(top);

        ScrollView sv = new ScrollView(this);
        GridLayout g = new GridLayout(this); g.setColumnCount(7);
        sv.addView(g);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1f); sl.topMargin = px(16);
        d.addView(sv, sl);

        List<ResolveInfo> apps = allApps();
        Runnable fill = () -> {
            g.removeAllViews();
            String q = search.getText().toString().toLowerCase(Locale.ROOT).trim();
            for (ResolveInfo r : apps) {
                String name = r.loadLabel(pm).toString();
                if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
                g.addView(appCell(r, name, () -> {
                    closeDrawer(); open(pm.getLaunchIntentForPackage(r.activityInfo.packageName)); }));
            }
        };
        fill.run();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) { fill.run(); }
            public void afterTextChanged(Editable s) {}
        });
        drawer = d;
        d.setAlpha(0f); root.addView(d, new FrameLayout.LayoutParams(-1, -1));
        d.animate().alpha(1f).setDuration(160).start();
    }

    private View appCell(ResolveInfo r, String name, Runnable onClick) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
        c.setPadding(px(6), px(12), px(6), px(12));
        ImageView iv = new ImageView(this); iv.setImageDrawable(r.loadIcon(pm));
        c.addView(iv, new LinearLayout.LayoutParams(px(52), px(52)));
        TextView t = text(name, 13, TEXT); t.setGravity(Gravity.CENTER); t.setSingleLine(true);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        GridLayout.LayoutParams gl = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        gl.width = 0; c.setLayoutParams(gl);
        c.setBackground(card(0x00000000, 18)); pressable(c);
        c.setOnClickListener(v -> onClick.run());
        return c;
    }

    private void closeDrawer() {
        if (drawer == null) return;
        View d = drawer; drawer = null;
        d.animate().alpha(0f).setDuration(120).withEndAction(() -> root.removeView(d)).start();
    }

    /** Lang drukken op een tegel: kies zelf welke app eronder zit. */
    private void pickAppFor(Tile t) {
        List<ResolveInfo> apps = allApps();
        String[] names = new String[apps.size() + 1];
        names[0] = "↺ Standaard herstellen";
        for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("App voor “" + t.label + "”")
                .setItems(names, (dlg, w) -> {
                    if (w == 0) prefs.edit().remove("tile_" + t.id).apply();
                    else prefs.edit().putString("tile_" + t.id, apps.get(w - 1).activityInfo.packageName).apply();
                    setContentView(buildUi());
                }).show();
    }

    private List<ResolveInfo> allApps() {
        Intent i = new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = new ArrayList<>(pm.queryIntentActivities(i, 0));
        list.removeIf(r -> r.activityInfo.packageName.equals(getPackageName()));
        Collections.sort(list, (x, y) -> x.loadLabel(pm).toString().compareToIgnoreCase(y.loadLabel(pm).toString()));
        return list;
    }

    // ---------- Live gegevens (elke seconde) ----------
    private final Runnable tick = new Runnable() { public void run() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE d MMMM", new Locale("nl", "BE")).format(now));
        updateStatus(); updateMedia(); updateRoute(); updateNight();
        if (System.currentTimeMillis() - lastHomey > 60000) { lastHomey = System.currentTimeMillis(); refreshHomey(); }
        ui.postDelayed(this, 1000);
    }};

    private void updateStatus() {
        boolean bt = false;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null && a.isEnabled())
                bt = a.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
                        || a.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception ignored) {}
        setChip(btChip, bt ? "● GSM verbonden" : "○ Geen GSM", bt ? GREEN : MUTED);

        boolean wifi = false, net = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null) { wifi = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                net = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED); }
        } catch (Exception ignored) {}
        setChip(wifiChip, wifi ? (net ? "● Internet" : "● Wifi, geen internet") : "○ Geen wifi", wifi ? (net ? GREEN : AMBER) : MUTED);
    }

    private MediaController controller() {
        try {
            MediaSessionManager m = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> l = m.getActiveSessions(new ComponentName(this, MediaListener.class));
            for (MediaController c : l) { PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return c; }
            return l.isEmpty() ? null : l.get(0);
        } catch (SecurityException e) { return null; }
    }

    private boolean mediaAllowed() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void updateMedia() {
        if (!mediaAllowed()) { trackTitle.setText("Muziekbediening activeren"); trackArtist.setText("Tik op ▶ en zet Ster Launcher aan"); return; }
        MediaController c = controller();
        if (c == null || c.getMetadata() == null) { trackTitle.setText("Geen muziek actief"); trackArtist.setText("Tik op Spotify of Radio"); playBtn.setText("▶"); return; }
        MediaMetadata md = c.getMetadata();
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE), artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        trackTitle.setText(title != null ? title : "Onbekend nummer");
        trackArtist.setText(artist != null ? artist : "");
        PlaybackState s = c.getPlaybackState();
        playBtn.setText(s != null && s.getState() == PlaybackState.STATE_PLAYING ? "❚❚" : "▶");
    }

    private void media(int action) {
        if (!mediaAllowed()) { open(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return; }
        MediaController c = controller(); if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        if (action == 0) t.skipToPrevious();
        else if (action == 2) t.skipToNext();
        else { PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play(); }
        ui.postDelayed(this::updateMedia, 300);
    }

    // ---------- v2: automatische route ----------
    private boolean toWork() {
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY), dow = c.get(Calendar.DAY_OF_WEEK);
        boolean weekday = dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
        return weekday && h >= 4 && h < 12;
    }
    private View routeCard() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.HORIZONTAL); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(card(CARD, 26)); c.setPadding(px(18), px(14), px(18), px(14));
        TextView arrow = text("➤", 24, Color.WHITE); arrow.setGravity(Gravity.CENTER); arrow.setBackground(circle(BLUE));
        c.addView(arrow, new LinearLayout.LayoutParams(px(52), px(52)));
        LinearLayout tx = new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(px(14), 0, 0, 0);
        routeTitle = text("", 19, TEXT); routeTitle.setSingleLine(true);
        routeSub = text("", 13, MUTED); routeSub.setSingleLine(true);
        tx.addView(routeTitle); tx.addView(routeSub);
        c.addView(tx, new LinearLayout.LayoutParams(0, -2, 1f));
        pressable(c);
        c.setOnClickListener(v -> navigate(toWork() ? workAddr() : homeAddr()));
        c.setOnLongClickListener(v -> { navigate(toWork() ? homeAddr() : workAddr()); return true; });
        return c;
    }
    private String workAddr() { return prefs.getString("work", "Stora Enso Langerbrugge, Gent"); }
    private String homeAddr() { return prefs.getString("home", "Knesselare"); }
    private void updateRoute() {
        if (routeTitle == null) return;
        boolean w = toWork();
        routeTitle.setText(w ? "Naar het werk" : "Naar huis");
        routeSub.setText((w ? workAddr() : homeAddr()) + "  ·  lang drukken: " + (w ? "naar huis" : "naar werk"));
    }
    private void navigate(String addr) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?q=" + Uri.encode(addr) + "&navigate=yes"));
        if (pm.getLaunchIntentForPackage("com.waze") != null) i.setPackage("com.waze");
        else i = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(addr)));
        open(i);
    }

    // ---------- v2: rijmodus ----------
    private void updateDriving(float kmh) {
        if (speedText == null) return;
        if (kmh >= 3) { speedText.setVisibility(View.VISIBLE); speedText.setText(Math.round(kmh) + " km/u"); }
        else speedText.setVisibility(View.GONE);
        boolean d = driving ? kmh > 20 : kmh > 30;   // hysterese: geen geflikker rond 30 km/u
        if (d == driving) return;
        driving = d; drivingNow = d;
        for (String id : DISTRACTING) { View v = tileViews.get(id); if (v != null) v.setVisibility(d ? View.GONE : View.VISIBLE); }
        if (d && drawer != null) closeDrawer();
        speedText.setTextColor(d ? BLUE : TEXT);
    }

    // ---------- v2: dag/nacht ----------
    private void updateNight() {
        if (nightOverlay == null) return;
        int[] s = Smart.sun(lat, lon);
        Calendar c = Calendar.getInstance();
        int now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        boolean n = now < s[0] - 15 || now > s[1] + 15;
        int mode = prefs.getInt("nightMode", 0);
        if (mode == 1) n = false; else if (mode == 2) n = true;
        if (n == night) return;
        night = n;
        nightOverlay.animate().alpha(n ? 0.38f : 0f).setDuration(1500).start();
    }

    // ---------- v2: Homey ----------
    private boolean homeyReady() { return !prefs.getString("hUrl", "").isEmpty() && !prefs.getString("hKey", "").isEmpty(); }
    private View homeyCard() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(card(CARD, 26)); c.setPadding(px(18), px(12), px(18), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(px(7), px(7), px(7), px(7));
        c.setLayoutParams(lp);
        TextView head = text("THUIS · HOMEY", 11, BLUE); head.setLetterSpacing(0.15f); c.addView(head);
        homeyLine = text(homeyReady() ? "Gegevens laden…" : "Tik hier om je Homey te koppelen", 17, TEXT);
        homeyLine.setPadding(0, px(4), 0, px(8)); c.addView(homeyLine);
        homeyButtons = new LinearLayout(this);
        String[] defs = {"", "Garagepoort", "Ik kom thuis", "Alarm"};
        for (int i = 1; i <= 3; i++) {
            final String label = prefs.getString("b" + i + "l", defs[i]);
            final String flow = prefs.getString("b" + i + "f", label);
            if (label.isEmpty()) continue;
            TextView b = text(label, 15, TEXT); b.setGravity(Gravity.CENTER); b.setSingleLine(true);
            b.setBackground(card(CARD_PRESSED, 18)); b.setPadding(px(10), px(12), px(10), px(12));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f); bl.setMargins(0, 0, px(10), 0);
            homeyButtons.addView(b, bl); pressable(b);
            b.setOnClickListener(v -> runFlow(flow));
        }
        c.addView(homeyButtons);
        c.setOnClickListener(v -> { if (!homeyReady()) openSettings(); else lastHomey = 0; });
        return c;
    }
    private void runFlow(String flow) {
        if (!homeyReady()) { openSettings(); return; }
        String u = prefs.getString("hUrl", ""), k = prefs.getString("hKey", "");
        toast("Bezig: " + flow + "…");
        new Thread(() -> {
            String msg; try { msg = Smart.triggerFlow(u, k, flow); } catch (Exception e) { msg = "Homey niet bereikbaar: " + e.getMessage(); }
            final String m = msg; ui.post(() -> toast(m));
        }).start();
    }
    private void refreshHomey() {
        if (!homeyReady() || homeyLine == null) return;
        String u = prefs.getString("hUrl", ""), k = prefs.getString("hKey", "");
        String vs = prefs.getString("vSolar", "zon_vermogen"), vb = prefs.getString("vBatt", "batterij_soc"), vp = prefs.getString("vPrice", "stroomprijs");
        new Thread(() -> {
            String line;
            try {
                String[] v = Smart.variables(u, k, vs, vb, vp);
                StringBuilder sb = new StringBuilder();
                if (!v[0].isEmpty()) sb.append("☀ ").append(v[0]).append(" W    ");
                if (!v[1].isEmpty()) sb.append("▮ ").append(v[1]).append(" %    ");
                if (!v[2].isEmpty()) sb.append("€ ").append(v[2]).append("/kWh");
                line = sb.length() > 0 ? sb.toString() : "Verbonden – stel de variabelenamen in";
            } catch (Exception e) { line = "Homey niet bereikbaar"; }
            final String l = line; ui.post(() -> homeyLine.setText(l));
        }).start();
    }

    // ---------- v2: instellingen ----------
    private void openSettings() {
        final String[][] f = {
            {"hUrl", "Homey-adres (thuis http://192.168.x.x, onderweg https://<id>.connect.athom.com)", ""},
            {"hKey", "Homey API-sleutel (Homey-app → Instellingen → API-sleutels)", ""},
            {"b1l", "Knop 1 – naam", "Garagepoort"}, {"b1f", "Knop 1 – naam van de Flow", "Garagepoort"},
            {"b2l", "Knop 2 – naam", "Ik kom thuis"}, {"b2f", "Knop 2 – naam van de Flow", "Ik kom thuis"},
            {"b3l", "Knop 3 – naam", "Alarm"}, {"b3f", "Knop 3 – naam van de Flow", "Alarm"},
            {"vSolar", "Homey-variabele zonnevermogen (W)", "zon_vermogen"},
            {"vBatt", "Homey-variabele batterij (%)", "batterij_soc"},
            {"vPrice", "Homey-variabele stroomprijs (€/kWh)", "stroomprijs"},
            {"work", "Adres werk", "Stora Enso Langerbrugge, Gent"},
            {"home", "Adres thuis", "Knesselare"},
            {"tvHost", "TV – URL + poort (bv. http://line.voorbeeld.nl)", ""},
            {"tvUser", "TV – gebruikersnaam", ""},
            {"tvPass", "TV – wachtwoord", ""},
        };
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(px(20), px(8), px(20), px(8));
        final EditText[] inputs = new EditText[f.length];
        for (int i = 0; i < f.length; i++) {
            TextView l = text(f[i][1], 13, MUTED); l.setPadding(0, px(10), 0, px(2)); form.addView(l);
            EditText e = new EditText(this); e.setSingleLine(true); e.setTextColor(TEXT); e.setTextSize(16);
            e.setText(prefs.getString(f[i][0], f[i][2]));
            if (f[i][0].equals("hKey") || f[i][0].equals("tvPass")) e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            inputs[i] = e; form.addView(e);
        }
        final android.widget.CheckBox fromHomey = new android.widget.CheckBox(this);
        fromHomey.setText("TV-login automatisch uit Homey halen (variabelen iptv_host, iptv_user, iptv_pass)");
        fromHomey.setTextColor(TEXT); fromHomey.setChecked(prefs.getBoolean("tvFromHomey", false)); form.addView(fromHomey);
        TextView nl = text("Nachtmodus", 13, MUTED); nl.setPadding(0, px(10), 0, px(2)); form.addView(nl);
        final android.widget.RadioGroup rg = new android.widget.RadioGroup(this); rg.setOrientation(LinearLayout.HORIZONTAL);
        String[] opts = {"Automatisch (zon)", "Altijd dag", "Altijd nacht"};
        for (int i = 0; i < 3; i++) { android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(opts[i]); rb.setTextColor(TEXT); rb.setId(100 + i); rg.addView(rb); }
        rg.check(100 + prefs.getInt("nightMode", 0)); form.addView(rg);
        ScrollView sv = new ScrollView(this); sv.addView(form);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ster-instellingen").setView(sv)
            .setNegativeButton("Annuleren", null)
            .setNeutralButton("Launcher updaten", (d, w) -> selfUpdate())
            .setPositiveButton("Bewaren", (d, w) -> {
                SharedPreferences.Editor ed = prefs.edit();
                for (int i = 0; i < f.length; i++) ed.putString(f[i][0], inputs[i].getText().toString().trim());
                ed.putBoolean("tvFromHomey", fromHomey.isChecked());
                ed.putInt("nightMode", Math.max(0, rg.getCheckedRadioButtonId() - 100));
                ed.apply(); lastHomey = 0; setContentView(buildUi());
            }).show();
    }

    // ---------- v3: FG-radio ----------
    private TextView radioState;
    private View radioCard() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(card(CARD, 26)); c.setPadding(px(18), px(12), px(18), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(px(7), px(7), px(7), px(7));
        c.setLayoutParams(lp);
        LinearLayout head = new LinearLayout(this);
        TextView h = text("RADIO", 11, BLUE); h.setLetterSpacing(0.15f); head.addView(h);
        radioState = text(Radio.playing() ? "  ·  ▶ " + Radio.current() : "", 11, GREEN); head.addView(radioState);
        c.addView(head);
        LinearLayout row = new LinearLayout(this); row.setPadding(0, px(8), 0, 0);
        for (String[] st : Radio.STATIONS) {
            TextView b = text(st[0], 15, TEXT); b.setGravity(Gravity.CENTER); b.setSingleLine(true);
            b.setBackground(card(CARD_PRESSED, 18)); b.setPadding(px(8), px(12), px(8), px(12));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1f); bl.setMargins(0, 0, px(10), 0);
            row.addView(b, bl); pressable(b);
            b.setOnClickListener(v -> { toast("▶ " + st[0] + " laden…");
                Radio.play(this, st[0], st[1], err -> ui.post(() -> { toast(err); updateRadio(); }));
                ui.postDelayed(this::updateRadio, 300); });
        }
        TextView stop = text("■", 18, RED); stop.setGravity(Gravity.CENTER); stop.setBackground(card(CARD_PRESSED, 18));
        row.addView(stop, new LinearLayout.LayoutParams(px(58), -1)); pressable(stop);
        stop.setOnClickListener(v -> { Radio.stop(); updateRadio(); });
        c.addView(row);
        return c;
    }
    private void updateRadio() { if (radioState != null) radioState.setText(Radio.playing() ? "  ·  ▶ " + Radio.current() : ""); }

    // ---------- v3: apps installeren & updaten ----------
    private boolean installAllowed() {
        if (getPackageName().endsWith("mobiel")) {
            toast("Op je gsm installeer je apps via de Play Store – deze knop is voor de autoradio");
            return false;
        }
        if (pm.canRequestPackageInstalls()) return true;
        toast("Sta eenmalig 'Apps installeren' toe voor Ster Launcher en tik dan opnieuw");
        open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
        return false;
    }
    private void installPack() {
        if (!installAllowed()) return;
        List<String[]> todo = new ArrayList<>();
        for (String[] app : Installer.PACK) {
            boolean have; try { pm.getPackageInfo(app[1], 0); have = true; } catch (Exception e) { have = false; }
            if (!have) todo.add(app);
        }
        if (todo.isEmpty()) { toast("Alles is al geïnstalleerd ✓"); return; }
        StringBuilder sb = new StringBuilder();
        for (String[] t : todo) sb.append("•  ").append(t[0]).append("\n");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Deze apps installeren?")
            .setMessage(sb + "\nJe bevestigt elke app met één tik op 'Installeren'.")
            .setNegativeButton("Nee", null)
            .setPositiveButton("Installeren", (d, w) -> Installer.start(this, todo))
            .show();
    }
    private void selfUpdate() {
        if (!installAllowed()) return;
        List<String[]> me = new ArrayList<>();
        me.add(new String[]{"Ster", getPackageName(), getPackageName().endsWith("mobiel") ? Installer.SELF_URL.replace("SterLauncher.apk", "SterMobiel.apk") : Installer.SELF_URL});
        Installer.start(this, me);
    }

    // ---------- Hulpjes ----------
    private void openBluetooth() {
        for (String p : a("com.syu.bt", "com.fyt.bt", "com.microntek.bluetooth")) {
            Intent i = pm.getLaunchIntentForPackage(p); if (i != null) { open(i); return; } }
        open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }
    private void store(String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        if (i.resolveActivity(pm) == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
        toast("Niet geïnstalleerd – Play Store wordt geopend"); open(i);
    }
    private void storeSearch(String q) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(q)));
        if (i.resolveActivity(pm) == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q + " apk")));
        toast("Niet geïnstalleerd – lang drukken om een andere app te kiezen"); open(i);
    }
    private void open(Intent i) {
        if (i == null) return;
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) { toast("Kan dit niet openen op deze radio"); }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int px(float v) { return Math.round(v * dp); }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); return t;
    }
    private TextView chip(String s) {
        TextView t = text(s, 14, MUTED); t.setPadding(px(14), px(8), px(14), px(8)); t.setBackground(card(CARD, 40));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.rightMargin = px(10); t.setLayoutParams(l);
        pressable(t); return t;
    }
    private void setChip(TextView t, String s, int c) { t.setText(s); t.setTextColor(c); }
    private GradientDrawable card(int color, int radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(px(radius));
        if (color != 0) g.setStroke(px(1), STROKE); return g;
    }
    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color); return g;
    }
    /** Zachte indruk-animatie zodat elke tik voelbaar is. */
    private void pressable(View v) {
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: x.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start(); break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: x.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); break;
            }
            return false;
        });
    }
}
