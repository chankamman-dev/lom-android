package dev.kmlom.cloud;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

// What this APK was built with (assets/port/features.txt, written by build-apk.sh: "companion" when
// 咖啡伴侣 is woven in, "bridge" when the lower-screen bridge is too) and what the device has.
final class Features {
    // The lower-screen companion app of lom-thor-probe (talks to the bridge on 127.0.0.1:23879).
    static final String LOWER_APP = "io.github.chankamman_dev.lomthor.dev";

    // The same lower screen built into this APK (build-apk.sh compiles lom-thor-probe's activity and
    // copies its pages; feature "lower").
    static final String LOWER_ACTIVITY = "io.github.chankamman_dev.lomthor.MainActivity";

    static boolean lowerAvailable(Context c) {
        return (has(c, "lower") && android.os.Build.VERSION.SDK_INT >= 30) || installed(c, LOWER_APP);
    }

    private static Set<String> set;

    static synchronized boolean has(Context c, String f) {
        if (set == null) {
            set = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("port/features.txt"), StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) if (!l.trim().isEmpty()) set.add(l.trim());
            } catch (Exception ignored) {
            }
        }
        return set.contains(f);
    }

    // A second built-in screen (Thor and other dual-screen handhelds); null on ordinary devices.
    static Display secondDisplay(Context c) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            if ((d.getFlags() & Display.FLAG_PRIVATE) != 0) continue;
            String n = d.getName() == null ? "" : d.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("virtual") || n.contains("overlay") || n.contains("cast")) continue; // screen recording, casting
            return d;
        }
        return null;
    }

    static boolean dualScreen(Context c) {
        return secondDisplay(c) != null;
    }

    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Features() { }
}
