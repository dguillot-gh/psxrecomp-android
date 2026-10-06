package com.psxrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputType;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Full on-screen PS1 controller, drawn over the SDL surface and shared by every
 * game's Android app.
 *
 * Layout is measured in units of the screen HEIGHT, never the width: height is
 * the stable dimension in landscape, so the controls keep the same physical
 * size on any phone and the wide middle, where the picture is, stays clear.
 * Each control is anchored to the left edge, the right edge or the centre.
 *
 * Every finger is tracked on its own, so the D-pad, face buttons and shoulders
 * can all be held together. A finger that slides from one button onto another
 * presses the new one. The held set is sent to the runtime as raw PS1 pad bits
 * (PsxInput), so Circle stays Circle whatever the game does with it.
 *
 * The small button at the top centre opens the layout editor: drag a control to
 * move it, then use the toolbar to resize, rename or hide it. The layout is
 * saved per app in SharedPreferences.
 */
public final class PadOverlay extends View {
    private static final int KIND_BUTTON = 0, KIND_SHOULDER = 1, KIND_DPAD = 2,
            KIND_STICK = 3, KIND_EDIT = 4, KIND_FPS = 5,
            KIND_MOUSE = 6;   /* PS1 Mouse button (mouse games); Control.stick = 0 left, 1 right */
    private static final int ANCHOR_LEFT = -1, ANCHOR_CENTER = 0, ANCHOR_RIGHT = 1;
    /* Hit areas are larger than the drawn control: thumbs land off-centre. */
    private static final float HIT_GROW = 1.3f;
    private static final String PREFS = "psx_pad_layout";
    private static final String PREFS_KEY = "layout_v1";

    /* Face-button symbol colours (the PS1 pad's own). */
    private static final int COLOR_TRIANGLE = 0xFF3DDCB0;
    private static final int COLOR_CIRCLE = 0xFFFF6B6B;
    private static final int COLOR_CROSS = 0xFF8AB4FF;
    private static final int COLOR_SQUARE = 0xFFFF8AD8;

    private static final class Control {
        final String id;
        final int kind;
        final int bits;          /* pad bits for a button; 0 for d-pad/stick */
        final int stick;         /* 0/1 for a stick */
        final String defaultLabel;
        final int defAnchor;
        final float defX, defY;
        final float size;        /* radius, or half-height for a shoulder */
        final float aspect;      /* half-width / half-height for a shoulder */
        final boolean defVisible;
        int anchor;
        float x, y, scale = 1.0f;
        String label;
        boolean visible;

        Control(String id, int kind, int bits, int stick, String label, int anchor,
                float x, float y, float size, float aspect, boolean visible) {
            this.id = id;
            this.kind = kind;
            this.bits = bits;
            this.stick = stick;
            this.defaultLabel = label;
            this.defAnchor = anchor;
            this.defX = x;
            this.defY = y;
            this.size = size;
            this.aspect = aspect;
            this.defVisible = visible;
            reset();
        }

        void reset() {
            anchor = defAnchor;
            x = defX;
            y = defY;
            scale = 1.0f;
            label = defaultLabel;
            visible = defVisible;
        }
    }

    /** What one finger is doing. */
    private static final class Touch {
        Control control;
        int dpadMask;
        float stickX, stickY;    /* knob offset, pixels */
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final List<Control> controls = new ArrayList<>();
    private final SparseArray<Touch> touches = new SparseArray<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;
    private final boolean stickDpad;
    private int heldBits = 0;
    private int stickBytes0 = 0x8080, stickBytes1 = 0x8080;

    /* Layout editor. */
    private boolean editing;
    private Control selected;
    private int dragPointer = -1;
    private float dragDx, dragDy;
    private static final String[] TOOLS = { "Smaller", "Bigger", "Rename", "Hide/Show",
            "Opacity", "Reset all", "Done" };
    /* FPS counter (runtime status line, top left), remembered per game. */
    private static final String PREFS_FPS = "fps_counter";
    private boolean fpsOn;

    /* Mouse games (game.toml [controller] mouse = true, e.g. Policenauts): the PS1
     * Mouse is in port 1 and the game never reads the pad, so the pad's buttons are
     * replaced: the D-pad steers the cursor (the runtime turns its directions into
     * mouse motion: a tap is one step, holding glides), ACT / MOVE are the left /
     * right mouse buttons (held while touched, so drags work), and the rest of the
     * screen is a trackpad: drag moves the cursor (slow drags are precise, fast
     * flicks travel further), a quick tap clicks ACT, a two-finger tap MOVE. */
    private final boolean mouseMode;
    /* Mouse counts per dp of finger travel: SLOW for careful drags, rising to FAST. */
    private static final float MOUSE_SLOW = 0.45f, MOUSE_FAST = 1.6f;
    /* Finger speed (dp per ms) at which the gain reaches MOUSE_FAST. */
    private static final float MOUSE_FAST_SPEED = 1.5f;
    private static final long TAP_MAX_MS = 250;
    /* Long enough for the game to poll the press at a reduced frame rate. */
    private static final long CLICK_HOLD_MS = 80;
    private int padPointer = -1;
    private float padLastX, padLastY, padDownX, padDownY, fracX, fracY;
    private long padDownTime, padLastTime;
    private boolean padMoved;
    private int padFingers;
    private final float touchSlop;
    private int mouseHeld;   /* bit 0 left, bit 1 right, from the ACT / MOVE controls */
    /* TOOLS index where the second toolbar row (Done) starts. Game actions and
     * display options live in the menu panel (PsxMenu). */
    private static final int GAME_TOOLS_FROM = 6;
    private final PsxMenu menu;
    /* Whole-pad opacity steps offered by the editor. */
    private static final float[] OPACITY = { 1.0f, 0.75f, 0.5f, 0.3f };
    private int opacityStep = 0;
    /* Hidden while a physical controller is in use; any touch brings it back. */
    private boolean controllerHidden;
    private final RectF[] toolRects = new RectF[TOOLS.length];

