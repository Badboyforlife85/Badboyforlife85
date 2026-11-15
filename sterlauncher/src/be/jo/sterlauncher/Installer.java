package be.jo.sterlauncher;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloadt en installeert apps één na één. F-Droid-apps worden altijd in hun nieuwste versie
 * opgehaald via de F-Droid-API, zodat de knop over een jaar nog steeds werkt.
 */
final class Installer {
    private Installer() {}

    static final String ACTION = "be.jo.sterlauncher.INSTALL_RESULT";
    static final String SELF_URL = "https://raw.githubusercontent.com/Badboyforlife85/Badboyforlife85/main/sterlauncher/SterLauncher.apk";

    /** Naam, pakket, bron ("fdroid" = nieuwste versie van f-droid.org). */
    static final String[][] PACK = {
        {"F-Droid (vrije appwinkel)", "org.fdroid.fdroid", "fdroid"},
        {"Fennec (Firefox-browser)", "org.mozilla.fennec_fdroid", "fdroid"},
        {"RadioDroid (alle radiozenders)", "net.programmierecke.radiodroid2", "fdroid"},
        {"NewPipe (YouTube-speler)", "org.schabi.newpipe", "fdroid"},
    };

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final List<String[]> queue = new ArrayList<>();
    private static String[] busy;

    static synchronized void start(Activity a, List<String[]> apps) {
        if (busy != null || !queue.isEmpty()) { toast(a, "Er loopt al een installatie"); return; }
        queue.addAll(apps);
        next(a);
    }

    private static synchronized void next(Activity a) {
        if (queue.isEmpty()) { busy = null; toast(a, "Klaar ✓"); return; }
        busy = queue.remove(0);
        final String[] app = busy;
        toast(a, "⇩ " + app[0] + " downloaden…");
        new Thread(() -> {
            try {
                String url = app[2].equals("fdroid") ? fdroidUrl(app[1]) : app[2];
                install(a, url);
            } catch (Exception e) {
                ui.post(() -> { toast(a, app[0] + ": " + e.getMessage()); next(a); });
            }
        }).start();
    }

    /** Kiest de nieuwste versie die past bij de processor van de radio. */
    private static String fdroidUrl(String pkg) throws Exception {
        JSONObject j = new JSONObject(get("https://f-droid.org/api/v1/packages/" + pkg));
        long code = j.getLong("suggestedVersionCode");
        JSONArray all = j.optJSONArray("packages");
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) if (abi.equals("arm64-v8a")) arm64 = true;
        // Apps met aparte builds per processor (bv. Fennec): code eindigt op 0 (arm), 1/2 (arm64 / x86)
        if (all != null && all.length() > 1 && pkg.equals("org.mozilla.fennec_fdroid")) {
            String name = all.getJSONObject(0).optString("versionName");
            for (int i = 0; i < all.length(); i++) {
                JSONObject p = all.getJSONObject(i);
                if (!p.optString("versionName").equals(name)) break;
                long c = p.getLong("versionCode");
                if (arm64 && c % 100 == 20) { code = c; break; }
                if (!arm64 && c % 100 == 10) { code = c; break; }
            }
        }
        return "https://f-droid.org/repo/" + pkg + "_" + code + ".apk";
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        if (c.getResponseCode() >= 400) throw new Exception("server antwoordt " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    private static void install(Activity a, String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() >= 400) throw new Exception("download mislukt (" + c.getResponseCode() + ")");
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("app.apk", 0, -1)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(a, Home.class).setAction(ACTION).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* MUTABLE */ : 0);
            PendingIntent p = PendingIntent.getActivity(a, id, i, flags);
            s.commit(p.getIntentSender());
        }
    }

    /** Aangeroepen vanuit Home.onNewIntent met het resultaat van Android. */
    static void onResult(Activity a, Intent i) {
        if (i == null || !ACTION.equals(i.getAction())) return;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(confirm); }
            return;
        }
        String name = busy != null ? busy[0] : "App";
        if (st == PackageInstaller.STATUS_SUCCESS) toast(a, "✓ " + name + " geïnstalleerd");
        else toast(a, name + " niet geïnstalleerd" + (st == PackageInstaller.STATUS_FAILURE_ABORTED ? " (geannuleerd)" : ""));
        next(a);
    }

    private static void toast(Activity a, String s) { ui.post(() -> Toast.makeText(a, s, Toast.LENGTH_LONG).show()); }
}
