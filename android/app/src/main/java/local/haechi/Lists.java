package local.haechi;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Block list and allow list. Both are plain text files in the app's private files dir:
 *   block.txt  (first run: copied from the bundled assets/default.txt)
 *   allow.txt  (first run: empty)
 * The parsed sets are swapped atomically, so the running filter picks up edits immediately.
 */
final class Lists {
    private Lists() {}

    static final String BLOCK = "block.txt";
    static final String ALLOW = "allow.txt";
    /** Samsung Internet rules (Adblock Plus format); not used by the DNS filter. */
    static final String BROWSER = "browser.txt";
    private static final String BROWSER_ASSET = "browser_default.txt";
    private static final String DEFAULT_ASSET = "default.txt";

    private static volatile Set<String> block = Collections.emptySet();
    private static volatile Set<String> allow = Collections.emptySet();

    /** Creates the files on first run, then (re)parses both. */
    static synchronized void load(Context c) throws IOException {
        File b = new File(c.getFilesDir(), BLOCK);
        File a = new File(c.getFilesDir(), ALLOW);
        if (!b.exists()) writeFile(b, readAsset(c, DEFAULT_ASSET));
        if (!a.exists()) writeFile(a, "");
        File w = new File(c.getFilesDir(), BROWSER);
        if (!w.exists()) writeFile(w, readAsset(c, BROWSER_ASSET));
        block = parse(readFile(b));
        allow = parse(readFile(a));
    }

    /** Overwrites block.txt with the bundled default list. allow.txt is left alone. */
    static synchronized void resetDefault(Context c) throws IOException {
        writeFile(new File(c.getFilesDir(), BLOCK), readAsset(c, DEFAULT_ASSET));
        load(c);
        SamsungBlocker.notifyUpdate(c);
    }

    static String read(Context c, String fileName) throws IOException {
        File f = new File(c.getFilesDir(), fileName);
        return f.exists() ? readFile(f) : "";
    }

    static synchronized void save(Context c, String fileName, String text) throws IOException {
        writeFile(new File(c.getFilesDir(), fileName), text);
        load(c);
        SamsungBlocker.notifyUpdate(c);   // block/allow/browser rules are all part of what Samsung Internet gets
    }

    static int blockSize() { return block.size(); }
    static Set<String> blockSet() { return block; }
    static Set<String> allowSet() { return allow; }

    /** True if the domain or a parent is in the allow list. */
    static boolean isAllowed(String name) { return matches(allow, name); }

    /** Used for every DNS query: true if the final verdict is "block". */
    static boolean isBlocked(String name) {
        return classify(name).status == BLOCKED;
    }

    /** Verdict + where it came from, e.g. BLOCKED / "AdGuard DNS" / rule "doubleclick.net". */
    static final class Verdict {
        final int status;
        final String source, rule;   // source null = no list matched
        Verdict(int status, String source, String rule) { this.status = status; this.source = source; this.rule = rule; }
    }

    static final String SRC_MY_ALLOW = "내 허용 목록", SRC_MY_BLOCK = "내 차단 목록", SRC_EXCEPTION = "목록 예외";
    private static final Verdict PASS = new Verdict(0, null, null);

    /**
     * Precedence, each tier checking the name and all its parents:
     * user allow > user block > bundled exceptions (@@) > bundled block > pass.
     */
    static Verdict classify(String name) {
        String r;
        if ((r = matchRule(allow, name)) != null) return new Verdict(ALLOWED, SRC_MY_ALLOW, r);
        if ((r = matchRule(block, name)) != null) return new Verdict(BLOCKED, SRC_MY_BLOCK, r);
        Bundled.Match m = Bundled.findException(name);
        if (m != null) return new Verdict(PASSED, SRC_EXCEPTION, m.rule);
        m = Bundled.findBlock(name);
        if (m != null) return new Verdict(BLOCKED, m.list, m.rule);
        return PASS;
    }

    private static boolean matches(Set<String> set, String name) {
        return matchRule(set, name) != null;
    }

    /** "x.ads.example.com" matches an entry for itself, "ads.example.com", "example.com" or "com". */
    private static String matchRule(Set<String> set, String name) {
        if (set.isEmpty()) return null;
        String d = name;
        while (true) {
            if (set.contains(d)) return d;
            int dot = d.indexOf('.');
            if (dot < 0) return null;
            d = d.substring(dot + 1);
        }
    }

