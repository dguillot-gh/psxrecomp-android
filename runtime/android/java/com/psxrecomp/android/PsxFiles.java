package com.psxrecomp.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** File helpers shared by the menu and the game screen. */
final class PsxFiles {
    private PsxFiles() {}

    static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readAll(input);
        }
    }

    static String readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            return readAll(input);
        }
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    static void writeText(File file, String text) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void copyAsset(Context context, String asset, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Unable to create " + parent.getName());
        try (InputStream input = context.getAssets().open(asset);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        }
    }

    /** Deletes a folder tree. Only ever pointed at imported disc data. */
    static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        if (!file.delete()) throw new IOException("Unable to replace " + file.getName());
    }

    static void copyBundledRuntimeFiles(Context context) throws IOException {
        File files = context.getFilesDir();
        copyAsset(context, "bios/openbios.bin", new File(files, "bios/openbios.bin"));
        copyAsset(context, "bios/OpenBIOS.LICENSE", new File(files, "bios/OpenBIOS.LICENSE"));
        /* A game that needs another BIOS (tools\build.ps1: android-bios.txt) has
         * it bundled under assets/bios too; PsxGameActivity passes it with --bios. */
        String[] extra = context.getAssets().list("bios");
        if (extra != null) {
            for (String name : extra) {
                if (name.equals("openbios.bin") || name.equals("OpenBIOS.LICENSE")) continue;
                copyAsset(context, "bios/" + name, new File(files, "bios/" + name));
            }
        }
    }

    /**
     * Install the APK's ahead-of-time compiled overlay shards (assets/overlays/
     * cg*_gc*_f* /) into the runtime's overlay cache. Shard names are content
     * addressed, so an existing file is already the right one and is kept;
     * a missing one is written to a temp name and renamed into place. Every
     * .so lands before any .ranges because the loader only adopts complete
     * pairs. Skipped entirely once this APK version has been installed.
     */
    static void installBundledOverlays(Context context, String gameId) throws IOException {
        String[] dirs = context.getAssets().list("overlays");
        if (dirs == null || dirs.length == 0 || gameId.isEmpty()) return;
        File root = new File(context.getFilesDir(), "cache/" + gameId + "/gcc/linux-arm64");
        File stamp = new File(root, ".aot_overlays_installed");
        String version;
        try {
            version = Long.toString(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            version = "unknown";
        }
        if (stamp.isFile() && version.equals(readText(stamp))) return;
        int installed = 0;
        for (String suffix : new String[] { ".so", ".ranges" }) {
            for (String dir : dirs) {
                String[] names = context.getAssets().list("overlays/" + dir);
                if (names == null) continue;
                File outDir = new File(root, dir);
                for (String name : names) {
                    if (!name.endsWith(suffix)) continue;
                    File dest = new File(outDir, name);
                    if (dest.isFile()) continue;
                    File tmp = new File(outDir, "." + name + ".aot-tmp");
                    copyAsset(context, "overlays/" + dir + "/" + name, tmp);
                    if (!tmp.renameTo(dest)) throw new IOException("Unable to install " + name);
                    installed++;
                }
            }
        }
        if (!root.isDirectory() && !root.mkdirs()) return;
        writeText(stamp, version);
        Log.i("psxrecomp", "installed " + installed + " bundled AOT overlay files");
    }
}
