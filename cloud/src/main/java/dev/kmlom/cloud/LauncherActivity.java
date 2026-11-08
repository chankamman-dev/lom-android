package dev.kmlom.cloud;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

// The game's home screen, in the ink-and-paper look of the title screen: the Steam library art on
// top, the calligraphy logo, the state of the Steam account, cloud saves and game data, and the
// buttons. First start downloads the game data from Steam (GameData). Everything works with a gamepad.
public final class LauncherActivity extends Activity {
    private static final int PAPER = Color.rgb(241, 233, 218), PAPER_DEEP = Color.rgb(226, 214, 192), CARD = Color.rgb(246, 240, 228),
            INK = Color.rgb(43, 36, 32), INK_SOFT = Color.rgb(92, 80, 70), MUTED = Color.rgb(140, 127, 114),
            CINNABAR = Color.rgb(168, 50, 40), CINNABAR_DARK = Color.rgb(132, 36, 28), JADE = Color.rgb(70, 125, 92), OCHRE = Color.rgb(176, 124, 44);
    private static final String TAG = "LomLauncher";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root, overlay;
    private TextView chipSteam, chipCloud, chipData, progressLabel, progressDetail, notice;
    private Bar progressBar;
    private LinearLayout progressBox, buttons;
    private TextView primary;
    private Typeface serif, serifBold;

