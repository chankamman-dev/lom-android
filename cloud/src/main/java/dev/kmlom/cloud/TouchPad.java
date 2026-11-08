package dev.kmlom.cloud;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

// On-screen controls over the game (launcher setting "触屏按键"). They are Android views on top of the
// Unity view: a touch that lands on a control is consumed here and never reaches the game (the game's
// dialogue advances on any legacy mouse click, so drawing the controls inside Unity would leak taps);
// every other touch falls through to Unity as before. The game polls state() each frame
// (LomAndroid.LomTouch) and feeds it to a virtual Input System gamepad, which the game already
// supports fully (its action map binds the left stick, face buttons, shoulders, triggers, Start).
//
// Modes: 1 = quick keys (back, menu, companion), 2 = full gamepad. Hidden while a physical gamepad is
// connected when "auto hide" is on (Thor's built-in controls count).
public final class TouchPad {
    private static final String TAG = "LomTouch";

    // Bit numbers follow Unity's GamepadButton; triggers get bits 14/15 and become axes on the C# side.
    static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, NORTH = 4, EAST = 5, SOUTH = 6, WEST = 7,
            LB = 10, RB = 11, START = 12, SELECT = 13, LT = 14, RT = 15;
    static final int CMD_COMPANION = 1, CMD_AI = 2;

    private static volatile float lx, ly;
    private static volatile int buttons, commands;
    private static FrameLayout layer;

    // Called by the game once it is up. Builds the layer according to the launcher settings.
    public static void attach(Activity a) {
        if (a == null) return;
        a.runOnUiThread(() -> {
            try {
                build(a);
            } catch (Throwable t) {
                Log.w(TAG, "touch layer failed: " + t);
            }
        });
    }

    // {left stick x, y, button bits, one-shot commands}; commands are cleared by the read. A button
    // pressed and released between two game frames (a quick tap) is still reported as down once.
    public static float[] state() {
        int c, b;
        synchronized (TouchPad.class) {
            c = commands;
            commands = 0;
            b = buttons | tapped;
            tapped = 0;
        }
        return new float[] { lx, ly, b, c };
    }

    private static int tapped;

    private static synchronized void command(int c) {
        commands |= c;
    }

    private static void set(int bit, boolean down) {
        synchronized (TouchPad.class) {
            buttons = down ? buttons | (1 << bit) : buttons & ~(1 << bit);
            if (down) tapped |= 1 << bit;
        }
    }