    /**
     * One domain per line. Also accepts hosts format ("0.0.0.0 ads.com") and the
     * filter-list style "||ads.com^". Lines starting with '#' or '!' are comments.
     */
    static Set<String> parse(String text) {
        Set<String> out = new HashSet<>();
        for (String line : text.split("\n")) {
            String d = parseLine(line);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** The domain written on one line, or null for comments / blank / unusable lines. */
    static String parseLine(String line) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null;
        int hash = line.indexOf('#');                           // trailing comment
        if (hash >= 0) line = line.substring(0, hash).trim();
        if (line.isEmpty()) return null;
        String[] tokens = line.split("\\s+");
        String d = tokens[tokens.length - 1].toLowerCase(Locale.ROOT);      // hosts format: last token
        if (d.startsWith("||")) d = d.substring(2);
        int caret = d.indexOf('^');
        if (caret >= 0) d = d.substring(0, caret);
        if (d.startsWith("*.")) d = d.substring(2);
        while (d.endsWith(".")) d = d.substring(0, d.length() - 1);
        return isUsable(d) ? d : null;
    }

    // ---- used by the query log screen ----

    static final int PASSED = 0, BLOCKED = 1, ALLOWED = 2;

    static int status(String name) {
        return classify(name).status;
    }

    /** True if block.txt has a line for exactly this domain (not just a parent). */
    static boolean blockedExactly(String name) {
        return block.contains(name);
    }

    /** Adds one domain as a new line at the end of the file and reloads the lists. */
    static synchronized void append(Context c, String fileName, String domain) throws IOException {
        String text = read(c, fileName);
        if (!text.isEmpty() && !text.endsWith("\n")) text += "\n";
        save(c, fileName, text + domain + "\n");
    }

    /**
     * Removes every line whose domain would match {@code name}: exactly equal, or (if
     * {@code parentsToo}) any parent of it. Comments and other lines are kept as they are.
     */
    static synchronized void remove(Context c, String fileName, String name, boolean parentsToo) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : read(c, fileName).split("\n")) {   // split() drops trailing empty pieces
            String d = parseLine(line);
            boolean hit = d != null && (d.equals(name) || (parentsToo && name.endsWith("." + d)));
            if (!hit) out.append(line).append('\n');
        }
        String text = out.toString();
        save(c, fileName, text.trim().isEmpty() ? "" : text);
    }

    // ---- used by the list manager: edit individual lines, keep everything else as it is ----

    /** Appends several lines in one write (one reload, one Samsung update). */
    static synchronized void appendAll(Context c, String fileName, List<String> lines) throws IOException {
        if (lines.isEmpty()) return;
        StringBuilder text = new StringBuilder(read(c, fileName));
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
        for (String l : lines) text.append(l).append('\n');
        save(c, fileName, text.toString());
    }

    /** Replaces the first line whose trimmed text equals {@code oldLine}. */
    static synchronized void replaceLine(Context c, String fileName, String oldLine, String newLine) throws IOException {
        StringBuilder out = new StringBuilder();
        boolean done = false;
        for (String line : read(c, fileName).split("\n")) {
            if (!done && line.trim().equals(oldLine)) { out.append(newLine).append('\n'); done = true; }
            else out.append(line).append('\n');
        }
        if (!done) out.append(newLine).append('\n');   // the line vanished meanwhile: just add
        save(c, fileName, out.toString());
    }

    /** Removes the lines whose trimmed text is in {@code lines}. Comments and the rest stay. */
    static synchronized void removeLines(Context c, String fileName, Set<String> lines) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : read(c, fileName).split("\n")) {
            if (!lines.contains(line.trim())) out.append(line).append('\n');
        }
        String text = out.toString();
        save(c, fileName, text.trim().isEmpty() ? "" : text);
    }

    /** Skips empty strings, bare words like "localhost", IP addresses and anything with odd characters. */
    private static boolean isUsable(String d) {
        if (d.isEmpty() || d.indexOf('.') < 0) return false;       // "localhost", "broadcasthost", ...
        boolean allDigits = true;
        for (int i = 0; i < d.length(); i++) {
            char ch = d.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.' || ch == '_';
            if (!ok) return false;                                 // ':' (IPv6), '/', '$' options, ...
            if (ch != '.' && (ch < '0' || ch > '9')) allDigits = false;
        }
        return !allDigits;                                         // "0.0.0.0", "127.0.0.1"
    }

    private static String readAsset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            return readAll(in);
        }
    }

    private static String readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
    }

    /** Write to a temp file, then rename, so a crash never leaves a half-written list. */
    private static void writeFile(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }
}
