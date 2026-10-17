package local.haechi;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 팩: named groups of browser rules (same format as browser.txt), one file each in files/packs/.
 * A whole pack is switched on or off; single lines inside can still be paused like anywhere else.
 * The first line "! 팩: 이름" holds the name, so a pack file shared as text keeps its name.
 * Packs shipped in assets/packs/ are copied once (switched off) and then belong to the user.
 */
final class Packs {
    private Packs() {}

    static final String DIR = "packs";
    private static final String NAME_PREFIX = "! 팩: ";
    private static final String PREFS = "packs", KEY_ON = "on", KEY_INSTALLED = "installed";
    private static volatile boolean ensured;

    static final class Pack {
        final String id, name;
        final int rules, paused;
        final boolean on;
        Pack(String id, String name, int rules, int paused, boolean on) {
            this.id = id; this.name = name; this.rules = rules; this.paused = paused; this.on = on;
        }
    }

    /** "packs/youtube.txt" for id "youtube" — a path Lists.read/save understand. */
    static String file(String id) { return DIR + "/" + id + ".txt"; }

    static boolean isPackFile(String f) {
        return f != null && f.startsWith(DIR + "/") && f.endsWith(".txt") && f.indexOf("..") < 0 && f.indexOf('/', DIR.length() + 1) < 0;
    }

    static String idOf(String f) { return f.substring(DIR.length() + 1, f.length() - 4); }

    /** Creates the folder and copies shipped packs not installed before (a deleted one stays deleted). */
    static synchronized void ensure(Context c) {
        if (ensured) return;
        File dir = new File(c.getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        SharedPreferences p = prefs(c);
        Set<String> installed = new HashSet<>(p.getStringSet(KEY_INSTALLED, Collections.emptySet()));
        try {
            String[] assets = c.getAssets().list(DIR);
            if (assets != null) for (String a : assets) {
                if (!a.endsWith(".txt")) continue;
                String id = a.substring(0, a.length() - 4);
                if (installed.contains(id)) continue;
                File f = new File(dir, a);
                if (!f.exists()) {
                    try (InputStream in = c.getAssets().open(DIR + "/" + a)) {
                        java.nio.file.Files.write(f.toPath(), readAll(in).getBytes(StandardCharsets.UTF_8));
                    }
                }
                installed.add(id);   // shipped packs start switched off
            }
        } catch (IOException ignored) {}
        p.edit().putStringSet(KEY_INSTALLED, installed).apply();
        ensured = true;
    }

    /** Every pack, by name. */
    static List<Pack> all(Context c) {
        ensure(c);
        List<Pack> out = new ArrayList<>();
        File[] files = new File(c.getFilesDir(), DIR).listFiles();
        if (files == null) return out;
        Set<String> on = onSet(c);
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".txt")) continue;
            String id = n.substring(0, n.length() - 4);
            String text = read(c, id);
            int rules = 0, paused = 0;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (Lists.isPaused(t)) { rules++; paused++; }
                else if (!t.isEmpty() && !t.startsWith("!")) rules++;
            }
            out.add(new Pack(id, nameIn(text, id), rules, paused, on.contains(id)));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static String name(Context c, String id) { return nameIn(read(c, id), id); }

    private static String nameIn(String text, String id) {
        String first = text.split("\n", 2)[0].trim();
        return first.startsWith(NAME_PREFIX) ? first.substring(NAME_PREFIX.length()).trim() : id;
    }

    static boolean isOn(Context c, String id) { return onSet(c).contains(id); }

    /** Switches a whole pack; the running filter and Samsung Internet pick it up at once. */
    static void setOn(Context c, String id, boolean on) throws IOException {
        Set<String> s = new HashSet<>(onSet(c));
        if (on) s.add(id); else s.remove(id);
        prefs(c).edit().putStringSet(KEY_ON, s).commit();
        Lists.load(c);                  // wildcard sites in packs
        SamsungBlocker.notifyUpdate(c);
    }

    /** New empty pack, switched on. Returns its id. */
    static String create(Context c, String name) throws IOException {
        ensure(c);
        String id = "p" + System.currentTimeMillis();
        Set<String> s = new HashSet<>(onSet(c));
        s.add(id);
        prefs(c).edit().putStringSet(KEY_ON, s).commit();
        Lists.save(c, file(id), NAME_PREFIX + clean(name) + "\n");
        return id;
    }

    static void rename(Context c, String id, String name) throws IOException {
        String text = read(c, id);
        String[] parts = text.split("\n", 2);
        String rest = parts[0].trim().startsWith(NAME_PREFIX) ? (parts.length > 1 ? parts[1] : "") : text;
        Lists.save(c, file(id), NAME_PREFIX + clean(name) + "\n" + rest);
    }

    static void delete(Context c, String id) throws IOException {
        new File(c.getFilesDir(), file(id)).delete();
        Set<String> s = new HashSet<>(onSet(c));
        s.remove(id);
        prefs(c).edit().putStringSet(KEY_ON, s).commit();
        Lists.load(c);
        SamsungBlocker.notifyUpdate(c);
    }

    /** All lines of the switched-on packs, for Samsung Internet, the picker and wildcard watching. */
    static String enabledText(Context c) {
        ensure(c);
        StringBuilder out = new StringBuilder();
        for (String id : onSet(c)) {
            if (new File(c.getFilesDir(), file(id)).exists()) out.append(read(c, id)).append('\n');
        }
        return out.toString();
    }

    private static String read(Context c, String id) {
        try { return Lists.read(c, file(id)); } catch (IOException e) { return ""; }
    }

    private static String clean(String name) {
        String n = name.replace('\n', ' ').trim();
        return n.isEmpty() ? "새 팩" : n;
    }

    private static Set<String> onSet(Context c) {
        return prefs(c).getStringSet(KEY_ON, Collections.emptySet());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
    }
}