    private volatile boolean syncing, syncedOk, installing;
    private volatile String syncError;
    private volatile GameData.Check check;
    private volatile String checkError;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private Runnable afterSync;
    private File localTest;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SteamCrypto.install();
        String lt = getIntent().getStringExtra("localSource");
        if (lt != null) localTest = new File(lt);
        serif = Typeface.create("serif", Typeface.NORMAL);
        serifBold = Typeface.create("serif", Typeface.BOLD);
        deviceDefaults();
        buildHome();
        hideBars();
        refresh();
        goOnline();
    }

    private void goOnline() {
        if (TokenStore.load(this) == null || Settings.offline(this)) return;
        startSync(null);
        startCheck();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    // ---------------------------------------------------------------- layout

    private void buildHome() {
        root = new FrameLayout(this);
        GradientDrawable paper = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { PAPER, PAPER, PAPER_DEEP });
        root.setBackground(paper);

        // Library art across the top, fading into the paper.
        ImageView hero = new ImageView(this);
        hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.setImageBitmap(asset("port/hero.jpg"));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP);
        root.addView(hero, hp);
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(0, 241, 233, 218), Color.argb(150, 241, 233, 218), PAPER }));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP);
        root.addView(fade, fp);
        // A paper wash from the left so the logo sits on light ground whatever the crop.
        View wash = new View(this);
        wash.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { Color.argb(150, 241, 233, 218), Color.argb(60, 241, 233, 218), Color.argb(0, 241, 233, 218) }));
        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.START);
        root.addView(wash, wp);

        ImageView logo = new ImageView(this);
        logo.setImageBitmap(asset("port/logo.png"));
        logo.setAdjustViewBounds(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        root.addView(logo, lp);

        root.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> {
            int w = r - l, h = bo - t;
            if (w == or - ol && h == ob - ot && hp.height > 0) return;
            // The art is 1920x620: about 62% of the screen height, cropping at most a quarter of it.
            int heroH = Math.max((int) (h * 0.40f), Math.min((int) (w * 620f / 1920f * 1.25f), (int) (h * 0.62f)));
            hp.height = heroH;
            fp.height = (int) (heroH * 0.45f);
            fp.topMargin = heroH - fp.height + 1;
            wp.width = (int) (w * 0.55f);
            wp.height = heroH;
            lp.width = Math.min((int) (w * 0.36f), (int) (heroH * 1.15f));
            lp.leftMargin = Math.max(dp(28), (int) (w * 0.045f));
            lp.topMargin = (int) (heroH * 0.10f);
            ui.post(() -> {
                hero.setLayoutParams(hp);
                fade.setLayoutParams(fp);
                wash.setLayoutParams(wp);
                logo.setLayoutParams(lp);
            });
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(44), 0, dp(44), dp(28));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        progressBox = new LinearLayout(this);
        progressBox.setOrientation(LinearLayout.VERTICAL);
        progressBox.setVisibility(View.GONE);
        progressLabel = text(16, INK, true);
        progressBar = new Bar(this);
        progressDetail = text(13, INK_SOFT, false);
        progressBox.addView(progressLabel);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        bp.setMargins(0, dp(10), 0, dp(8));
        progressBox.addView(progressBar, bp);
        progressBox.addView(progressDetail);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbl.setMargins(0, 0, 0, dp(18));
        panel.addView(progressBox, pbl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setClipChildren(false);
        panel.addView(row);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sub = text(15, INK_SOFT, false);
        sub.setTypeface(serif);
        sub.setLetterSpacing(0.06f);
        GameData.Port port = GameData.port(this);
        sub.setText("活侠传  ·  Legend of Mortal  ·  安卓版");
        info.addView(sub);
        TextView build = text(12, MUTED, false);
        build.setText("基于 Steam 正式版 build " + port.build + "，存档与电脑版互通");
        build.setPadding(0, dp(3), 0, dp(14));
        info.addView(build);

        LinearLayout chips = new LinearLayout(this);
        chipSteam = chip();
        chipCloud = chip();
        chipData = chip();
        chips.addView(chipSteam);
        chips.addView(chipCloud);
        chips.addView(chipData);
        info.addView(chips);

        notice = text(13, OCHRE, false);
        notice.setVisibility(View.GONE);
        notice.setPadding(0, dp(10), dp(16), 0);
        info.addView(notice);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setClipChildren(false);
        row.addView(right);
        primary = button("开始游戏", true);
        right.addView(primary, new LinearLayout.LayoutParams(dp(260), dp(60)));
        buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(0, dp(12), 0, 0);
        right.addView(buttons, bl);

        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(120, 30, 24, 20));
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // Settle in: the panel rises a little, the art fades up.
        panel.setAlpha(0f);
        panel.setTranslationY(dp(16));
        panel.animate().alpha(1f).translationY(0).setDuration(380).setStartDelay(80).setInterpolator(new DecelerateInterpolator()).start();
        logo.setAlpha(0f);
        logo.animate().alpha(1f).setDuration(520).start();
    }

    // Recomputes the status line and the buttons.
    private void refresh() {
        TokenStore t = TokenStore.load(this);
        GameData.Status st = GameData.status(this);
        boolean ready = st == GameData.Status.READY;
        boolean offline = Settings.offline(this);

        if (offline) setChip(chipSteam, OCHRE, "离线模式" + (t == null ? "" : " · " + t.account));
        else if (t == null) setChip(chipSteam, MUTED, "Steam 未登录");
        else setChip(chipSteam, JADE, "Steam · " + t.account);

        if (t == null) setChip(chipCloud, MUTED, "云存档未开启");
        else if (offline) setChip(chipCloud, MUTED, "云存档暂停");
        else if (syncing) setChip(chipCloud, INK_SOFT, "云存档同步中…");
        else if (syncedOk) setChip(chipCloud, JADE, "云存档已同步 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date()));
        else setChip(chipCloud, OCHRE, "云存档未同步");

        GameData.Check c = check;
        if (installing) setChip(chipData, INK_SOFT, "游戏数据安装中");
        else if (st == GameData.Status.MISSING) setChip(chipData, OCHRE, GameData.partialBytes(this) > 0 ? "游戏数据未下载完" : "需要下载游戏数据");
        else if (st == GameData.Status.APK_CHANGED) setChip(chipData, OCHRE, "游戏数据需要更新");
        else if (c != null && c.needsNewApk) setChip(chipData, OCHRE, "Steam 有新版本");
        else if (deviceUpdate(c)) setChip(chipData, OCHRE, "游戏数据可更新");
        else setChip(chipData, JADE, "游戏数据已就绪");

        if (c != null && c.needsNewApk) {
            notice.setText("Steam 上的活侠传已经更新，这次改动了游戏程序，需要新版安卓移植包才能跟上。现在可以继续玩当前版本，存档不受影响。");
            notice.setVisibility(View.VISIBLE);
        } else if (checkError != null && !ready) {
            notice.setText(checkError);
            notice.setVisibility(View.VISIBLE);
        } else if (syncError != null && !syncing) {
            notice.setText("云存档：" + syncError);
            notice.setVisibility(View.VISIBLE);
        } else notice.setVisibility(View.GONE);

        buttons.removeAllViews();
        if (installing) {
            setPrimary("暂停", () -> cancel.set(true));
        } else if (st == GameData.Status.MISSING) {
            boolean resume = GameData.partialBytes(this) > 0;
            if (localTest != null) setPrimary("从测试目录安装", this::startInstall);
            else if (offline) setPrimary("切回在线以下载", () -> setOffline(false));
            else if (t == null) setPrimary("登录 Steam 下载游戏", this::showLogin);
            else {
                long bytes = c != null && c.target != 0 && c.bytesToGet > 0 ? c.bytesToGet : GameData.port(this).steamBytes;
                setPrimary((resume ? "继续下载  " : "下载游戏  ") + gb(bytes), this::startInstall);
            }
        } else if (st == GameData.Status.APK_CHANGED) {
            setPrimary("更新游戏数据", this::startInstall);
        } else {
            setPrimary("开始游戏", this::play);
            if (deviceUpdate(c)) addSecondary("更新 " + mb(c.bytesToGet), this::startInstall);
        }
        if (t != null) addSecondary("账户", this::showAccount);
        else if (installing || st != GameData.Status.MISSING || offline || localTest != null) addSecondary("登录 Steam", this::showLogin); // else the main button is the login
        addSecondary("设置", this::showSettings);
        if (overlay.getVisibility() != View.VISIBLE && !primary.hasFocus()) primary.requestFocus();
    }

    private static boolean deviceUpdate(GameData.Check c) {
        return c != null && !c.upToDate && !c.needsNewApk && c.target != 0 && c.target != c.installed && c.filesToGet > 0;
    }

    private void setPrimary(String label, Runnable r) {
        primary.setText(label);
        primary.setOnClickListener(v -> r.run());
    }

    private void addSecondary(String label, Runnable r) {
        TextView b = button(label, false);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        if (buttons.getChildCount() > 0) lp.setMargins(dp(10), 0, 0, 0);
        buttons.addView(b, lp);
    }

    // ---------------------------------------------------------------- cloud sync

    private void startSync(Runnable then) {
        if (then != null) afterSync = then;
        if (syncing) return;
        syncing = true;
        refresh();
        new Thread(() -> {
            String err = null;
            boolean rejected = false;
            try {
                CloudSync.Result r = CloudSync.sync(this, s -> { }, true);
                Log.i("LomCloud", "sync: " + r);
            } catch (Throwable e) {
                err = message(e);
                rejected = e instanceof SteamSession.LogOnRejected;
                Log.w("LomCloud", "sync failed: " + e);
            }
            final String error = err;
            final boolean expired = rejected;
            ui.post(() -> {
                syncing = false;
                syncedOk = error == null;
                syncError = error;
                refresh();
                Runnable next = afterSync;
                afterSync = null;
                if (expired) {
                    showMessage("Steam 登录已失效", "需要重新扫码登录。本机存档没有改动。",
                            new String[] { "重新登录", "离线进入游戏" }, new Runnable[] { () -> { signOut(); showLogin(); }, this::enterGame });
                } else if (error != null && next != null) {
                    showMessage("云存档同步失败", error + "\n本机存档没有改动。可以先离线游玩，下次启动会再同步。",
                            new String[] { "离线进入游戏", "取消" }, new Runnable[] { this::enterGame, this::closeOverlay });
                } else if (next != null) next.run();
            });
        }, "LomCloud-sync").start();
    }

    private void play() {
        if (!GameData.ready(this)) return;
        if (TokenStore.load(this) == null || syncedOk || Settings.offline(this)) {
            enterGame();
            return;
        }
        showProgress("正在同步 Steam 云存档…", -1, "同步完成后自动进入游戏");
        startSync(this::enterGame);
    }

    private void enterGame() {
        Intent i = new Intent();
        i.setClassName(this, "com.unity3d.player.UnityPlayerActivity");
        android.view.Display second = Features.secondDisplay(this);
        Bundle opts = null;
        if (second != null) {
            // Dual screen: the game goes where the player chose; the lower-screen companion (if wanted
            // and installed) goes to the other screen first, so the game ends up with the focus.
            boolean onSecond = Settings.get(this, "gamescreen", 0) == 1;
            int gameId = onSecond ? second.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
            int otherId = onSecond ? android.view.Display.DEFAULT_DISPLAY : second.getDisplayId();
            if (Settings.get(this, "companion", 1) == 2 && Settings.get(this, "lowerapp", 1) == 1 && Features.lowerAvailable(this)) {
                try {
                    // The lower screen is built into this APK (its own process, see AndroidManifest);
                    // the separate 活侠下屏 app is only a fallback for builds without it.
                    Intent c = Features.has(this, "lower") ? new Intent().setClassName(this, Features.LOWER_ACTIVITY)
                            : getPackageManager().getLaunchIntentForPackage(Features.LOWER_APP);
                    if (c != null) {
                        c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(c, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(otherId).toBundle());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "lower-screen app not started: " + t);
                }
            }
            opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(gameId).toBundle();
        }
        try {
            startActivity(i, opts);
        } catch (Throwable t) {
            Log.w(TAG, "launch on display failed, default display: " + t);
            startActivity(i);
        }
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    private void signOut() {
        TokenStore.clear(this);
        CloudSync.forget(this);
        SteamPlay.forget(this);
        syncedOk = false;
        syncError = null;
        check = null;
    }

    // ---------------------------------------------------------------- game data

    private void startCheck() {
        new Thread(() -> {
            try {
                GameData.Check c = GameData.check(this, new GameData.Listener() {
                    @Override public void status(String s) { }
                    @Override public void progress(long done, long total, int files, int filesTotal) { }
                });
                check = c;
                checkError = null;
                Log.i(TAG, "check: installed " + Long.toUnsignedString(c.installed) + " apk " + Long.toUnsignedString(c.apkBase) + " latest " + Long.toUnsignedString(c.latest)
                        + " needsNewApk " + c.needsNewApk + (c.whyNewApk != null ? " (" + c.whyNewApk + ")" : "") + " files " + c.filesToGet + " bytes " + c.bytesToGet);
            } catch (Throwable e) {
                checkError = "无法向 Steam 查询游戏版本：" + message(e);
                Log.w(TAG, "check failed: " + e);
            }
            ui.post(this::refresh);
        }, "LomData-check").start();
    }

    private void startInstall() {
        if (installing) return;
        GameData.Check c = check;
        long target = c != null && c.target != 0 ? c.target : 0;
        installing = true;
        cancel.set(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refresh();
        showProgress("正在准备…", -1, "请停留在这个页面。可以随时暂停，下次会接着来。");
        final long[] lastT = { System.currentTimeMillis() }, lastB = { 0 };
        final double[] speed = { 0 };
        final String[] phase = { "正在准备…" };
        new Thread(() -> {
            String err = null;
            boolean paused = false;
            try {
                GameData.install(this, target, localTest, new GameData.Listener() {
                    @Override
                    public void status(String s) {
                        phase[0] = s;
                        lastT[0] = System.currentTimeMillis();
                        speed[0] = 0;
                        ui.post(() -> progressLabel.setText(s));
                    }

                    @Override
                    public void progress(long done, long total, int files, int filesTotal) {
                        long now = System.currentTimeMillis();
                        if (now - lastT[0] >= 1000) {
                            double inst = (done - lastB[0]) * 1000.0 / (now - lastT[0]);
                            speed[0] = speed[0] == 0 ? inst : speed[0] * 0.7 + inst * 0.3;
                            lastT[0] = now;
                            lastB[0] = done;
                        }
                        float f = total > 0 ? (float) done / total : 0;
                        String eta = speed[0] > 1 ? "  ·  约 " + duration((long) ((total - done) / speed[0])) : "";
                        String d = String.format(Locale.CHINA, "%s / %s  ·  %s/s%s  ·  %d / %d 个文件", gb(done), gb(total), mb((long) Math.max(0, speed[0])), eta, files, filesTotal);
                        ui.post(() -> {
                            progressLabel.setText(phase[0] + "  " + Math.round(f * 100) + "%");
                            progressBar.set(f);
                            progressDetail.setText(d);
                        });
                    }
                }, cancel);
            } catch (InterruptedException e) {
                paused = true;
            } catch (Throwable e) {
                err = message(e);
                Log.w(TAG, "install failed", e);
            }
            final String error = err;
            final boolean wasPaused = paused;
            ui.post(() -> {
                installing = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                hideProgress();
                if (error == null && !wasPaused) {
                    check = null;
                    if (TokenStore.load(this) != null && !Settings.offline(this)) startCheck();
                }
                refresh();
                if (error != null) showMessage("没有完成", error, new String[] { "重试", "关闭" }, new Runnable[] { () -> { closeOverlay(); startInstall(); }, this::closeOverlay });
                else if (wasPaused) toast("已暂停，下次会从这里继续");
                else toast("游戏数据已就绪");
            });
        }, "LomData-install").start();
    }

    private void showProgress(String label, float f, String detail) {
        progressBox.setVisibility(View.VISIBLE);
        progressLabel.setText(label);
        progressBar.set(f);
        progressDetail.setText(detail);
    }

    private void hideProgress() {
        progressBox.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- overlays

    private LinearLayout card(String title, String body) {
        overlay.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(34), dp(26), dp(34), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.argb(70, 43, 36, 32));
        c.setBackground(bg);
        c.setElevation(dp(12));
        TextView t = text(22, INK, true);
        t.setTypeface(serifBold);
        t.setText(title);
        c.addView(t);
        View rule = new View(this);
        rule.setBackgroundColor(CINNABAR);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(dp(36), dp(2));
        rl.setMargins(0, dp(10), 0, 0);
        c.addView(rule, rl);
        if (body != null) {
            TextView b = text(15, INK_SOFT, false);
            b.setText(body);
            b.setLineSpacing(dp(4), 1f);
            b.setPadding(0, dp(12), 0, 0);
            c.addView(b);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(640), (int) (getResources().getDisplayMetrics().widthPixels * 0.86f)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(c, lp);
        if (overlay.getVisibility() != View.VISIBLE) {
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(160).start();
            c.setScaleX(0.96f);
            c.setScaleY(0.96f);
            c.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }
        return c;
    }

    private LinearLayout cardButtons(LinearLayout card, String[] labels, Runnable[] actions) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        for (int k = 0; k < labels.length; k++) {
            TextView b = button(labels[k], k == 0);
            final Runnable a = actions[k];
            b.setOnClickListener(v -> a.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
            if (k > 0) lp.setMargins(dp(12), 0, 0, 0);
            b.setPadding(dp(26), 0, dp(26), 0);
            row.addView(b, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.setMargins(0, dp(22), 0, 0);
        card.addView(row, rl);
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
        return row;
    }

    private void showMessage(String title, String body, String[] labels, Runnable[] actions) {
        cardButtons(card(title, body), labels, actions);
    }

    private void closeOverlay() {
        overlay.setVisibility(View.GONE);
        overlay.removeAllViews();
        loginCancel.set(true);
        refresh();
        primary.requestFocus();
    }

    private final AtomicBoolean loginCancel = new AtomicBoolean();

    private void showLogin() {
        LinearLayout c = card("用 Steam 手机应用扫码登录", "打开手机上的 Steam，在「Steam 令牌」页点扫码，扫描下面的二维码。\n登录后可以从 Steam 下载游戏、同步云存档，并记录游戏时长和成就。");
        ImageView qr = new ImageView(this);
        qr.setBackgroundColor(Color.WHITE);
        int pad = dp(10);
        qr.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(210), dp(210));
        ql.gravity = Gravity.CENTER_HORIZONTAL;
        ql.setMargins(0, dp(16), 0, 0);
        c.addView(qr, ql);
        TextView st = text(14, MUTED, false);
        st.setGravity(Gravity.CENTER);
        st.setText("正在向 Steam 申请二维码…");
        st.setPadding(0, dp(10), 0, 0);
        c.addView(st);
        cardButtons(c, new String[] { "取消" }, new Runnable[] { this::closeOverlay });
        loginCancel.set(false);
        AtomicBoolean myCancel = loginCancel;
        new Thread(() -> {
            try (SteamSession s = new SteamSession()) {
                String[] r = QrLogin.run(s, url -> ui.post(() -> {
                    Bitmap bmp = qrBitmap(url);
                    if (bmp != null) qr.setImageBitmap(bmp);
                    st.setText("等待手机上确认…");
                }));
                if (myCancel.get()) return;
                TokenStore.save(this, r[0], r[1]);
                ui.post(() -> {
                    closeOverlay();
                    toast("已登录 Steam：" + r[0]);
                    goOnline();
                });
            } catch (Throwable t) {
                if (myCancel.get()) return;
                ui.post(() -> showMessage("登录没有完成", message(t), new String[] { "重试", "关闭" }, new Runnable[] { this::showLogin, this::closeOverlay }));
            }
        }, "LomCloud-login").start();
    }

    private void showAccount() {
        TokenStore t = TokenStore.load(this);
        if (t == null) {
            showLogin();
            return;
        }
        LinearLayout c = card("Steam 账户", "已登录：" + t.account + "\n\n启动游戏前会先下载云存档，游戏里存档后自动上传；游戏时长和成就会记到这个账号。"
                + (Settings.offline(this) ? "\n\n现在是离线模式，不会连接 Steam。可以在「设置」里切回在线。" : ""));
        cardButtons(c, new String[] { "关闭", "退出登录" }, new Runnable[] { this::closeOverlay, () -> {
            signOut();
            closeOverlay();
            toast("已退出 Steam 登录");
        } });
    }

    private void showSettings() {
        LinearLayout c = card("设置", null);
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        int listH = Math.min(dp(470), getResources().getDisplayMetrics().heightPixels - dp(220));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listH);
        sl.setMargins(0, dp(12), 0, 0);
        c.addView(sv, sl);

        boolean dual = Features.dualScreen(this);
        boolean companion = Features.has(this, "companion"), bridge = Features.has(this, "bridge");

        list.addView(section("画面与性能", "下次进入游戏时生效"));
        list.addView(choiceRow("帧率上限", "安卓上 Unity 默认只跑 30 帧。60 帧流畅、发热适中；更高帧率需要屏幕支持。", Settings.FPS, Settings.FPS_LABEL, "fps", 60, null));
        list.addView(choiceRow("渲染分辨率", "低于 100% 时画面略软，但 GPU 负担更小、更凉快。", Settings.SCALE, Settings.SCALE_LABEL, "scale", 100, null));

        list.addView(section("操作", dual ? "双屏掌机自带手柄，触屏按键默认在接着手柄时隐藏" : "触屏点按照常操作游戏；这里加的是屏幕上的按键"));
        list.addView(choiceRow("触屏按键", "快捷键：右侧的返回、菜单（和咖啡伴侣）；虚拟手柄：摇杆、ABXY、肩键扳机，适合不想点菜单的场景。按键上的触摸不会传到游戏里。",
                new int[] { 0, 1, 2 }, new String[] { "关", "快捷键", "虚拟手柄" }, "touch", 1, null));
        list.addView(choiceRow("按键大小", "按屏幕大小选，平板可以调大。", new int[] { 80, 100, 125 }, new String[] { "小", "中", "大" }, "touchsize", 100, null));
        list.addView(choiceRow("按键不透明度", "调低后不挡画面。", new int[] { 40, 70, 100 }, new String[] { "40%", "70%", "100%" }, "touchalpha", 70, null));
        list.addView(choiceRow("接手柄时隐藏", "连上蓝牙/有线手柄（或掌机自带手柄）时不显示触屏按键。", new int[] { 1, 0 }, new String[] { "开", "关" }, "touchauto", 1, null));

        if (dual) {
            boolean lowerApp = Features.lowerAvailable(this);
            list.addView(section("双屏", "检测到第二块屏幕"));
            list.addView(choiceRow("游戏显示在", "主屏一般更大；也可以把游戏放到副屏，主屏留给别的应用。", new int[] { 0, 1 }, new String[] { "主屏", "副屏" }, "gamescreen", 0, null));
            list.addView(choiceRow("自动打开下屏伴侣", lowerApp ? "咖啡伴侣选「双屏版」时，进游戏的同时在另一块屏打开下屏伴侣（已内置，不用另装应用）。"
                    : "这个安装包没有内置下屏伴侣，这项不起作用。", new int[] { 1, 0 }, new String[] { "开", "关" }, "lowerapp", 1, null));
        }

        if (companion) {
            list.addView(section("咖啡伴侣", "原 Mod 的全部功能，按这里的选择在游戏启动时加载"));
            boolean canDual = dual && bridge;
            int[] cv = canDual ? new int[] { 0, 1, 2 } : new int[] { 0, 1 };
            String[] cl = canDual ? new String[] { "关闭", "普通版", "双屏版" } : new String[] { "关闭", "普通版" };
            list.addView(choiceRow("咖啡伴侣", "关闭：原版游戏，所有补丁都不生效。普通版：伴侣窗口显示在游戏画面里（触屏按键里有「伴侣」）。"
                    + (canDual ? "双屏版：窗口都放到下屏（内置的下屏伴侣），游戏画面保持干净。" : ""), cv, cl, "companion", canDual ? 2 : 1, null));
        }

        list.addView(section("Steam", null));
        list.addView(choiceRow("Steam 连接", "离线时不连接 Steam，直接进游戏：不同步云存档、不记时长和成就。切回在线后会补传存档、补记成就。",
                new int[] { 0, 1 }, new String[] { "在线", "离线模式" }, "offline", 0, v -> { syncedOk = false; if (v == 0) goOnline(); refresh(); }));
        list.addView(choiceRow("成就解锁提示", "Steam 记下新成就时，在游戏画面下方弹出成就卡片（名称、说明和图标），和电脑上的 Steam 一样。切到「开」可以预览。",
                new int[] { 1, 0 }, new String[] { "开", "关" }, "achtoast", 1, v -> { if (v == 1) AchievementToast.preview(this); }));
        list.addView(section("开发者", null));
        list.addView(choiceRow("帧率日志", "排查性能时打开：游戏每 5 秒把帧率写进 logcat（[LomFps]）。", new int[] { 0, 1 }, new String[] { "关", "开" }, "fpslog", 0, null));

        GameData.Port p = GameData.port(this);
        GameData.Status st = GameData.status(this);
        String data = st == GameData.Status.READY ? "已安装（Steam 清单 " + Long.toUnsignedString(GameData.installedManifest(this)) + "）"
                : st == GameData.Status.APK_CHANGED ? "需要更新（移植包换了新版本）" : "未安装";
        TextView about = text(13, MUTED, false);
        about.setText("游戏数据：" + data + "\n移植包：Steam build " + p.build + "，depot 清单 " + Long.toUnsignedString(p.manifest)
                + "\n可用存储空间：" + gb(GameData.freeBytes(this)));
        about.setLineSpacing(dp(2), 1f);
        about.setPadding(dp(4), dp(14), 0, 0);
        list.addView(about);

        cardButtons(c, new String[] { "完成", "检查并修复游戏数据" }, new Runnable[] { this::closeOverlay, () -> {
            closeOverlay();
            if (Settings.offline(this) && localTest == null) toast("离线模式下不能检查游戏数据，请先切回在线");
            else if (TokenStore.load(this) == null && localTest == null) toast("检查游戏数据需要先登录 Steam");
            else startInstall();
        } });
    }

    private View section(String title, String note) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.HORIZONTAL);
        s.setGravity(Gravity.BOTTOM);
        s.setPadding(dp(4), dp(16), 0, dp(4));
        View mark = new View(this);
        mark.setBackgroundColor(CINNABAR);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(4), dp(16));
        ml.setMargins(0, 0, dp(8), dp(3));
        s.addView(mark, ml);
        TextView t = text(15, INK, true);
        t.setTypeface(serifBold);
        t.setText(title);
        s.addView(t);
        if (note != null) {
            TextView n = text(12, MUTED, false);
            n.setText("  ·  " + note);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            s.addView(n);
        }
        return s;
    }

    // First start: settings whose best value depends on the device are written once, so the game
    // (which reads them through CloudBridge.intSetting) sees the same defaults as this screen.
    private void deviceDefaults() {
        android.content.SharedPreferences p = getSharedPreferences(Settings.PREFS, MODE_PRIVATE);
        if (!p.contains("companion")) {
            boolean dual = Features.dualScreen(this) && Features.has(this, "bridge");
            Settings.put(this, "companion", dual ? 2 : 1);
        }
    }

    private void setOffline(boolean on) {
        Settings.put(this, "offline", on ? 1 : 0);
        syncedOk = false;
        if (!on) {
            toast("已回到在线模式");
            goOnline();
        }
        refresh();
    }

    private View choiceRow(String title, String hint, int[] values, String[] labels, String key, int def, java.util.function.IntConsumer changed) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setFocusable(true);
        row.setBackground(rowBackground());
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(16, INK, true);
        t.setText(title);
        TextView h = text(12, MUTED, false);
        h.setText(hint);
        h.setPadding(0, dp(2), dp(12), 0);
        texts.addView(t);
        texts.addView(h);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(15, CINNABAR, true);
        row.addView(v);
        final int[] idx = { 0 };
        int cur = Settings.get(this, key, def);
        for (int i = 0; i < values.length; i++) if (values[i] == cur) idx[0] = i;
        v.setText("‹  " + labels[idx[0]] + "  ›");
        Runnable step = () -> {
            idx[0] = (idx[0] + 1) % values.length;
            Settings.put(this, key, values[idx[0]]);
            v.setText("‹  " + labels[idx[0]] + "  ›");
            if (changed != null) changed.accept(values[idx[0]]);
        };
        row.setOnClickListener(x -> step.run());
        row.setOnKeyListener((x, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { step.run(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                idx[0] = (idx[0] + values.length - 2) % values.length;
                step.run();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(lp);
        return row;
    }

    private void toast(String s) {
        TextView t = text(15, PAPER, false);
        t.setText(s);
        t.setPadding(dp(22), dp(10), dp(22), dp(10));
        t.setBackground(solid(Color.argb(235, 43, 36, 32), dp(6)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.setMargins(0, dp(24), 0, 0);
        root.addView(t, lp);
        t.setAlpha(0f);
        t.setTranslationY(-dp(12));
        t.animate().alpha(1f).translationY(0).setDuration(220).start();
        ui.postDelayed(() -> t.animate().alpha(0f).translationY(-dp(12)).setDuration(220).withEndAction(() -> root.removeView(t)).start(), 2600);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                // Right after launch the window is in touch mode and nothing has focus: A then means
                // the main button (or the first button of an open card).
                View f = getCurrentFocus();
                if (f == null) f = overlay.getVisibility() == View.VISIBLE ? firstFocusable(overlay) : primary;
                if (f != null) {
                    f.requestFocus();
                    f.performClick();
                }
            }
            return true;
        }
        if (getCurrentFocus() == null && e.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            View f = overlay.getVisibility() == View.VISIBLE ? firstFocusable(overlay) : primary;
            if (f != null) f.requestFocus();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (overlay.getVisibility() == View.VISIBLE) closeOverlay();
                else if (code == KeyEvent.KEYCODE_BACK) finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static View firstFocusable(View v) {
        if (v.isFocusable() && v.getVisibility() == View.VISIBLE && !(v instanceof ViewGroup && ((ViewGroup) v).getChildCount() > 0 && !(v instanceof LinearLayout && v.isClickable()))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return v.isFocusable() ? v : null;
    }

    // ---------------------------------------------------------------- widgets

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private TextView chip() {
        TextView t = text(13, INK, false);
        t.setPadding(dp(12), dp(6), dp(14), dp(6));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(150, 255, 252, 245));
        bg.setStroke(dp(1), Color.argb(40, 43, 36, 32));
        bg.setCornerRadius(dp(4));
        t.setBackground(bg);
        t.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(10), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void setChip(TextView chip, int dot, String label) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(1));
        d.setColor(dot);
        d.setSize(dp(7), dp(7));
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
        chip.setText(label);
    }

    // Primary: a cinnabar seal; secondary: ink outline on paper. Focus gets an ink frame.
    private TextView button(String label, boolean main) {
        TextView b = text(main ? 19 : 15, main ? PAPER : INK, true);
        b.setText(label);
        if (main) {
            b.setTypeface(serifBold);
            b.setLetterSpacing(0.12f);
        }
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setSingleLine(true);
        StateListDrawable s = new StateListDrawable();
        int r = dp(main ? 6 : 5);
        GradientDrawable normal = main ? solid(CINNABAR, r) : outline(Color.argb(110, 255, 252, 245), Color.argb(120, 43, 36, 32), r, dp(1));
        GradientDrawable focused = main ? solid(CINNABAR, r) : outline(Color.argb(210, 255, 252, 245), INK, r, dp(2));
        if (main) focused.setStroke(dp(3), INK);
        GradientDrawable pressed = main ? solid(CINNABAR_DARK, r) : outline(Color.argb(60, 43, 36, 32), INK, r, dp(2));
        s.addState(new int[] { android.R.attr.state_pressed }, pressed);
        s.addState(new int[] { android.R.attr.state_focused }, focused);
        s.addState(new int[0], normal);
        b.setBackground(s);
        if (main) b.setElevation(dp(3));
        b.setOnFocusChangeListener((v, has) -> v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(120).start());
        return b;
    }

    private StateListDrawable rowBackground() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable f = solid(Color.argb(28, 43, 36, 32), dp(6));
        f.setStroke(dp(2), CINNABAR);
        s.addState(new int[] { android.R.attr.state_focused }, f);
        s.addState(new int[0], solid(Color.argb(12, 43, 36, 32), dp(6)));
        return s;
    }

    private static GradientDrawable solid(int c, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(c);
        g.setCornerRadius(r);
        return g;
    }

    private static GradientDrawable outline(int fill, int stroke, int r, int w) {
        GradientDrawable g = solid(fill, r);
        g.setStroke(w, stroke);
        return g;
    }

    // Thin ink track with a cinnabar fill; f < 0 shows an indeterminate sweep.
    private final class Bar extends View {
        private float f = -1, sweep;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Bar(Context c) {
            super(c);
            track.setColor(Color.argb(36, 43, 36, 32));
            fill.setColor(CINNABAR);
        }

        void set(float v) {
            f = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, track);
            if (f >= 0) {
                rect.set(0, 0, Math.max(h, w * Math.min(1f, f)), h);
                c.drawRoundRect(rect, r, r, fill);
            } else {
                sweep = (sweep + 0.012f) % 1.3f;
                float x = (sweep - 0.3f) * w;
                rect.set(Math.max(0, x), 0, Math.min(w, x + w * 0.3f), h);
                if (rect.width() > 0) c.drawRoundRect(rect, r, r, fill);
                postInvalidateOnAnimation();
            }
        }
    }

    private Bitmap qrBitmap(String url) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 400, 400, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    px[y * w + x] = m.get(x, y) ? INK : Color.WHITE;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private Bitmap asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static String gb(long b) {
        return b >= 1e9 ? String.format(Locale.CHINA, "%.1f GB", b / 1e9) : mb(b);
    }

    static String mb(long b) {
        return String.format(Locale.CHINA, "%.0f MB", b / 1e6);
    }

    static String duration(long s) {
        if (s < 60) return s + " 秒";
        if (s < 3600) return (s / 60) + " 分钟";
        return (s / 3600) + " 小时 " + (s % 3600 / 60) + " 分";
    }

    private static String message(Throwable t) {
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void hideBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0x1706);
        }
    }
}
