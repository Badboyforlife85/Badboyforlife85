package be.jo.sterlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * "Delen → Ster" op je gsm: stuurt een adres of Maps/Waze-link via Homey naar de autoradio.
 * Geen scherm: deelt, toont een melding en sluit.
 */
public class ShareActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) text = i.getDataString();
        if (text == null || text.trim().isEmpty()) { finish(); return; }
        final String dest = text.trim();
        SharedPreferences p = getSharedPreferences("ster", MODE_PRIVATE);
        String url = p.getString("hUrl", ""), key = p.getString("hKey", "");
        if (url.isEmpty() || key.isEmpty()) {
            Toast.makeText(this, "Koppel eerst Homey in Ster-instellingen", Toast.LENGTH_LONG).show(); finish(); return;
        }
        Toast.makeText(this, "🚗 Naar de auto sturen…", Toast.LENGTH_SHORT).show();
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try { Smart.setVariable(url, key, "ster_bestemming", dest); msg = "✓ Bestemming staat klaar in de auto"; }
            catch (Exception e) { msg = "Homey niet bereikbaar: " + e.getMessage(); }
            final String m = msg;
            ui.post(() -> { Toast.makeText(getApplicationContext(), m, Toast.LENGTH_LONG).show(); finish(); });
        }).start();
    }
}
