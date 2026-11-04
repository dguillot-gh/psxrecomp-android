package com.psxrecomp.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
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
 *
 * Continue (shown once a save state exists) lists the game's save-state slots,
 * newest first, and starts the game with the chosen one: it leaves
 * files/autoload_slot for the game process, which loads it once the runtime is
 * up (PsxMenu.autoloadAfterRestart). It only reads the state files.
 */
public class LauncherActivity extends Activity {
    private static final int REQUEST_FILES = 81;
    private static final int REQUEST_FOLDER = 82;
    private static final int REQUEST_CARD = 83;
    private static final String PREFS = "psx_launcher";
    private static final long CARD_SIZE = 131072; // 128 KB – exactly one PS1 memory card
    private static final Pattern CUE_FILE =
            Pattern.compile("^\\s*FILE\\s+(?:\"([^\"]+)\"|(\\S+))", Pattern.CASE_INSENSITIVE);
    /* The runtime's slot files (savestate.c savestate_slot_path):
     * <saves>/<openbios|scph1001>/state_<entry pc>[_disc<N>]_slot<NN>.pst, slot 0-based. */
    private static final Pattern STATE_FILE =
            Pattern.compile("state_[0-9A-Fa-f]{8}(?:_disc(\\d+))?_slot(\\d{2})\\.pst");
    /* savestate.c: 12-byte header ("PSTH", u32 width, u32 height), then ARGB pixels. */
    private static final int THUMB_W = 128, THUMB_H = 96;

    private TextView status;
    private Button resume;
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

        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(0xFF101014);

        int bgId = getResources().getIdentifier("launcher_bg", "drawable", getPackageName());
        if (bgId != 0) {
            ImageView bgView = new ImageView(this);
            bgView.setImageResource(bgId);
            bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            container.addView(bgView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // Subtle dark overlay to ensure text and buttons remain easily readable
            View scrim = new View(this);
            scrim.setBackgroundColor(0x55000000);
            container.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * dp);
        root.setPadding(pad, pad, pad, pad);
        container.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        resume = new Button(this);
        resume.setText("Continue");
        resume.setOnClickListener(v -> chooseState());
        root.addView(resume, button(dp));

        play = new Button(this);
        play.setText("Play");
        play.setOnClickListener(v -> {
            /* Play starts fresh: drop a Continue choice a failed start left behind. */
            //noinspection ResultOfMethodCallIgnored
            new File(getFilesDir(), PsxMenu.AUTOLOAD_FILE).delete();
            startGame();
        });
        root.addView(play, button(dp));

        Button select = new Button(this);
        select.setText("Select game file");
        select.setOnClickListener(v -> pickFiles());
        root.addView(select, button(dp));

        Button importCard = new Button(this);
        importCard.setText("Import memory card");
        importCard.setOnClickListener(v -> pickCard());
        root.addView(importCard, button(dp));

        TextView hint = new TextView(this);
        hint.setText("Pick the .cue and its .bin together (long-press to select both), "
                + "or a single .bin, .iso or .chd. Several discs: select every disc's .cue and .bin "
                + "together; change discs in the game with the pad's menu button.");
        hint.setTextColor(0xFF707078);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, (int) (16 * dp), 0, 0);
        root.addView(hint, wrap());