    private static void build(Activity a) {
        int mode = Settings.get(a, "touch", 1);
        if (mode == 0 || layer != null) return;
        float size = Settings.get(a, "touchsize", 100) / 100f;
        float alpha = Settings.get(a, "touchalpha", 70) / 100f;
        boolean autoHide = Settings.get(a, "touchauto", 1) != 0;
        boolean companion = Settings.get(a, "companion", 1) == 1 && Features.has(a, "companion");
        float d = a.getResources().getDisplayMetrics().density * size;

        FrameLayout l = new FrameLayout(a);
        l.setAlpha(alpha);
        l.setMotionEventSplittingEnabled(true);
        if (mode == 2) {
            add(l, new Stick(a), (int) (150 * d), (int) (150 * d), Gravity.BOTTOM | Gravity.START, (int) (48 * d), (int) (40 * d));
            int bs = (int) (58 * d), cx = (int) (118 * d), cy = (int) (100 * d), sp = (int) (62 * d);
            add(l, new Key(a, "A", SOUTH, 0), bs, bs, Gravity.BOTTOM | Gravity.END, cx - bs / 2, cy - sp - bs / 2);
            add(l, new Key(a, "B", EAST, 0), bs, bs, Gravity.BOTTOM | Gravity.END, cx - sp - bs / 2, cy - bs / 2);
            add(l, new Key(a, "X", WEST, 0), bs, bs, Gravity.BOTTOM | Gravity.END, cx + sp - bs / 2, cy - bs / 2);
            add(l, new Key(a, "Y", NORTH, 0), bs, bs, Gravity.BOTTOM | Gravity.END, cx - bs / 2, cy + sp - bs / 2);
            int pw = (int) (86 * d), ph = (int) (40 * d), m = (int) (20 * d);
            add(l, new Key(a, "LT", LT, 0), pw, ph, Gravity.TOP | Gravity.START, m, m);
            add(l, new Key(a, "LB", LB, 0), pw, ph, Gravity.TOP | Gravity.START, m, m + ph + (int) (10 * d));
            add(l, new Key(a, "RT", RT, 0), pw, ph, Gravity.TOP | Gravity.END, m, m);
            add(l, new Key(a, "RB", RB, 0), pw, ph, Gravity.TOP | Gravity.END, m, m + ph + (int) (10 * d));
            int sw = (int) (64 * d), sh = (int) (32 * d);
            add(l, new Key(a, "选择", SELECT, 0), sw, sh, Gravity.TOP | Gravity.CENTER_HORIZONTAL, -(int) (40 * d), m);
            add(l, new Key(a, "菜单", START, 0), sw, sh, Gravity.TOP | Gravity.CENTER_HORIZONTAL, (int) (40 * d), m);
            if (companion) add(l, new Key(a, "伴侣", -1, CMD_COMPANION), sw, sh, Gravity.TOP | Gravity.END, m + pw + (int) (14 * d), m);
        } else {
            int w = (int) (66 * d), h = (int) (40 * d), m = (int) (18 * d), gap = (int) (12 * d);
            int top = (int) (90 * d);
            add(l, new Key(a, "返回", EAST, 0), w, h, Gravity.TOP | Gravity.END, m, top);
            add(l, new Key(a, "菜单", START, 0), w, h, Gravity.TOP | Gravity.END, m, top + h + gap);
            if (companion) add(l, new Key(a, "伴侣", -1, CMD_COMPANION), w, h, Gravity.TOP | Gravity.END, m, top + 2 * (h + gap));
        }
        a.addContentView(l, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer = l;
        Log.i(TAG, "touch layer: mode " + mode + ", size " + size + ", auto hide " + autoHide);

        if (autoHide) {
            InputManager im = (InputManager) a.getSystemService(Context.INPUT_SERVICE);
            Runnable check = () -> {
                boolean pad = hasGamepad();
                l.setVisibility(pad ? View.GONE : View.VISIBLE);
                if (pad) {
                    lx = ly = 0;
                    buttons = 0;
                }
            };
            check.run();
            im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { check.run(); }
                @Override public void onInputDeviceRemoved(int id) { check.run(); }
                @Override public void onInputDeviceChanged(int id) { check.run(); }
            }, new Handler(Looper.getMainLooper()));
        }
    }

    static boolean hasGamepad() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) continue;
            int s = dev.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    private static void add(FrameLayout l, View v, int w, int h, int gravity, int mx, int my) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        if ((gravity & Gravity.END) == Gravity.END) p.rightMargin = mx;
        else if ((gravity & Gravity.CENTER_HORIZONTAL) == Gravity.CENTER_HORIZONTAL) p.leftMargin = mx * 2; // offset from centre
        else p.leftMargin = mx;
        if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) p.bottomMargin = my;
        else p.topMargin = my;
        l.addView(v, p);
    }

    // ---- views: ink on paper, like the launcher ----
    private static final int INK = Color.argb(150, 30, 24, 20), INK_DOWN = Color.argb(220, 168, 50, 40), PAPER = Color.rgb(241, 233, 218);

    private static final class Key extends View {
        final String label;
        final int bit, cmd;
        boolean down;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();

        Key(Context c, String label, int bit, int cmd) {
            super(c);
            this.label = label;
            this.bit = bit;
            this.cmd = cmd;
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.argb(170, 241, 233, 218));
            text.setColor(PAPER);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("serif", Typeface.BOLD));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = Math.min(w, h) / 2;
            r.set(1, 1, w - 1, h - 1);
            fill.setColor(down ? INK_DOWN : INK);
            ring.setStrokeWidth(Math.max(1.5f, rad * 0.05f));
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, ring);
            text.setTextSize(label.length() > 1 ? h * 0.40f : h * 0.46f);
            c.drawText(label, w / 2, h / 2 - (text.descent() + text.ascent()) / 2, text);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    if (bit >= 0) set(bit, true);
                    if (cmd != 0) command(cmd);
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    if (bit >= 0) set(bit, false);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    private static final class Stick extends View {
        float kx, ky; // knob offset, -1..1
        boolean active;
        final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Stick(Context c) {
            super(c);
            base.setColor(Color.argb(90, 30, 24, 20));
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(Color.argb(150, 241, 233, 218));
            knob.setColor(INK);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), R = Math.min(w, h) / 2, cx = w / 2, cy = h / 2;
            ring.setStrokeWidth(R * 0.03f);
            c.drawCircle(cx, cy, R - 2, base);
            c.drawCircle(cx, cy, R - 2, ring);
            knob.setColor(active ? INK_DOWN : INK);
            float kr = R * 0.42f;
            c.drawCircle(cx + kx * (R - kr), cy + ky * (R - kr), kr, knob);
            c.drawCircle(cx + kx * (R - kr), cy + ky * (R - kr), kr, ring);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                active = false;
                kx = ky = 0;
                lx = ly = 0;
                invalidate();
                return true;
            }
            float R = Math.min(getWidth(), getHeight()) / 2f;
            float dx = (e.getX() - getWidth() / 2f) / (R * 0.75f), dy = (e.getY() - getHeight() / 2f) / (R * 0.75f);
            float len = (float) Math.hypot(dx, dy);
            if (len > 1) {
                dx /= len;
                dy /= len;
            }
            active = true;
            kx = dx;
            ky = dy;
            lx = dx;
            ly = -dy; // Unity's stick: up is positive
            invalidate();
            return true;
        }
    }

    private TouchPad() { }
}
