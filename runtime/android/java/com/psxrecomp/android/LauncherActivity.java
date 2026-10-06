package com.psxrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bare-bones start menu shared by every psxrecomp Android app: the game's
 * name, which disc file is loaded, Play, and Select game file.
 *
 * Select game file copies the user's own disc image into app storage
 * (files/gamedata) and writes files/game.toml from the APK's
 * assets/game.toml.in, filling its disc list (@@DISC1@@...). Accepted picks: a
 * .cue together with its track files, several .cues with their track files (a
 * multi-disc game, numbered by name), or a single .bin/.img/.iso/.chd. Memory
 * cards live elsewhere in files/ and are never touched here.
 */
public class LauncherActivity extends Activity {
    private static final int REQUEST_FILES = 81;
    private static final int REQUEST_FOLDER = 82;
    private static final String PREFS = "psx_launcher";
    private static final Pattern CUE_FILE =
            Pattern.compile("^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))", Pattern.CASE_INSENSITIVE);

    private TextView status;
    private Button play;
    private ProgressDialog progress;
    private boolean importing;

    /** One file the user picked. */
    private static final class Pick {
        final Uri uri;
        final String name;
        final long size;

        Pick(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float dp = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF101014);
        int pad = (int) (24 * dp);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(resString("psx_game_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, wrap());

        status = new TextView(this);
        status.setTextColor(0xFFB0B0B8);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int) (8 * dp), 0, (int) (20 * dp));
        root.addView(status, wrap());

        play = new Button(this);
        play.setText("Play");
        play.setOnClickListener(v -> startGame());
        root.addView(play, button(dp));

        Button select = new Button(this);
        select.setText("Select game file");
        select.setOnClickListener(v -> pickFiles());
        root.addView(select, button(dp));