        setContentView(container);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        /* "Restart game" in the pad's menu ends the game process after leaving this
         * flag; start the game again (it loads the state the restart saved). */
        File restart = new File(getFilesDir(), PsxMenu.RESTART_FLAG);
        if (restart.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            restart.delete();
            startGame();
        }
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
        resume.setVisibility(hasGame() && !findStates().isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void startGame() {
        if (!hasGame() || importing) return;
        startActivity(new Intent(this, PsxGameActivity.class));
    }

    /* ---- Continue: pick a save state -------------------------------------- */

    /** One save-state slot file on disk. */
    private static final class SavedState {
        final File file;
        final int slot;   // 0-based, as the runtime numbers it
        final int disc;   // 1-based for multi-disc sets, 0 = single-disc naming
        final long time;

        SavedState(File file, int slot, int disc) {
            this.file = file;
            this.slot = slot;
            this.disc = disc;
            this.time = file.lastModified();
        }
    }

    /**
     * Every slot file the runtime has written, newest first. The runtime keeps
     * them in a BIOS-named folder inside its save folder (files/ on Android), so
     * files/ and the folders directly inside it are searched. When the set is
     * scoped by disc, older unscoped files are left out: the runtime would not
     * look for them under that name.
     */
    private List<SavedState> findStates() {
        List<SavedState> found = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        File files = getFilesDir();
        dirs.add(files);
        File[] children = files.listFiles();
        if (children != null)
            for (File c : children)
                if (c.isDirectory() && !c.getName().startsWith("gamedata")) dirs.add(c);
        boolean scoped = false;
        for (File dir : dirs) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                Matcher m = STATE_FILE.matcher(f.getName());
                if (!m.matches() || !f.isFile() || f.length() == 0) continue;
                int slot = Integer.parseInt(m.group(2));
                int disc = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
                if (disc > 0) scoped = true;
                found.add(new SavedState(f, slot, disc));
            }
        }
        if (scoped) {
            List<SavedState> keep = new ArrayList<>();
            for (SavedState s : found) if (s.disc > 0) keep.add(s);
            found = keep;
        }
        Collections.sort(found, (a, b) -> Long.compare(b.time, a.time));
        return found;
    }

    private void chooseState() {
        if (!hasGame() || importing) return;
        List<SavedState> states = findStates();
        if (states.isEmpty()) {
            refresh();
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (8 * dp);
        list.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Continue from")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        for (SavedState s : states) {
            View row = stateRow(s, dp);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                continueFrom(s);
            });
            list.addView(row);
        }
        dialog.show();
    }

    private View stateRow(SavedState s, float dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (6 * dp);
        row.setPadding(pad, pad, pad, pad);
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true))
            row.setBackgroundResource(ripple.resourceId);

        ImageView thumb = new ImageView(this);
        Bitmap picture = readThumb(s.file);
        if (picture != null) thumb.setImageBitmap(picture);
        else thumb.setBackgroundColor(0xFF000000);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(thumb, new LinearLayout.LayoutParams((int) (96 * dp), (int) (72 * dp)));

        TextView label = new TextView(this);
        Date when = new Date(s.time);
        String name = "Slot " + (s.slot + 1) + (s.disc > 0 ? "  ·  Disc " + s.disc : "");
        label.setText(name + "\n" + DateFormat.getMediumDateFormat(this).format(when) + "  "
                + DateFormat.getTimeFormat(this).format(when));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setPadding((int) (12 * dp), 0, 0, 0);
        row.addView(label, wrap());
        return row;
    }

    /** The slot's .thumb picture, or null when it is missing or not the expected size. */
    private static Bitmap readThumb(File state) {
        String n = state.getName();
        File f = new File(state.getParentFile(), n.substring(0, n.length() - 4) + ".thumb");
        int pixels = THUMB_W * THUMB_H;
        if (f.length() != 12 + 4L * pixels) return null;
        byte[] raw = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(raw);
        } catch (IOException e) {
            return null;
        }
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (b.get(0) != 'P' || b.get(1) != 'S' || b.get(2) != 'T' || b.get(3) != 'H' ||
                b.getInt(4) != THUMB_W || b.getInt(8) != THUMB_H)
            return null;
        int[] argb = new int[pixels];
        b.position(12);
        b.asIntBuffer().get(argb);
        return Bitmap.createBitmap(argb, THUMB_W, THUMB_H, Bitmap.Config.ARGB_8888);
    }

    /** Start the game and have it load this slot as soon as it is running. */
    private void continueFrom(SavedState s) {
        try {
            PsxFiles.writeText(new File(getFilesDir(), PsxMenu.AUTOLOAD_FILE),
                    PsxMenu.autoloadLine(s.slot, s.disc));
        } catch (IOException e) {
            showError("Could not prepare the save state: " + e.getMessage());
            return;
        }
        startGame();
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

    private void pickCard() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CARD);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQUEST_CARD && data.getData() != null) {
            importCard(data.getData());
            return;
        }
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

    /**
     * Copy a .mcd file picked by the user into files/card1.mcd.
     * Validates the file is exactly 128 KB. If a card already exists, asks
     * the user before overwriting (the existing card is NOT backed up here –
     * use phone.ps1 -Action backup-saves for that).
     */
    private void importCard(Uri uri) {
        // Resolve the display name and size from the content provider.
        String displayName;
        long size;
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                showError("Cannot read the selected file."); return;
            }
            displayName = c.getString(0);
            size = c.isNull(1) ? -1 : c.getLong(1);
        }
        if (size != CARD_SIZE) {
            showError("\"" + displayName + "\" is " + size + " bytes. "
                    + "A PS1 memory card image must be exactly 131072 bytes (128 KB).");
            return;
        }
        File dest = new File(getFilesDir(), "card1.mcd");
        if (dest.exists()) {
            // Show what the existing card is so the user can decide safely.
            java.text.DateFormat dateFmt = android.text.format.DateFormat.getMediumDateFormat(this);
            java.text.DateFormat timeFmt = android.text.format.DateFormat.getTimeFormat(this);
            java.util.Date existing = new java.util.Date(dest.lastModified());
            String existingInfo = "Last saved: " + dateFmt.format(existing)
                    + " at " + timeFmt.format(existing)
                    + "\nSize: " + dest.length() + " bytes";
            new AlertDialog.Builder(this)
                    .setTitle("Memory card already exists")
                    .setMessage("card1.mcd is already in the app.\n\n"
                            + existingInfo + "\n\n"
                            + "Replace it with \"" + displayName + "\"?\n\n"
                            + "⚠ Back up the existing card first:\n"
                            + "phone.ps1 -Action backup-saves")
                    .setPositiveButton("Replace", (d, w) -> doImportCard(uri, displayName, dest))
                    .setNegativeButton("Keep existing", null)
                    .show();
        } else {
            doImportCard(uri, displayName, dest);
        }
    }

    private void doImportCard(Uri uri, String displayName, File dest) {
        new Thread(() -> {
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new IOException("Cannot open the selected file.");
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            } catch (IOException e) {
                error = e.getMessage();
            }
            // Verify the written size as a sanity check.
            if (error == null && dest.length() != CARD_SIZE) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                error = "Copy failed: written size does not match.";
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (failure != null) {
                    showError(failure);
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Memory card imported")
                            .setMessage("\"" + displayName + "\" was saved as card1.mcd.\n"
                                    + "Start the game and your saves will be there.")
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
        }, "psx-card-import").start();
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