    /**
     * @param analogSticks show the two analog sticks (games that read them).
     *                     When shown, the left stick also presses the D-pad
     *                     directions, so menus that read only the D-pad work.
     */
    public PadOverlay(Activity activity, boolean analogSticks) {
        super(activity);
        this.activity = activity;
        this.stickDpad = analogSticks;
        setWillNotDraw(false);
        density = getResources().getDisplayMetrics().density;
        prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        mouseMode = readMouseFlag(activity);
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        for (int i = 0; i < toolRects.length; i++) toolRects[i] = new RectF();

        final int L = ANCHOR_LEFT, R = ANCHOR_RIGHT, C = ANCHOR_CENTER;
        /* Left: shoulders on top, D-pad, Select below. Right mirrors it. */
        add(new Control("dpad", KIND_DPAD, 0, 0, "", L, 0.27f, 0.60f, 0.19f, 1, true));
        add(new Control("l1", KIND_SHOULDER, PsxInput.L1, 0, "L1", L, 0.14f, 0.09f, 0.050f, 1.7f, true));
        add(new Control("l2", KIND_SHOULDER, PsxInput.L2, 0, "L2", L, 0.36f, 0.09f, 0.050f, 1.7f, true));
        add(new Control("select", KIND_SHOULDER, PsxInput.SELECT, 0, "SELECT", L, 0.16f, 0.92f, 0.040f, 2.1f, true));
        add(new Control("triangle", KIND_BUTTON, PsxInput.TRIANGLE, 0, "△", R, 0.27f, 0.47f, 0.072f, 1, true));
        add(new Control("cross", KIND_BUTTON, PsxInput.CROSS, 0, "✕", R, 0.27f, 0.73f, 0.072f, 1, true));
        add(new Control("square", KIND_BUTTON, PsxInput.SQUARE, 0, "□", R, 0.40f, 0.60f, 0.072f, 1, true));
        add(new Control("circle", KIND_BUTTON, PsxInput.CIRCLE, 0, "○", R, 0.14f, 0.60f, 0.072f, 1, true));
        add(new Control("r1", KIND_SHOULDER, PsxInput.R1, 0, "R1", R, 0.14f, 0.09f, 0.050f, 1.7f, true));
        add(new Control("r2", KIND_SHOULDER, PsxInput.R2, 0, "R2", R, 0.36f, 0.09f, 0.050f, 1.7f, true));
        add(new Control("start", KIND_SHOULDER, PsxInput.START, 0, "START", R, 0.16f, 0.92f, 0.040f, 2.1f, true));
        add(new Control("lstick", KIND_STICK, 0, 0, "", L, 0.52f, 0.80f, 0.13f, 1, analogSticks));
        add(new Control("rstick", KIND_STICK, 0, 1, "", R, 0.52f, 0.80f, 0.13f, 1, analogSticks));
        add(new Control("edit", KIND_EDIT, 0, 0, "", C, 0.0f, 0.045f, 0.032f, 1, true));
        /* FPS readout (pad menu "FPS"): a label, not a button; movable and resizable in
         * the editor. Default: the black bar left of the picture, between L1 and the D-pad. */
        add(new Control("fps", KIND_FPS, 0, 0, "", L, 0.13f, 0.24f, 0.028f, 2.3f, true));
        /* Mouse games only: the PS1 Mouse's two buttons, named as Policenauts names them. */
        add(new Control("mouse_left", KIND_MOUSE, 0, 0, "ACT", R, 0.22f, 0.58f, 0.075f, 1.5f, true));
        add(new Control("mouse_right", KIND_MOUSE, 0, 1, "MOVE", R, 0.22f, 0.82f, 0.065f, 1.5f, true));
        loadLayout();
        menu = new PsxMenu(activity, this);
        PsxMenu.autoloadAfterRestart(activity, this);
        fpsOn = prefs.getBoolean(PREFS_FPS, false);
        if (fpsOn) {
            try { PsxInput.nativeSetFpsCounter(true); }
            catch (UnsatisfiedLinkError e) { /* game library failed to load; SDL reports it */ }
        }
    }

