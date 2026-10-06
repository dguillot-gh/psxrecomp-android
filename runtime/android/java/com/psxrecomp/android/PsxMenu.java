package com.psxrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pad's menu (the button at the top centre): a scrolling side panel over the
 * right part of the screen, the game still visible beside it. Tapping outside it
 * or Back closes it.
 *
 * Game: save/load state, change disc, restart. Display: FPS counter and the
 * runtime's picture options from game.toml [video] (internal resolution, aspect
 * ratio 4:3/16:9, sharp or anti-aliased scaling, texture smoothing, perspective-correct textures). Those
 * are read once at start, so they are written into files/game.toml and applied by
 * "Restart game", which first saves a state into the last slot and loads it back
 * after the restart (files/autoload_slot), so nothing is lost. Controls: the pad
 * layout editor and its opacity.
 */
final class PsxMenu {
    /* Written by the game process before it restarts itself; the start menu (other
     * process) sees restart_pending and starts the game again, which loads the slot. */
    static final String RESTART_FLAG = "restart_pending";
    static final String AUTOLOAD_FILE = "autoload_slot";

    private final Activity activity;
    private final PadOverlay pad;
    private final float dp;
    private FrameLayout root;
    private ScrollView scroll;
    private boolean videoChanged;

    PsxMenu(Activity activity, PadOverlay pad) {
        this.activity = activity;
        this.pad = pad;
        this.dp = activity.getResources().getDisplayMetrics().density;
    }

    boolean isOpen() { return root != null; }

    void close() {
        if (root == null) return;
        ((ViewGroup) root.getParent()).removeView(root);
        root = null;
    }

