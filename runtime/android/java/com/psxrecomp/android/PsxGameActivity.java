package com.psxrecomp.android;

import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;

/**
 * The game screen shared by every psxrecomp Android app: SDL runs the native
 * runtime (libmain.so) and the on-screen PS1 pad sits on top.
 *
 * The app's LauncherActivity starts this once a disc has been imported and
 * files/game.toml exists. It runs in its own process (":game" in the
 * manifest) because SDL's native side cannot start twice in one process;
 * quitting ends that process and returns to the menu.
 *
 * Per-game values come from the app's string/bool resources:
 * psx_game_id (overlay cache folder), psx_game_title, psx_analog_sticks.
 */
public class PsxGameActivity extends SDLActivity {
    private PadOverlay pad;

    @Override
    protected String[] getLibraries() {
        // SDL3 is linked statically into libmain.so by the Android CMake build.
        // SDLActivity's default list would try to load a separate libSDL3.so,
        // which is intentionally not packaged and prevents the Activity starting.
        return new String[] { "main" };
    }

    @Override
    protected String[] getArguments() {
        // Pass the imported app-private config path explicitly. The renderer is
        // always given on the command line, so a saved desktop setting can never
        // pick it: software unless the menu's Renderer choice wrote
        // [video] renderer = "opengl" into files/game.toml (OpenGL ES, experimental).
        File toml = new File(getFilesDir(), "game.toml");
        File bios = wantedBios();
        if (bios != null) {
            return new String[] {
                    "--game", toml.getAbsolutePath(),
                    "--renderer", wantsGpu(toml) ? "opengl" : "software",
                    "--bios", bios.getAbsolutePath()
            };
        }
        return new String[] {
                "--game", toml.getAbsolutePath(),
                "--renderer", wantsGpu(toml) ? "opengl" : "software"
        };
    }

    /* The runtime does not read game.toml's [bios] path, so a game built with
     * another BIOS (tools\build.ps1: android-bios.txt, e.g. Mizzurna Falls'
     * fan translation, which needs Sony's) gets it on the command line. Read
     * from the APK's own assets/game.toml.in so it follows the installed build,
     * not a files/game.toml written by an older one. null = the default OpenBIOS. */
    private File wantedBios() {
        try {
            String s = PsxFiles.readAsset(this, "game.toml.in");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^\\s*path\\s*=\\s*\"(bios/[^\"]+)\"").matcher(s);
            if (!m.find() || m.group(1).equals("bios/openbios.bin")) return null;
            File f = new File(getFilesDir(), m.group(1));
            return f.isFile() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean wantsGpu(File toml) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(toml.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            return java.util.regex.Pattern.compile("(?m)^\\s*renderer\\s*=\\s*\"opengl\"").matcher(s).find();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // Opt-in runtime perf report to logcat (tag psxrecomp): create
        // files/perf_diag over adb to enable it, delete it to disable. Set
        // before the native game thread starts so getenv() sees it.
        if (new File(getFilesDir(), "perf_diag").isFile()) {
            try {
                Os.setenv("PSX_RUNTIME_PERF_DIAG", "1", true);
                Os.setenv("PSX_RUNTIME_PERF_DIAG_MS", "2000", true);
            } catch (ErrnoException e) {
                // Diagnostics only; run without them.
            }
        }
        // Opt-in runtime environment for diagnosis: files/runtime_env holds
        // KEY=VALUE lines (e.g. PSX_OVERLAY_DIFF=1), pushed over adb run-as.
        // Absent in normal installs; set before the native thread starts.
        File runtimeEnv = new File(getFilesDir(), "runtime_env");
        if (runtimeEnv.isFile()) {
            try {
                for (String line : PsxFiles.readText(runtimeEnv).split("\\r?\\n")) {
                    line = line.trim();
                    int eq = line.indexOf('=');
                    if (line.isEmpty() || line.startsWith("#") || eq <= 0) continue;
                    Os.setenv(line.substring(0, eq).trim(), line.substring(eq + 1).trim(), true);
                }
            } catch (IOException | ErrnoException e) {
                // Diagnostics only; run without them.
            }
        }
        try {
            PsxFiles.copyBundledRuntimeFiles(this);
            PsxFiles.installBundledOverlays(this, resString("psx_game_id"));
        } catch (IOException e) {
            Toast.makeText(this, "Could not prepare game runtime files: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        super.onCreate(state);
        if (mLayout == null) return;  // SDL refused to start (finishing)
        if (!new File(getFilesDir(), "game.toml").isFile()) {
            // Reached without the menu having imported a disc.
            finish();
            return;
        }
        pad = new PadOverlay(this, resBool("psx_analog_sticks"));
        mLayout.addView(pad, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
        else if (pad != null) pad.releaseAll();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    /* A physical controller (or keyboard) drives the game through SDL; the
     * touch pad steps aside until the screen is touched again. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (pad != null && event.getKeyCode() != KeyEvent.KEYCODE_BACK && fromController(event))
            pad.hideForController();
        return super.dispatchKeyEvent(event);
    }

    /* Gamepads and real keyboards; not the phone's own volume/power keys,
     * which also report as a keyboard source. */
    private static boolean fromController(KeyEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK))
            return true;
        InputDevice device = event.getDevice();
        return device != null && !device.isVirtual() &&
                device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (pad != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK))
            pad.hideForController();
        return super.dispatchGenericMotionEvent(event);
    }

    /** Back asks before quitting: a stray swipe must not throw away a run. */
    @Override
    public void onBackPressed() {
        if (pad != null && pad.closeEditor()) return;
        if (pad != null) pad.releaseAll();
        new AlertDialog.Builder(this)
                .setTitle("Quit " + resString("psx_game_title") + "?")
                .setMessage("Progress since your last save is lost. Memory card saves are kept.")
                .setPositiveButton("Quit", (d, w) -> finish())
                .setNegativeButton("Keep playing", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // SDL's native side cannot be started again in this process; end it so
        // the next Play from the menu gets a fresh one.
        if (isFinishing()) System.exit(0);
    }

    private String resString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id != 0 ? getString(id) : "";
    }

    private boolean resBool(String name) {
        int id = getResources().getIdentifier(name, "bool", getPackageName());
        return id != 0 && getResources().getBoolean(id);
    }
}