    /** game.toml [controller] mouse = true in this app's config (assets/game.toml.in). */
    private static boolean readMouseFlag(Activity activity) {
        try (java.io.InputStream in = activity.getAssets().open("game.toml.in")) {
            java.util.Scanner s = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
            String toml = s.hasNext() ? s.next() : "";
            return java.util.regex.Pattern.compile("(?m)^\\s*mouse\\s*=\\s*true\\b").matcher(toml).find();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private String toolLabel(int i) { return TOOLS[i]; }

    /* ---- used by the menu panel (PsxMenu) ------------------------------- */

    boolean isFpsOn() { return fpsOn; }

    void setFpsOn(boolean on) { if (on != fpsOn) toggleFps(); }

    void startEditing() { setEditing(true); }

    int opacityPercent() { return Math.round(OPACITY[opacityStep] * 100); }

    void cycleOpacity() {
        opacityStep = (opacityStep + 1) % OPACITY.length;
        applyAlpha();
        saveLayout();
    }

    private void add(Control c) { controls.add(c); }

    /** Mouse games show the D-pad, ACT / MOVE, the menu button and the FPS readout;
     *  pad games everything but ACT / MOVE. */
    private boolean inMode(Control c) {
        if (c.kind == KIND_MOUSE) return mouseMode;
        if (!mouseMode) return true;
        return c.kind == KIND_EDIT || c.kind == KIND_FPS || c.kind == KIND_DPAD;
    }

    /* ---- geometry ---------------------------------------------------- */

    private float unit() { return getHeight(); }

    private float cx(Control c) {
        float h = unit();
        if (c.anchor == ANCHOR_LEFT) return c.x * h;
        if (c.anchor == ANCHOR_RIGHT) return getWidth() - c.x * h;
        return getWidth() * 0.5f + c.x * h;
    }

    private float cy(Control c) { return c.y * unit(); }

    private float radius(Control c) { return c.size * c.scale * unit(); }

    private boolean hit(Control c, float x, float y) {
        float dx = x - cx(c), dy = y - cy(c), r = radius(c);
        switch (c.kind) {
            case KIND_SHOULDER:
            case KIND_FPS:
            case KIND_MOUSE:
                return Math.abs(dx) <= r * c.aspect * HIT_GROW && Math.abs(dy) <= r * HIT_GROW * 1.2f;
            case KIND_DPAD:
                return Math.hypot(dx, dy) <= r * 1.15f;
            case KIND_EDIT:
                return Math.hypot(dx, dy) <= r * 1.6f;
            default:
                return Math.hypot(dx, dy) <= r * HIT_GROW;
        }
    }

    /** Closest live control under (x,y), or null. */
    private Control controlAt(float x, float y, boolean includeHidden) {
        Control best = null;
        double bestDist = Double.MAX_VALUE;
        for (Control c : controls) {
            if (!c.visible && !includeHidden) continue;
            /* The FPS readout never takes a touch while playing; the editor can move it. */
            if (c.kind == KIND_FPS && !editing) continue;
            if (!inMode(c)) continue;
            if (!hit(c, x, y)) continue;
            double d = Math.hypot(x - cx(c), y - cy(c)) / Math.max(1.0f, radius(c));
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    /* ---- drawing ----------------------------------------------------- */

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editing) canvas.drawColor(0x66000000);
        for (Control c : controls) {
            if (!c.visible && !editing) continue;
            if (editing && c.kind == KIND_EDIT) continue;
            if (!inMode(c)) continue;
            if (c.kind == KIND_FPS && !fpsOn && !editing) continue;
            int alpha = c.visible ? 255 : 90;
            if (c.kind == KIND_FPS) alpha = fpsOn ? 255 : 90;
            switch (c.kind) {
                case KIND_FPS: drawFps(canvas, c, alpha); break;
                case KIND_MOUSE: drawShoulder(canvas, c, alpha); break;
                case KIND_DPAD: drawDpad(canvas, c, alpha); break;
                case KIND_STICK: drawStick(canvas, c, alpha); break;
                case KIND_SHOULDER: drawShoulder(canvas, c, alpha); break;
                case KIND_EDIT: drawEditButton(canvas, c); break;
                default: drawButton(canvas, c, alpha); break;
            }
            if (editing && c == selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3.0f * density);
                paint.setColor(0xFFFFD54F);
                float r = radius(c);
                float hw = c.kind == KIND_SHOULDER ? r * c.aspect : r;
                rect.set(cx(c) - hw - 6 * density, cy(c) - r - 6 * density,
                        cx(c) + hw + 6 * density, cy(c) + r + 6 * density);
                canvas.drawRoundRect(rect, 8 * density, 8 * density, paint);
            }
        }
        if (editing) drawToolbar(canvas);
        /* The runtime measures once a second; redraw the readout twice a second. */
        if (fpsOn && !editing) postInvalidateDelayed(500);
    }

    private void drawFps(Canvas canvas, Control c, int alpha) {
        float x = cx(c), y = cy(c), hh = radius(c), hw = hh * c.aspect;
        float fps = 0;
        if (fpsOn) {
            try { fps = PsxInput.nativeGameFps(); } catch (UnsatisfiedLinkError e) { fps = 0; }
        }
        String text = fps > 0 ? Math.round(fps) + " FPS" : "-- FPS";
        rect.set(x - hw, y - hh, x + hw, y + hh);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        paint.setAlpha(Math.min(0x99, alpha));
        canvas.drawRoundRect(rect, hh * 0.5f, hh * 0.5f, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(alpha);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(hh * 1.1f);
        float w = paint.measureText(text);
        if (w > hw * 1.8f) paint.setTextSize(hh * 1.1f * hw * 1.8f / w);
        canvas.drawText(text, x, y + paint.getTextSize() * 0.36f, paint);
        paint.setFakeBoldText(false);
    }

    private boolean held(Control c) {
        if (c.kind == KIND_DPAD) return false;
        if (c.kind == KIND_MOUSE) return (mouseHeld & (1 << c.stick)) != 0;
        return c.bits != 0 && (heldBits & c.bits) != 0;
    }

    private void fillBody(Canvas canvas, boolean pressed, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0xB0FFFFFF : 0x50000000);
        paint.setAlpha(Math.min(paint.getAlpha(), alpha));
    }

    private void strokeEdge(int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.0f * density);
        paint.setColor(0xB0FFFFFF);
        paint.setAlpha(Math.min(paint.getAlpha(), alpha));
    }