    void open() {
        if (root != null) return;
        ViewGroup host = (ViewGroup) pad.getParent();
        if (host == null) return;
        root = new FrameLayout(activity);
        root.setBackgroundColor(0x55000000);
        root.setOnClickListener(v -> close());   /* tap outside the panel */

        scroll = new ScrollView(activity);
        scroll.setBackgroundColor(0xF0181820);
        scroll.setOnClickListener(v -> { });     /* taps inside don't close */
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int p = px(16);
        list.setPadding(p, px(10), p, px(24));
        scroll.addView(list);
        build(list);

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int w = Math.max(px(320), (int) (screenW * 0.40f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(w, screenW),
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        root.addView(scroll, lp);
        host.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /* ---- contents -------------------------------------------------------- */

    private void build(LinearLayout list) {
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Menu", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView x = text("✕", 24, Color.WHITE);
        x.setPadding(px(12), px(4), px(4), px(4));
        x.setOnClickListener(v -> close());
        head.addView(x);
        list.addView(head);

        section(list, "Game");
        row(list, "Save state…", () -> { close(); pad.stateSlots(false); });
        row(list, "Load state…", () -> { close(); pad.stateSlots(true); });
        if (PsxInput.nativeDiscCount() > 1)
            row(list, "Change disc…", () -> { close(); pad.changeDisc(); });
        row(list, "Restart game", this::confirmRestart);

        section(list, "Display");
        toggle(list, "FPS counter", "Movable: drag it in Edit pad layout", pad.isFpsOn(),
                on -> pad.setFpsOn(on));

        Map<String, String> video = readVideo();
        int scale = parseInt(video.get("supersampling"), 1);
        boolean smooth = !"false".equals(video.get("antialiasing"));
        list.addView(label("Internal resolution", "How big the game's picture is drawn before it reaches the screen. 1× = original PS1."));
        LinearLayout scales = new LinearLayout(activity);
        for (int s = 1; s <= 4; s++) {
            final int value = s;
            TextView b = chip(s + "×", s == scale);
            b.setOnClickListener(v -> setVideo("supersampling", String.valueOf(value)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(44), 1);
            lp.setMargins(s == 1 ? 0 : px(6), px(6), 0, px(4));
            scales.addView(b, lp);
        }
        list.addView(scales);
        /* Aspect ratio: hidden. On PSX this framework only engages widescreen from a
         * per-game widescreen mod package (main.cpp: "widescreen is mod-owned"), and
         * none of our games ships one yet, so a plain [video] aspect_ratio is ignored. */
        if (false) {
            boolean wide = "\"16:9\"".equals(video.get("aspect_ratio"));
            list.addView(label("Aspect ratio", "16:9 widens the 3D camera (you see more, nothing is stretched). "
                    + "2D backgrounds and some edges may not fill it; switch back if a game looks wrong."));
            LinearLayout aspects = new LinearLayout(activity);
            String[][] choices = { { "4:3", "\"4:3\"" }, { "16:9", "\"16:9\"" } };
            for (int i = 0; i < choices.length; i++) {
                final String value = choices[i][1];
                TextView b = chip(choices[i][0], (i == 1) == wide);
                b.setOnClickListener(v -> setVideo("aspect_ratio", value));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(44), 1);
                lp.setMargins(i == 0 ? 0 : px(6), px(6), 0, px(4));
                aspects.addView(b, lp);
            }
            list.addView(aspects);
        }
        toggle(list, "Anti-aliased (smooth edges)",
                "On: draw big, shrink back (soft). Off: keep the full size (sharp, like DuckStation's upscale).",
                smooth, on -> setVideo("antialiasing", on ? "true" : "false"));
        toggle(list, "Smooth textures", "Bilinear filtering instead of blocky pixels.",
                "\"bilinear\"".equals(video.get("texture_filtering")),
                on -> setVideo("texture_filtering", on ? "\"bilinear\"" : "\"nearest\""));
        toggle(list, "Perspective-correct textures", "Fixes textures that warp on floors and walls.",
                "true".equals(video.get("perspective_texturing")),
                on -> setVideo("perspective_texturing", on ? "true" : "false"));
        TextView note = text(videoChanged ? "Changed: tap Restart game to apply (your place is kept)."
                : "Display changes apply when the game restarts.", 13, videoChanged ? 0xFFFFD54F : 0xFF9090A0);
        note.setPadding(0, px(8), 0, 0);
        list.addView(note);
        if (videoChanged) row(list, "Restart game now", this::confirmRestart);

        section(list, "Controls");
        row(list, "Edit pad layout…", () -> { close(); pad.startEditing(); });
        row(list, "Pad opacity: " + pad.opacityPercent() + "%", () -> { pad.cycleOpacity(); refresh(); });
    }

    /* Rebuild the panel (new values, the restart note), keeping the scroll position. */
    private void refresh() {
        final int y = scroll != null ? scroll.getScrollY() : 0;
        close();
        open();
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, y));
    }

    /* ---- restart --------------------------------------------------------- */

    private void confirmRestart() {
        new AlertDialog.Builder(activity)
                .setTitle("Restart game")
                .setMessage("Your current place is saved to the last save-state slot first and "
                        + "loaded again right after the restart.")
                .setPositiveButton("Restart", (d, w) -> saveThenRestart())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveThenRestart() {
        close();
        final int slot = PsxInput.nativeStateSlots() - 1;
        final long before = PsxInput.nativeStateSlotTime(slot);
        PsxInput.nativeRequestState(slot, false);
        final Handler h = new Handler(Looper.getMainLooper());
        final long start = System.currentTimeMillis();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                long now = PsxInput.nativeStateSlotTime(slot);
                if (now > 0 && now != before) {
                    /* Saved. One more second lets a memory-card write settle (0.5 s). */
                    h.postDelayed(() -> restart(slot), 1000);
                } else if (System.currentTimeMillis() - start < 6000) {
                    h.postDelayed(this, 200);
                } else {
                    new AlertDialog.Builder(activity)
                            .setTitle("Could not save a state")
                            .setMessage("Restart anyway? Progress since your last save is lost.")
                            .setPositiveButton("Restart", (d, w) -> restart(-1))
                            .setNegativeButton("Cancel", null)
                            .show();
                }
            }
        }, 300);
    }

    private void restart(int slot) {
        File files = activity.getFilesDir();
        try {
            if (slot >= 0) write(new File(files, AUTOLOAD_FILE), String.valueOf(slot));
            write(new File(files, RESTART_FLAG), "1");
        } catch (IOException e) {
            return;
        }
        activity.finish();
        /* SDL and the runtime only start once per process: end this one (":game");
         * the start menu, in the main process, starts the game again. */
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Called when the game starts: load the state Restart saved, once. */
    static void autoloadAfterRestart(Activity activity, View anyView) {
        File f = new File(activity.getFilesDir(), AUTOLOAD_FILE);
        if (!f.isFile()) return;
        int slot;
        try {
            slot = Integer.parseInt(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            slot = -1;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (slot < 0) return;
        final int s = slot;
        /* Give the runtime time to boot; the request waits for the next frame anyway. */
        anyView.postDelayed(() -> PsxInput.nativeRequestState(s, true), 3000);
    }

    /* ---- files/game.toml [video] ------------------------------------------ */

    private File gameToml() { return new File(activity.getFilesDir(), "game.toml"); }

    /** The [video] section's keys and raw values, in order. */
    private Map<String, String> readVideo() {
        Map<String, String> kv = new LinkedHashMap<>();
        String sec = "";
        for (String line : readLines()) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) { sec = t; continue; }
            int eq = t.indexOf('=');
            if ("[video]".equals(sec) && eq > 0 && !t.startsWith("#"))
                kv.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return kv;
    }

    /** Set one [video] key (raw TOML value), keeping every other line as it was. */
    private void setVideo(String key, String value) {
        Map<String, String> kv = readVideo();
        if (value.equals(kv.get(key))) return;
        kv.put(key, value);
        StringBuilder out = new StringBuilder();
        boolean inVideo = false;
        for (String line : readLines()) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")) inVideo = "[video]".equals(t);
            if (!inVideo) out.append(line).append('\n');
        }
        while (out.length() > 1 && out.charAt(out.length() - 1) == '\n' && out.charAt(out.length() - 2) == '\n')
            out.setLength(out.length() - 1);
        out.append("\n[video]\n");
        for (Map.Entry<String, String> e : kv.entrySet())
            out.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        try {
            /* Temp file + rename: a crash mid-write never leaves a broken game.toml. */
            File tmp = new File(gameToml().getPath() + ".tmp");
            write(tmp, out.toString());
            if (!tmp.renameTo(gameToml())) throw new IOException("rename failed");
            videoChanged = true;
            if (root != null) root.post(this::refresh);
        } catch (IOException e) {
            new AlertDialog.Builder(activity).setMessage("Could not save the setting: " + e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private String[] readLines() {
        try {
            return new String(Files.readAllBytes(gameToml().toPath()), StandardCharsets.UTF_8).split("\r?\n", -1);
        } catch (IOException e) {
            return new String[0];
        }
    }

    private static void write(File f, String text) throws IOException {
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /* ---- widgets --------------------------------------------------------- */

    interface OnToggle { void set(boolean on); }

    private int px(float v) { return (int) (v * dp + 0.5f); }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    private void section(LinearLayout list, String name) {
        TextView t = text(name.toUpperCase(), 13, 0xFF8AB4FF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(18), 0, px(4));
        list.addView(t);
    }

    private void row(LinearLayout list, String name, Runnable action) {
        TextView t = text(name, 17, Color.WHITE);
        t.setPadding(px(12), 0, px(12), 0);
        t.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2A34);
        bg.setCornerRadius(px(10));
        t.setBackground(bg);
        t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        lp.topMargin = px(6);
        list.addView(t, lp);
    }

    private LinearLayout label(String name, String hint) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(10), 0, 0);
        box.addView(text(name, 17, Color.WHITE));
        if (hint != null) box.addView(text(hint, 12, 0xFF9090A0));
        return box;
    }

    private void toggle(LinearLayout list, String name, String hint, boolean on, OnToggle onToggle) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(name, hint), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(activity);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener((b, checked) -> onToggle.set(checked));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        list.addView(row);
    }

    private TextView chip(String s, boolean selected) {
        TextView t = text(s, 17, selected ? Color.BLACK : Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF8AB4FF : 0xFF2A2A34);
        bg.setCornerRadius(px(10));
        t.setBackground(bg);
        return t;
    }
}
