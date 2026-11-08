package dev.kmlom.cloud;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.List;

// The "achievement unlocked" card the Steam client shows, drawn over whatever activity of the app is
// on screen. SteamPlay calls show() for the achievements Steam has just accepted as new (not the ones
// the game re-offers on every start). Names, descriptions and icons come from the game's Steam
// schema (cached in prefs by remember(), so the launcher can preview the card offline); icons are
// fetched once from the Steam CDN and kept in files/ach-icons.
// The card rises from the bottom centre (clear of the touch keys on both sides), stays five seconds
// and goes back down the way it came. Several unlocks queue up. It never takes touches.
final class AchievementToast {
    private static final String TAG = "LomCloud";
    private static final String PREFS = "lom_ach_schema";
    private static final String CDN = "https://cdn.akamai.steamstatic.com/steamcommunity/public/images/apps/";
    private static final long SHOW_MS = 5000;

    static final class Info {
        final String id, name, desc, icon;

        Info(String id, String name, String desc, String icon) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.icon = icon;
        }
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<Object[]> queue = new ArrayDeque<>();
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static boolean busy;

    // SteamPlay's lifecycle callbacks keep this pointed at the activity in front.
    static void resumed(Activity a) {
        top = new WeakReference<>(a);
    }

    static void paused(Activity a) {
        if (top.get() == a) top = new WeakReference<>(null);
    }

    static boolean enabled(Context c) {
        return Settings.get(c, "achtoast", 1) != 0;
    }

    // Keeps what the card needs from the schema SteamPlay just read: stats/<id>/bits/<n>/{name, display/{name,desc}/<lang>, icon}.
    static void remember(Context c, in.dragonbra.javasteam.types.KeyValue stats) {
        try {
            SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            for (in.dragonbra.javasteam.types.KeyValue stat : stats.getChildren())
                for (in.dragonbra.javasteam.types.KeyValue bit : stat.get("bits").getChildren()) {
                    String id = bit.get("name").getValue();
                    if (id == null) continue;
                    in.dragonbra.javasteam.types.KeyValue d = bit.get("display");
                    JSONObject o = new JSONObject();
                    o.put("name", local(d.get("name"), id));
                    o.put("desc", local(d.get("desc"), ""));
                    String icon = bit.get("icon").getValue();
                    if (icon == null) icon = d.get("icon").getValue();
                    o.put("icon", icon == null ? "" : icon);
                    e.putString(id, o.toString());
                }
            e.apply();
        } catch (Throwable t) {
            Log.w(TAG, "achievement schema not kept: " + t);
        }
    }

    private static String local(in.dragonbra.javasteam.types.KeyValue kv, String def) {
        for (String lang : new String[] { "schinese", "tchinese", "english" }) {
            String v = kv.get(lang).getValue();
            if (v != null && !v.isEmpty()) return v;
        }
        String v = kv.getValue();
        return v != null && !v.isEmpty() ? v : def;
    }