        TextView hint = new TextView(this);
        hint.setText("Pick the .cue and its .bin together (long-press to select both), "
                + "or a single .bin, .iso or .chd. Several discs: select every disc's .cue and .bin "
                + "together; change discs in the game with the pad's menu button.");
        hint.setTextColor(0xFF707078);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, (int) (16 * dp), 0, 0);
        root.addView(hint, wrap());

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams button(float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (260 * dp), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * dp);
        return lp;
    }

    private boolean hasGame() {
        return new File(getFilesDir(), "game.toml").isFile() &&
                new File(getFilesDir(), "gamedata").isDirectory();
    }

    private void refresh() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (hasGame()) {
            status.setText("Game file: " + prefs.getString("disc_name", "imported"));
            play.setEnabled(true);
        } else {
            status.setText("No game file selected yet");
            play.setEnabled(false);
        }
    }

    private void startGame() {
        if (!hasGame() || importing) return;
        startActivity(new Intent(this, PsxGameActivity.class));
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_FILES);
    }

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        List<Pick> picks = new ArrayList<>();
        try {
            if (requestCode == REQUEST_FILES) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++)
                        picks.add(describe(data.getClipData().getItemAt(i).getUri()));
                } else if (data.getData() != null) {
                    picks.add(describe(data.getData()));
                }
            } else if (requestCode == REQUEST_FOLDER && data.getData() != null) {
                listFolder(data.getData(), picks);
            } else {
                return;
            }
        } catch (IOException e) {
            showError(e.getMessage());
            return;
        }
        if (!picks.isEmpty()) importPicks(picks);
    }

    private Pick describe(Uri uri) throws IOException {
        try (Cursor c = getContentResolver().query(uri, new String[] {
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IOException("Cannot read the selected file");
            String name = c.getString(0);
            long size = c.isNull(1) ? -1 : c.getLong(1);
            return new Pick(uri, safeName(name), size);
        }
    }

    /** The files directly inside a picked folder. */
    private void listFolder(Uri tree, List<Pick> picks) throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] columns = { DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE };
        try (Cursor c = getContentResolver().query(children, columns, null, null, null)) {
            if (c == null) throw new IOException("Storage provider returned no files");
            while (c.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) continue;
                String name = c.getString(1);
                if (kind(name) == null) continue;
                picks.add(new Pick(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        safeName(name), c.isNull(3) ? -1 : c.getLong(3)));
            }
        }
    }

    private static String safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") ||
                name.equals(".") || name.equals(".."))
            throw new IOException("Storage provider returned an invalid file name");
        return name;
    }

    /** "cue", "image", or null for a file the game does not use. */
    private static String kind(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".cue")) return "cue";
        for (String ext : new String[] { ".bin", ".img", ".iso", ".chd" })
            if (lower.endsWith(ext)) return "image";
        return null;
    }

    private void importPicks(List<Pick> picks) {
        importing = true;
        long total = 0;
        for (Pick p : picks) if (kind(p.name) != null && p.size > 0) total += p.size;
        progress = new ProgressDialog(this);
        progress.setMessage("Copying the game into app storage…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();
        final long totalBytes = total;
        new Thread(() -> {
            String error = null;
            String discName = null;
            try {
                discName = importInto(picks, totalBytes);
            } catch (MissingTracksException e) {
                error = "missing:" + e.getMessage();
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String failure = error;
            final String name = discName;
            runOnUiThread(() -> {
                importing = false;
                if (progress != null) progress.dismiss();
                if (failure == null) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("disc_name", name).apply();
                    refresh();
                } else if (failure.startsWith("missing:")) {
                    new AlertDialog.Builder(this)
                            .setTitle("Track file not selected")
                            .setMessage("The .cue needs " + failure.substring(8) + ". Select the "
                                    + ".cue and .bin together (long-press the first, then tap "
                                    + "the other), or choose the folder that holds them.")
                            .setPositiveButton("Choose folder", (d, w) -> pickFolder())
                            .setNegativeButton("Pick files again", (d, w) -> pickFiles())
                            .show();
                } else {
                    showError(failure);
                }
            });
        }, "psx-disc-import").start();
    }

    private static final class MissingTracksException extends IOException {
        MissingTracksException(String names) { super(names); }
    }

    /**
     * Copy, check and adopt the picks. A multi-disc game is picked in one go:
     * every disc's .cue with its track files. The discs are numbered by file
     * name in natural order ("Disc 2" before "Disc 10"), which is how disc
     * images are named, and become the game's disc list (Disc 1 boots; the
     * pad menu's Change disc swaps). @return the display name of the disc(s).
     */
    private String importInto(List<Pick> picks, long totalBytes) throws IOException {
        List<Pick> cues = new ArrayList<>();
        List<Pick> images = new ArrayList<>();
        for (Pick p : picks) {
            String k = kind(p.name);
            if ("cue".equals(k)) cues.add(p);
            else if ("image".equals(k)) images.add(p);
        }
        if (cues.isEmpty() && images.isEmpty())
            throw new IOException("None of the selected files is a disc image "
                    + "(.cue, .bin, .img, .iso or .chd).");
        if (cues.isEmpty() && images.size() > 1)
            throw new IOException("Several disc files were selected without a .cue. "
                    + "Select the .cue together with them (one .cue per disc).");
        Collections.sort(cues, (a, b) -> naturalCompare(a.name, b.name));

        File files = getFilesDir();
        File staging = new File(files, "gamedata.import");
        PsxFiles.deleteRecursively(staging);
        if (!staging.mkdirs()) throw new IOException("Unable to create the import folder");
        try {
            long[] done = { 0 };
            for (Pick p : cues) copy(p, staging, done, totalBytes);
            for (Pick p : images) copy(p, staging, done, totalBytes);

            List<String> discs = new ArrayList<>();
            for (Pick c : cues) {
                checkCueTracks(new File(staging, c.name), staging);
                discs.add(c.name);
            }
            if (discs.isEmpty()) discs.add(images.get(0).name);

            File gamedata = new File(files, "gamedata");
            PsxFiles.deleteRecursively(gamedata);
            if (!staging.renameTo(gamedata)) throw new IOException("Unable to store the game files");
            PsxFiles.writeText(new File(files, "game.toml"),
                    fillDiscs(PsxFiles.readAsset(this, "game.toml.in"), discs));
            return discs.size() == 1 ? discs.get(0)
                    : discs.size() + " discs (" + discs.get(0) + " ...)";
        } finally {
            PsxFiles.deleteRecursively(staging);
        }
    }

    /**
     * game.toml.in lists the discs as placeholder lines "  @@DISC1@@," ...
     * The @@DISC1@@ line becomes one line per imported disc (same indent), and
     * the other placeholder lines go, so the list is exactly what was imported:
     * valid TOML whether the player picked fewer or more discs than the
     * desktop build listed.
     */
    static String fillDiscs(String template, List<String> discs) {
        Matcher m = Pattern.compile("(?m)^([ \\t]*)@@DISC1@@,?[ \\t]*(\\R)").matcher(template);
        if (!m.find()) return template;
        StringBuilder lines = new StringBuilder();
        for (String d : discs)
            lines.append(m.group(1)).append(quoteToml("gamedata/" + d)).append(",").append(m.group(2));
        String filled = template.substring(0, m.start()) + lines + template.substring(m.end());
        return filled.replaceAll("(?m)^[ \\t]*@@DISC\\d+@@,?[ \\t]*\\R", "");
    }

    /** Name order with digit runs compared as numbers: "Disc 2" < "Disc 10". */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private void copy(Pick p, File dir, long[] done, long totalBytes) throws IOException {
        File dest = new File(dir, p.name);
        try (InputStream input = getContentResolver().openInputStream(p.uri);
             OutputStream output = new FileOutputStream(dest)) {
            if (input == null) throw new IOException("Unable to read " + p.name);
            byte[] buffer = new byte[256 * 1024];
            int count;
            int lastPct = -1;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                done[0] += count;
                if (totalBytes > 0) {
                    int pct = (int) Math.min(100, done[0] * 100 / totalBytes);
                    if (pct != lastPct) {
                        lastPct = pct;
                        runOnUiThread(() -> { if (progress != null) progress.setProgress(pct); });
                    }
                }
            }
        }
    }

    /** Every FILE in the cue must be present; fix up case-only mismatches. */
    private static void checkCueTracks(File cueFile, File dir) throws IOException {
        List<String> missing = new ArrayList<>();
        File[] present = dir.listFiles();
        for (String line : PsxFiles.readText(cueFile).split("\\r?\\n")) {
            Matcher m = CUE_FILE.matcher(line);
            if (!m.find()) continue;
            String ref = m.group(1) != null ? m.group(1) : m.group(2);
            int slash = Math.max(ref.lastIndexOf('/'), ref.lastIndexOf('\\'));
            String base = ref.substring(slash + 1);
            if (new File(dir, base).isFile()) continue;
            File match = null;
            if (present != null)
                for (File f : present) if (f.getName().equalsIgnoreCase(base)) match = f;
            if (match != null && match.renameTo(new File(dir, base))) continue;
            missing.add("\"" + base + "\"");
        }
        if (!missing.isEmpty()) throw new MissingTracksException(String.join(", ", missing));
    }

    private static String quoteToml(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void showError(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Could not load the game file")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private String resString(String name) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id != 0 ? getString(id) : "";
    }
}