    private void drawButton(Canvas canvas, Control c, int alpha) {
        float x = cx(c), y = cy(c), r = radius(c);
        boolean pressed = held(c);
        fillBody(canvas, pressed, alpha);
        canvas.drawCircle(x, y, r, paint);
        strokeEdge(alpha);
        canvas.drawCircle(x, y, r, paint);
        if (c.label.equals(c.defaultLabel)) drawSymbol(canvas, c, x, y, r, alpha);
        else drawLabel(canvas, c.label, x, y, r, pressed, alpha);
    }

    /** The face buttons' own shapes, drawn rather than typed so they never
     *  depend on a font having the glyphs. */
    private void drawSymbol(Canvas canvas, Control c, float x, float y, float r, int alpha) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2.0f, r * 0.11f));
        float s = r * 0.42f;
        switch (c.id) {
            case "triangle":
                paint.setColor(COLOR_TRIANGLE);
                paint.setAlpha(alpha);
                path.reset();
                path.moveTo(x, y - s);
                path.lineTo(x + s * 0.95f, y + s * 0.65f);
                path.lineTo(x - s * 0.95f, y + s * 0.65f);
                path.close();
                canvas.drawPath(path, paint);
                break;
            case "circle":
                paint.setColor(COLOR_CIRCLE);
                paint.setAlpha(alpha);
                canvas.drawCircle(x, y, s * 0.85f, paint);
                break;
            case "cross":
                paint.setColor(COLOR_CROSS);
                paint.setAlpha(alpha);
                canvas.drawLine(x - s * 0.75f, y - s * 0.75f, x + s * 0.75f, y + s * 0.75f, paint);
                canvas.drawLine(x - s * 0.75f, y + s * 0.75f, x + s * 0.75f, y - s * 0.75f, paint);
                break;
            case "square":
                paint.setColor(COLOR_SQUARE);
                paint.setAlpha(alpha);
                canvas.drawRect(x - s * 0.7f, y - s * 0.7f, x + s * 0.7f, y + s * 0.7f, paint);
                break;
            default:
                drawLabel(canvas, c.label, x, y, r, held(c), alpha);
                break;
        }
    }

    private void drawLabel(Canvas canvas, String label, float x, float y, float r,
                           boolean pressed, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0xFF000000 : 0xFFFFFFFF);
        paint.setAlpha(alpha);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        float size = r * 0.62f;
        paint.setTextSize(size);
        float w = paint.measureText(label);
        float max = r * 1.7f;
        if (w > max) paint.setTextSize(size * max / w);
        canvas.drawText(label, x, y + paint.getTextSize() * 0.36f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawShoulder(Canvas canvas, Control c, int alpha) {
        float x = cx(c), y = cy(c), hh = radius(c), hw = hh * c.aspect;
        boolean pressed = held(c);
        rect.set(x - hw, y - hh, x + hw, y + hh);
        fillBody(canvas, pressed, alpha);
        canvas.drawRoundRect(rect, hh, hh, paint);
        strokeEdge(alpha);
        canvas.drawRoundRect(rect, hh, hh, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0xFF000000 : 0xFFFFFFFF);
        paint.setAlpha(alpha);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(hh * 0.85f);
        float w = paint.measureText(c.label);
        if (w > hw * 1.7f) paint.setTextSize(hh * 0.85f * hw * 1.7f / w);
        canvas.drawText(c.label, x, y + paint.getTextSize() * 0.36f, paint);
        paint.setFakeBoldText(false);
    }

    private void drawDpad(Canvas canvas, Control c, int alpha) {
        float x = cx(c), y = cy(c), r = radius(c);
        int mask = 0;
        for (int i = 0; i < touches.size(); i++)
            if (touches.valueAt(i).control == c) mask |= touches.valueAt(i).dpadMask;
        float arm = r * 0.36f;  /* half-width of each arm */
        /* Body: a plus shape. */
        path.reset();
        path.addRoundRect(new RectF(x - arm, y - r, x + arm, y + r), arm * 0.4f, arm * 0.4f, Path.Direction.CW);
        path.addRoundRect(new RectF(x - r, y - arm, x + r, y + arm), arm * 0.4f, arm * 0.4f, Path.Direction.CW);
        path.setFillType(Path.FillType.WINDING);
        fillBody(canvas, false, alpha);
        canvas.drawPath(path, paint);
        /* Pressed arms. */
        int[] bits = { PsxInput.UP, PsxInput.DOWN, PsxInput.LEFT, PsxInput.RIGHT };
        float[][] dirs = { {0, -1}, {0, 1}, {-1, 0}, {1, 0} };
        for (int i = 0; i < 4; i++) {
            float dx = dirs[i][0], dy = dirs[i][1];
            float ax = x + dx * r * 0.62f, ay = y + dy * r * 0.62f;
            if ((mask & bits[i]) != 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xB0FFFFFF);
                paint.setAlpha(Math.min(0xB0, alpha));
                /* The arm from just off the centre out to its tip. */
                float near = arm * 0.6f;
                if (dx != 0) rect.set(x + dx * near, y - arm, x + dx * r, y + arm);
                else rect.set(x - arm, y + dy * near, x + arm, y + dy * r);
                rect.sort();
                canvas.drawRoundRect(rect, arm * 0.4f, arm * 0.4f, paint);
            }
            /* Arrow head. */
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((mask & bits[i]) != 0 ? 0xFF000000 : 0xFFFFFFFF);
            paint.setAlpha(alpha);
            float t = arm * 0.55f;
            path.reset();
            path.moveTo(ax + dx * t, ay + dy * t);
            path.lineTo(ax - dx * t * 0.5f + dy * t, ay - dy * t * 0.5f + dx * t);
            path.lineTo(ax - dx * t * 0.5f - dy * t, ay - dy * t * 0.5f - dx * t);
            path.close();
            canvas.drawPath(path, paint);
        }
        strokeEdge(alpha);
        path.reset();
        path.addRoundRect(new RectF(x - arm, y - r, x + arm, y + r), arm * 0.4f, arm * 0.4f, Path.Direction.CW);
        path.addRoundRect(new RectF(x - r, y - arm, x + r, y + arm), arm * 0.4f, arm * 0.4f, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    private void drawStick(Canvas canvas, Control c, int alpha) {
        float x = cx(c), y = cy(c), r = radius(c);
        fillBody(canvas, false, alpha);
        canvas.drawCircle(x, y, r, paint);
        strokeEdge(alpha);
        canvas.drawCircle(x, y, r, paint);
        float kx = x, ky = y;
        boolean active = false;
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            if (t.control == c) {
                kx += t.stickX;
                ky += t.stickY;
                active = true;
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(active ? 0xC0FFFFFF : 0x90FFFFFF);
        paint.setAlpha(Math.min(paint.getAlpha(), alpha));
        canvas.drawCircle(kx, ky, r * 0.45f, paint);
    }

    private void drawEditButton(Canvas canvas, Control c) {
        float x = cx(c), y = cy(c), r = radius(c);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40000000);
        canvas.drawCircle(x, y, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, r * 0.12f));
        paint.setColor(0x80FFFFFF);
        canvas.drawCircle(x, y, r, paint);
        float w = r * 0.5f;
        for (int i = -1; i <= 1; i++)
            canvas.drawLine(x - w, y + i * r * 0.32f, x + w, y + i * r * 0.32f, paint);
    }

    private void drawToolbar(Canvas canvas) {
        float h = unit();
        float bh = 0.085f * h, gap = 0.015f * h;
        paint.setTextSize(bh * 0.4f);
        paint.setFakeBoldText(true);
        float[] widths = new float[TOOLS.length];
        for (int i = 0; i < TOOLS.length; i++) widths[i] = paint.measureText(toolLabel(i)) + bh * 0.8f;
        /* Two rows, each centred: the layout tools, then the game actions
         * (from GAME_TOOLS_FROM on: save/load state, change disc, FPS, done). */
        float y = h * 0.42f;
        for (int row = 0; row < 2; row++) {
            int from = row == 0 ? 0 : GAME_TOOLS_FROM, to = row == 0 ? GAME_TOOLS_FROM : TOOLS.length;
            float total = 0;
            for (int i = from; i < to; i++) total += widths[i] + (i > from ? gap : 0);
            float x = (getWidth() - total) * 0.5f, ry = y + row * (bh + gap);
            for (int i = from; i < to; i++) {
                boolean needsSelection = i < 4;
                toolRects[i].set(x, ry, x + widths[i], ry + bh);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(needsSelection && selected == null ? 0x60303030 : 0xE0303030);
                canvas.drawRoundRect(toolRects[i], bh * 0.25f, bh * 0.25f, paint);
                paint.setColor(needsSelection && selected == null ? 0x80FFFFFF : 0xFFFFFFFF);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(toolLabel(i), toolRects[i].centerX(), ry + bh * 0.64f, paint);
                x += widths[i] + gap;
            }
        }
        paint.setTextSize(bh * 0.36f);
        paint.setColor(0xFFFFFFFF);
        canvas.drawText((selected == null ? "Tap a control to select it, drag to move it"
                        : "Selected: " + describe(selected))
                        + "   ·   opacity " + Math.round(OPACITY[opacityStep] * 100) + "%",
                getWidth() * 0.5f, y - gap * 1.5f, paint);
        paint.setFakeBoldText(false);
    }

    private static String describe(Control c) {
        switch (c.id) {
            case "dpad": return "D-pad";
            case "lstick": return "left stick";
            case "rstick": return "right stick";
            case "triangle": return "Triangle";
            case "circle": return "Circle";
            case "cross": return "Cross";
            case "square": return "Square";
            case "fps": return "FPS counter (on/off in the menu)";
            case "mouse_left": return "ACT (left mouse button)";
            case "mouse_right": return "MOVE (right mouse button)";
            default: return c.defaultLabel;
        }
    }

    /* ---- touch ------------------------------------------------------- */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return false;
        int action = event.getActionMasked();
        if (controllerHidden) {
            /* The touch that brings the pad back only shows it. */
            if (action == MotionEvent.ACTION_DOWN) {
                controllerHidden = false;
                applyAlpha();
            }
            return true;
        }
        if (mouseMode && !editing) return mouseTouch(event, action);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = event.getActionIndex();
                if (editing) editDown(event.getPointerId(i), event.getX(i), event.getY(i));
                else pointerDown(event.getPointerId(i), event.getX(i), event.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (editing) editMove(event.getPointerId(i), event.getX(i), event.getY(i));
                    else pointerMove(event.getPointerId(i), event.getX(i), event.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = event.getPointerId(event.getActionIndex());
                if (editing) {
                    if (id == dragPointer) dragPointer = -1;
                } else {
                    touches.remove(id);
                    update();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (editing) dragPointer = -1;
                else releaseAll();
                break;
            default:
                break;
        }
        return true;
    }

    /* ---- trackpad (mouse games) ---------------------------------------- */

    private boolean mouseTouch(MotionEvent event, int action) {
        int i = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = event.getPointerId(i);
                float x = event.getX(i), y = event.getY(i);
                Control c = controlAt(x, y, false);
                if (c != null && c.kind == KIND_EDIT) {
                    releaseAll();
                    menu.open();
                    return true;
                }
                if (c != null) {
                    pointerDown(id, x, y);   /* D-pad, ACT, MOVE: ordinary pad controls */
                } else if (padPointer < 0) {
                    padPointer = id;
                    padDownX = padLastX = x;
                    padDownY = padLastY = y;
                    padDownTime = padLastTime = event.getEventTime();
                    fracX = fracY = 0.0f;
                    padMoved = false;
                    padFingers = 1;
                } else {
                    padFingers++;            /* a second trackpad finger: two-finger tap */
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int k = 0; k < event.getPointerCount(); k++) {
                    int id = event.getPointerId(k);
                    if (touches.get(id) != null) pointerMove(id, event.getX(k), event.getY(k));
                }
                int p = event.findPointerIndex(padPointer);
                if (p < 0) break;
                float x = event.getX(p), y = event.getY(p);
                long t = event.getEventTime();
                if (!padMoved) {
                    /* A two-finger tap never moves the cursor; one finger starts
                     * moving it only past the touch slop, so taps click in place. */
                    if (padFingers > 1 || Math.hypot(x - padDownX, y - padDownY) <= touchSlop) break;
                    padMoved = true;
                } else {
                    float ddx = (x - padLastX) / density, ddy = (y - padLastY) / density;
                    float speed = (float) Math.hypot(ddx, ddy) / Math.max(1L, t - padLastTime);
                    float gain = MOUSE_SLOW + (MOUSE_FAST - MOUSE_SLOW) * Math.min(1.0f, speed / MOUSE_FAST_SPEED);
                    fracX += ddx * gain;
                    fracY += ddy * gain;
                    int dx = (int) fracX, dy = (int) fracY;
                    fracX -= dx;
                    fracY -= dy;
                    if (dx != 0 || dy != 0) PsxInput.nativeMouseMotion(dx, dy);
                }
                padLastX = x;
                padLastY = y;
                padLastTime = t;
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int id = event.getPointerId(i);
                if (touches.get(id) != null) {
                    touches.remove(id);
                    update();
                } else if (id == padPointer) {
                    /* Another trackpad finger still down takes the cursor over, without a jump. */
                    int next = -1;
                    for (int k = 0; k < event.getPointerCount(); k++)
                        if (k != i && touches.get(event.getPointerId(k)) == null) { next = k; break; }
                    if (next >= 0) {
                        padPointer = event.getPointerId(next);
                        padLastX = event.getX(next);
                        padLastY = event.getY(next);
                        padLastTime = event.getEventTime();
                    } else {
                        if (!padMoved && event.getEventTime() - padDownTime <= TAP_MAX_MS)
                            mouseClick(padFingers >= 2 ? 1 : 0);
                        padPointer = -1;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        return true;
    }

    /** A short press of a mouse button (0 = left, 1 = right). */
    private void mouseClick(final int button) {
        PsxInput.nativeMouseButton(button, true);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        postDelayed(() -> {
            /* Keep it down if a finger is holding that button's control. */
            if ((mouseHeld & (1 << button)) == 0) PsxInput.nativeMouseButton(button, false);
        }, CLICK_HOLD_MS);
    }

    private void toggleFps() {
        fpsOn = !fpsOn;
        prefs.edit().putBoolean(PREFS_FPS, fpsOn).apply();
        PsxInput.nativeSetFpsCounter(fpsOn);
        invalidate();
    }


    private void pointerDown(int id, float x, float y) {
        Control c = controlAt(x, y, false);
        if (c != null && c.kind == KIND_EDIT) {
            releaseAll();
            menu.open();
            return;
        }
        Touch t = new Touch();
        t.control = c;
        touches.put(id, t);
        track(t, x, y);
        update();
    }

    private void pointerMove(int id, float x, float y) {
        Touch t = touches.get(id);
        if (t == null) return;
        /* Buttons: sliding onto another button presses that one instead. The
         * D-pad and sticks keep the finger until it lifts. */
        if (t.control == null || t.control.kind == KIND_BUTTON || t.control.kind == KIND_SHOULDER) {
            Control c = controlAt(x, y, false);
            if (c != null && (c.kind == KIND_BUTTON || c.kind == KIND_SHOULDER)) t.control = c;
            else if (t.control != null && !hit(t.control, x, y)) t.control = null;
        }
        track(t, x, y);
        update();
    }

    private void track(Touch t, float x, float y) {
        Control c = t.control;
        if (c == null) return;
        float dx = x - cx(c), dy = y - cy(c), r = radius(c);
        if (c.kind == KIND_DPAD) {
            /* 8-way: an axis engages once the finger is more than 22.5 degrees
             * off its perpendicular, with a small dead zone at the centre. */
            float dist = (float) Math.hypot(dx, dy);
            int mask = 0;
            if (dist >= r * 0.18f) {
                float th = 0.383f * dist;
                if (dy < -th) mask |= PsxInput.UP;
                if (dy > th) mask |= PsxInput.DOWN;
                if (dx < -th) mask |= PsxInput.LEFT;
                if (dx > th) mask |= PsxInput.RIGHT;
            }
            t.dpadMask = mask;
        } else if (c.kind == KIND_STICK) {
            float len = (float) Math.hypot(dx, dy);
            if (len > r) {
                dx = dx / len * r;
                dy = dy / len * r;
                len = r;
            }
            t.stickX = dx;
            t.stickY = dy;
            int mask = 0;
            if (stickDpad && c.stick == 0 && len > r * 0.5f) {
                if (dx > len * 0.5f) mask |= PsxInput.RIGHT;
                if (dx < -len * 0.5f) mask |= PsxInput.LEFT;
                if (dy > len * 0.5f) mask |= PsxInput.DOWN;
                if (dy < -len * 0.5f) mask |= PsxInput.UP;
            }
            t.dpadMask = mask;
        }
    }

    private static int axisByte(float v, float r) {
        int b = 128 + Math.round(v / r * 127.0f);
        return Math.max(0, Math.min(255, b));
    }

    /** Recompute the held set and sticks from every finger; send changes. */
    private void update() {
        int bits = 0, mouse = 0;
        int s0 = 0x8080, s1 = 0x8080;
        for (int i = 0; i < touches.size(); i++) {
            Touch t = touches.valueAt(i);
            Control c = t.control;
            if (c == null) continue;
            bits |= c.bits | t.dpadMask;
            if (c.kind == KIND_MOUSE) mouse |= 1 << c.stick;
            if (c.kind == KIND_STICK) {
                float r = radius(c);
                int v = axisByte(t.stickX, r) | (axisByte(t.stickY, r) << 8);
                if (c.stick == 0) s0 = v;
                else s1 = v;
            }
        }
        if (bits != heldBits) {
            if ((bits & ~heldBits) != 0)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            heldBits = bits;
            PsxInput.nativeSetButtons(bits);
        }
        if (mouse != mouseHeld) {
            if ((mouse & ~mouseHeld) != 0)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            for (int b = 0; b < 2; b++)
                if (((mouse ^ mouseHeld) & (1 << b)) != 0)
                    PsxInput.nativeMouseButton(b, (mouse & (1 << b)) != 0);
            mouseHeld = mouse;
        }
        if (s0 != stickBytes0) {
            stickBytes0 = s0;
            PsxInput.nativeSetStick(0, s0 & 0xFF, s0 >> 8);
        }
        if (s1 != stickBytes1) {
            stickBytes1 = s1;
            PsxInput.nativeSetStick(1, s1 & 0xFF, s1 >> 8);
        }
        invalidate();
    }

    /** Let go of everything (focus lost, cancel, editor opened). */
    public void releaseAll() {
        touches.clear();
        padPointer = -1;
        update();
    }

    /* ---- layout editor ----------------------------------------------- */

    private void setEditing(boolean on) {
        if (on) releaseAll();
        editing = on;
        selected = null;
        dragPointer = -1;
        if (!on) saveLayout();
        applyAlpha();
        invalidate();
    }

    private void editDown(int id, float x, float y) {
        for (int i = 0; i < toolRects.length; i++) {
            if (toolRects[i].contains(x, y)) {
                tool(i);
                return;
            }
        }
        Control c = controlAt(x, y, true);
        if (c == null || c.kind == KIND_EDIT) {
            selected = null;
        } else {
            selected = c;
            dragPointer = id;
            dragDx = cx(c) - x;
            dragDy = cy(c) - y;
        }
        invalidate();
    }

    private void editMove(int id, float x, float y) {
        if (id != dragPointer || selected == null) return;
        float h = unit(), w = getWidth();
        float px = Math.max(0, Math.min(w, x + dragDx));
        float py = Math.max(0, Math.min(h, y + dragDy));
        /* Re-anchor to the nearest side so the layout survives other aspect
         * ratios: thumbs stay where they were relative to the edges. */
        if (px < w / 3) {
            selected.anchor = ANCHOR_LEFT;
            selected.x = px / h;
        } else if (px > w * 2 / 3) {
            selected.anchor = ANCHOR_RIGHT;
            selected.x = (w - px) / h;
        } else {
            selected.anchor = ANCHOR_CENTER;
            selected.x = (px - w * 0.5f) / h;
        }
        selected.y = py / h;
        invalidate();
    }

    private void tool(int index) {
        switch (TOOLS[index]) {
            case "Smaller":
                if (selected != null) selected.scale = Math.max(0.5f, selected.scale / 1.12f);
                break;
            case "Bigger":
                if (selected != null) selected.scale = Math.min(2.5f, selected.scale * 1.12f);
                break;
            case "Rename":
                if (selected != null) rename(selected);
                break;
            case "Hide/Show":
                if (selected != null && selected.kind == KIND_FPS) toggleFps();
                else if (selected != null) selected.visible = !selected.visible;
                break;
            case "Opacity":
                opacityStep = (opacityStep + 1) % OPACITY.length;
                applyAlpha();
                break;
            case "Reset all":
                for (Control c : controls) c.reset();
                opacityStep = 0;
                applyAlpha();
                selected = null;
                break;
            case "Done":
                setEditing(false);
                return;
            default:
                break;
        }
        invalidate();
    }

    /**
     * Save states: the runtime's slots (per disc on multi-disc games). The list
     * shows when each slot was saved. Saving over a used slot and loading
     * (which replaces the current progress) both ask first. The game's own
     * memory-card saves are separate and unaffected.
     */
    void stateSlots(final boolean load) {
        int slots = PsxInput.nativeStateSlots();
        final String[] items = new String[slots];
        final boolean[] used = new boolean[slots];
        java.text.DateFormat fmt = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (int i = 0; i < slots; i++) {
            long t = PsxInput.nativeStateSlotTime(i);
            used[i] = t != 0;
            items[i] = "Slot " + (i + 1) + "   " + (t > 1 ? fmt.format(new java.util.Date(t * 1000L))
                    : used[i] ? "saved" : "empty");
        }
        new AlertDialog.Builder(activity)
                .setTitle(load ? "Load state from" : "Save state to")
                .setItems(items, (d, which) -> {
                    if (load && !used[which]) return;
                    if (!load && !used[which]) { PsxInput.nativeRequestState(which, false); return; }
                    new AlertDialog.Builder(activity)
                            .setTitle(load ? "Load slot " + (which + 1) + "?" : "Overwrite slot " + (which + 1) + "?")
                            .setMessage(load ? "Progress since your last save state or in-game save is lost."
                                    : "The state saved in this slot will be replaced.")
                            .setPositiveButton(load ? "Load" : "Save",
                                    (d2, w2) -> PsxInput.nativeRequestState(which, load))
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Multi-disc games: put another disc in the drive, like opening the PS1's
     * lid and swapping discs. The runtime does it between frames
     * (cdrom_swap_disc); the game sees the tray open and close and reads the
     * new disc itself, so swap when the game asks for it.
     */
    void changeDisc() {
        int count = PsxInput.nativeDiscCount();
        int current = PsxInput.nativeCurrentDisc();
        if (count <= 1) {
            new AlertDialog.Builder(activity)
                    .setTitle("Change disc")
                    .setMessage("This game has one disc loaded. For a game with several discs, go back to the "
                            + "start menu, tap Select game file, and select every disc's .cue and .bin files together.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] items = new String[count];
        for (int i = 0; i < count; i++)
            items[i] = "Disc " + (i + 1) + (i + 1 == current ? "  (in the drive)" : "");
        new AlertDialog.Builder(activity)
                .setTitle("Insert which disc?")
                .setItems(items, (d, which) -> {
                    if (which + 1 != current) PsxInput.nativeRequestDiscSwap(which + 1);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rename(final Control c) {
        if (c.kind == KIND_DPAD || c.kind == KIND_STICK) return;
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        input.setText(c.label.equals(c.defaultLabel) ? "" : c.label);
        input.setHint(describe(c));
        new AlertDialog.Builder(activity)
                .setTitle("Label for " + describe(c))
                .setMessage("Leave empty for the standard label.")
                .setView(input)
                .setPositiveButton("OK", (d, w) -> {
                    String text = input.getText().toString().trim();
                    c.label = text.isEmpty() ? c.defaultLabel : text;
                    invalidate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveLayout() {
        JSONObject all = new JSONObject();
        try {
            for (Control c : controls) {
                JSONObject o = new JSONObject();
                o.put("a", c.anchor);
                o.put("x", c.x);
                o.put("y", c.y);
                o.put("s", c.scale);
                o.put("v", c.visible);
                if (!c.label.equals(c.defaultLabel)) o.put("l", c.label);
                all.put(c.id, o);
            }
            all.put("_opacity", opacityStep);
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(PREFS_KEY, all.toString()).apply();
    }

    private void loadLayout() {
        String text = prefs.getString(PREFS_KEY, null);
        if (text == null) return;
        try {
            JSONObject all = new JSONObject(text);
            opacityStep = Math.max(0, Math.min(OPACITY.length - 1, all.optInt("_opacity", 0)));
            for (Control c : controls) {
                JSONObject o = all.optJSONObject(c.id);
                if (o == null) continue;
                c.anchor = o.optInt("a", c.defAnchor);
                c.x = (float) o.optDouble("x", c.defX);
                c.y = (float) o.optDouble("y", c.defY);
                c.scale = (float) o.optDouble("s", 1.0);
                c.visible = o.optBoolean("v", c.defVisible);
                c.label = o.optString("l", c.defaultLabel);
            }
        } catch (JSONException e) {
            for (Control c : controls) c.reset();
        }
        /* The editor button can never be hidden, or the layout could not be
         * edited again. */
        for (Control c : controls) if (c.kind == KIND_EDIT || c.kind == KIND_FPS) c.visible = true;
        applyAlpha();
    }

    private void applyAlpha() {
        /* The editor itself always draws fully opaque so it stays usable. */
        setAlpha(controllerHidden ? 0.0f : editing ? 1.0f : OPACITY[opacityStep]);
    }

    /**
     * A physical controller was used: get out of the way (and let go of any
     * touch input). The next touch on the screen shows the pad again.
     */
    public void hideForController() {
        if (controllerHidden || editing) return;
        releaseAll();
        controllerHidden = true;
        applyAlpha();
    }

    /** Back closes the editor first. @return true if it was open. */
    public boolean closeEditor() {
        if (menu.isOpen()) {
            menu.close();
            return true;
        }
        if (!editing) return false;
        setEditing(false);
        return true;
    }
}
