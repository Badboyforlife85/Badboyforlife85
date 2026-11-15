package be.jo.sterlauncher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.PowerManager;

/** Ingebouwde internetradio: één tik, geen extra app nodig. */
final class Radio {
    private Radio() {}

    interface Err { void on(String msg); }

    /** Naam, stream-URL (bron: Radio Browser). */
    static final String[][] STATIONS = {
        {"FG Antwerpen", "https://stream.rcs.revma.com/5wesqhfap98uv"},
        {"FG Underground", "https://radiofg.impek.com/ufg.mp3"},
        {"FG Vlaanderen", "http://radiofg.impek.com/fga"},
    };

    private static MediaPlayer mp;
    private static String now = "";

    static synchronized void play(Context ctx, String name, String url, Err err) {
        stop();
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(ctx.getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(url);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((m, w, e) -> { stop(); err.on(name + " is nu niet bereikbaar"); return true; });
            now = name;
            mp.prepareAsync();
        } catch (Exception e) { stop(); err.on("Kan " + name + " niet afspelen"); }
    }

    static synchronized void stop() {
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) {} mp.release(); mp = null; }
        now = "";
    }

    static boolean playing() { return mp != null; }
    static String current() { return now; }
}
