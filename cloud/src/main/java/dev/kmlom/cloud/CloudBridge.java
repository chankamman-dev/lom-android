package dev.kmlom.cloud;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

// Called from the game (LomAndroid.LomCloud) after every save write or delete. Saves come in bursts
// -- a slot and the universe file together -- so the upload waits for things to settle.
public final class CloudBridge {
    private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LomCloud-upload");
        t.setDaemon(true);
        return t;
    });
    private static ScheduledFuture<?> pending;

    public static synchronized void onSaved(Context c) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        boolean ready = TokenStore.load(app) != null && CloudSync.initialized(app) && !Settings.offline(app);
        Log.i("LomCloud", ready ? "save written, upload queued" : "save written, cloud sync off");
        if (!ready) return;
        if (pending != null) pending.cancel(false);
        pending = worker.schedule(() -> {
            try {
                CloudSync.sync(app, s -> Log.i("LomCloud", s), false);
            } catch (Throwable t) {
                Log.w("LomCloud", "upload failed: " + t);
            }
        }, 4, TimeUnit.SECONDS);
    }

    // The game is up: report it as running on Steam while it is on screen.
    public static void onGameStarted(Context c) {
        if (c != null && !Settings.offline(c)) SteamPlay.start(c);
    }

    // Comma-separated Steam achievement names (ACH_...) the game has earned.
    public static void onAchievements(Context c, String names) {
        if (c != null && names != null) SteamPlay.unlock(c, names.split(","));
    }

    // Launcher settings for the game side (LomPerf: frame-rate cap, render scale, fps log).
    public static int intSetting(Context c, String key, int def) {
        return c == null ? def : Settings.get(c, key, def);
    }

    // The on-screen controls (TouchPad), built once the game is up; state() is polled every frame.
    public static void attachTouch(android.app.Activity a) {
        AchievementToast.resumed(a);
        TouchPad.attach(a);
    }

    public static float[] touchState() {
        return TouchPad.state();
    }

    private CloudBridge() { }
}
