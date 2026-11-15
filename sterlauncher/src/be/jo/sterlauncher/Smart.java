package be.jo.sterlauncher;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Iterator;
import java.util.TimeZone;

/** Hulpfuncties zonder UI: Homey-API en zonsopgang/-ondergang. Alles hier draait op een achtergrondthread. */
final class Smart {
    private Smart() {}

    // ---------------- Homey ----------------
    // Werkt met Homey Pro (2023) API-sleutel: Instellingen → API-sleutels.
    // Basis-URL thuis: http://192.168.x.x  — onderweg: https://<homeyId>.connect.athom.com

    static String http(String method, String url, String key, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(8000); c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        if (in != null) while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        c.disconnect();
        if (code >= 400) throw new Exception("Homey antwoordt " + code);
        return b.toString("UTF-8");
    }

    private static String base(String url) { return url.replaceAll("/+$", ""); }

    /** Start een (gewone of advanced) Flow op naam. Geeft een korte melding terug. */
    static String triggerFlow(String baseUrl, String key, String flowName) throws Exception {
        String want = flowName.trim().toLowerCase();
        String[][] kinds = {{"flow", "flow"}, {"flow", "advancedflow"}};
        for (String[] k : kinds) {
            JSONObject all = new JSONObject(http("GET", base(baseUrl) + "/api/manager/" + k[0] + "/" + k[1], key, null));
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String id = it.next(); JSONObject f = all.getJSONObject(id);
                if (f.optString("name").trim().toLowerCase().equals(want)) {
                    http("POST", base(baseUrl) + "/api/manager/" + k[0] + "/" + k[1] + "/" + id + "/trigger", key, "{}");
                    return "✓ " + f.optString("name") + " gestart";
                }
            }
        }
        return "Flow “" + flowName + "” niet gevonden in Homey";
    }

    /** Alle Flow-namen (gewone + advanced), alfabetisch. */
    static java.util.List<String> flowNames(String baseUrl, String key) throws Exception {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String kind : new String[]{"flow", "advancedflow"}) {
            JSONObject all = new JSONObject(http("GET", base(baseUrl) + "/api/manager/flow/" + kind, key, null));
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String n = all.getJSONObject(it.next()).optString("name").trim();
                if (!n.isEmpty() && !out.contains(n)) out.add(n);
            }
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Leest Homey-logicavariabelen op naam (hoofdletterongevoelig). Leeg = niet gevonden. */
    static String[] variables(String baseUrl, String key, String... names) throws Exception {
        JSONObject all = new JSONObject(http("GET", base(baseUrl) + "/api/manager/logic/variable", key, null));
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) out[i] = "";
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject v = all.getJSONObject(it.next());
            for (int i = 0; i < names.length; i++)
                if (!names[i].isEmpty() && v.optString("name").equalsIgnoreCase(names[i].trim())) {
                    Object val = v.opt("value");
                    if (val instanceof Double) out[i] = String.valueOf(Math.round((Double) val * 100) / 100.0);
                    else out[i] = String.valueOf(val);
                }
        }
        return out;
    }

    // ---------------- Zon ----------------
    /** Zonsopgang en -ondergang in minuten na middernacht (lokale tijd), NOAA-benadering. */
    static int[] sun(double lat, double lon) {
        Calendar cal = Calendar.getInstance();
        int doy = cal.get(Calendar.DAY_OF_YEAR);
        double g = 2 * Math.PI / 365 * (doy - 1);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double r = Math.toRadians(lat);
        double ha = Math.toDegrees(Math.acos(Math.cos(Math.toRadians(90.833)) / (Math.cos(r) * Math.cos(decl))
                - Math.tan(r) * Math.tan(decl)));
        double offset = TimeZone.getDefault().getOffset(cal.getTimeInMillis()) / 60000.0;
        int rise = (int) Math.round(720 - 4 * (lon + ha) - eqt + offset);
        int set = (int) Math.round(720 - 4 * (lon - ha) - eqt + offset);
        return new int[]{rise, set};
    }
}