    static Info info(Context c, String id) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null);
        if (s == null) return new Info(id, id, "", "");
        try {
            JSONObject o = new JSONObject(s);
            return new Info(id, o.optString("name", id), o.optString("desc", ""), o.optString("icon", ""));
        } catch (Exception e) {
            return new Info(id, id, "", "");
        }
    }

    // Any one achievement from the cache, for the launcher's preview; null before the first Steam session.
    static Info sample(Context c) {
        for (String k : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().keySet()) return info(c, k);
        return null;
    }

    static void show(Context c, List<String> ids) {
        Context app = c.getApplicationContext();
        if (!enabled(app)) return;
        for (String id : ids) {
            Info i = info(app, id);
            // The icon is fetched here, on the caller's (network) thread, before the card is queued.
            Bitmap b = icon(app, i.icon);
            UI.post(() -> enqueue(i, b));
        }
    }

    static void preview(Activity a) {
        Info i = sample(a);
        Info shown = i != null ? i : new Info("", "江湖初出", "成就解锁时，这张卡片会出现在游戏画面下方。", "");
        new Thread(() -> {
            Bitmap b = icon(a.getApplicationContext(), shown.icon);
            UI.post(() -> {
                top = new WeakReference<>(a);
                enqueue(shown, b);
            });
        }, "LomCloud-achicon").start();
    }

    private static void enqueue(Info i, Bitmap b) {
        queue.add(new Object[] { i, b });
        if (!busy) next();
    }

    private static void next() {
        Object[] n = queue.poll();
        if (n == null) {
            busy = false;
            return;
        }
        Activity a = top.get();
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            // Nothing of ours on screen (the game is in the background): keep it for when it returns.
            queue.addFirst(n);
            busy = false;
            UI.postDelayed(() -> { if (!busy && !queue.isEmpty()) next(); }, 1500);
            return;
        }
        busy = true;
        try {
            card(a, (Info) n[0], (Bitmap) n[1]);
        } catch (Throwable t) {
            Log.w(TAG, "achievement card failed: " + t);
            busy = false;
            next();
        }
    }

    private static void card(Activity a, Info info, Bitmap icon) {
        float d = a.getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(a);
        root.setClickable(false);
        root.setFocusable(false);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (10 * d);
        card.setPadding(pad, pad, (int) (16 * d), pad);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(42, 71, 94), Color.rgb(27, 40, 56) });
        bg.setCornerRadius(6 * d);
        bg.setStroke(Math.max(1, (int) d), Color.argb(70, 102, 192, 244));
        card.setBackground(bg);
        card.setElevation(10 * d);
        card.setClickable(false);
        card.setFocusable(false);

        ImageView iv = new ImageView(a);
        int is = (int) (56 * d);
        if (icon != null) iv.setImageBitmap(icon);
        else {
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(Color.rgb(61, 94, 120));
            ph.setCornerRadius(3 * d);
            iv.setBackground(ph);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 3 * d); }
        });
        iv.setClipToOutline(true);
        card.addView(iv, new LinearLayout.LayoutParams(is, is));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding((int) (12 * d), 0, 0, 0);
        TextView head = new TextView(a);
        head.setText("已解锁成就");
        head.setTextSize(12);
        head.setTextColor(Color.rgb(143, 184, 214));
        TextView name = new TextView(a);
        name.setText(info.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(head);
        texts.addView(name);
        if (!info.desc.isEmpty()) {
            TextView desc = new TextView(a);
            desc.setText(info.desc);
            desc.setTextSize(12);
            desc.setTextColor(Color.rgb(172, 178, 184));
            desc.setSingleLine(true);
            desc.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(desc);
        }
        card.addView(texts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int maxW = Math.min((int) (380 * d), a.getWindow().getDecorView().getWidth() - (int) (32 * d));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (24 * d);
        card.setMinimumWidth((int) (260 * d));
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (maxW > 0) lp.width = Math.max((int) (260 * d), Math.min(maxW, (int) (380 * d)));
        root.addView(card, lp);
        a.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        float travel = 120 * d;
        card.setAlpha(0f);
        card.setTranslationY(travel);
        card.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(new DecelerateInterpolator(2f)).start();
        UI.postDelayed(() -> card.animate().alpha(0f).translationY(travel).setDuration(260).setInterpolator(new AccelerateInterpolator(1.6f))
                .withEndAction(() -> {
                    ViewGroup p = (ViewGroup) root.getParent();
                    if (p != null) p.removeView(root);
                    next();
                }).start(), SHOW_MS);
        Log.i(TAG, "achievement card: " + info.id + " " + info.name);
    }

    private static Bitmap icon(Context app, String file) {
        if (file == null || !file.matches("[0-9a-f]{40}\\.jpg")) return null;
        File dir = new File(app.getFilesDir(), "ach-icons");
        File f = new File(dir, file);
        try {
            if (!f.isFile()) {
                dir.mkdirs();
                HttpURLConnection c = (HttpURLConnection) new URL(CDN + CloudSync.APP + "/" + file).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                try {
                    if (c.getResponseCode() != 200) return null;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    try (InputStream in = c.getInputStream()) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            if (out.size() > 1 << 20) return null;
                        }
                    }
                    File tmp = new File(dir, file + ".part");
                    try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(out.toByteArray()); }
                    if (!tmp.renameTo(f)) return null;
                } finally {
                    c.disconnect();
                }
            }
            return BitmapFactory.decodeFile(f.getPath());
        } catch (Throwable t) {
            Log.w(TAG, "achievement icon " + file + ": " + t);
            return null;
        }
    }

    private AchievementToast() { }
}
