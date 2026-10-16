package local.haechi;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Wildcard sites in browser.txt element-hiding rules, e.g. "aa*.com##.ad" for mirror sites
 * aa1.com, aa2.com, ... Samsung Internet (Adblock Plus format) has no such syntax, so we expand it:
 * every DNS query (and every page the picker opens) is checked against the patterns, matching domains
 * are remembered in wildcard_seen.txt, and the rule goes to Samsung Internet as "aa1.com,aa2.com##.ad".
 * '*' stands for letters, digits or '-' inside one label (never a dot).
 */
final class Wildcards {
    private Wildcards() {}

    static final String SEEN = "wildcard_seen.txt";
    private static final int MAX_SEEN = 2000;

    private static volatile List<Pattern> patterns = Collections.emptyList();
    private static final Set<String> seen = new LinkedHashSet<>();   // guarded by itself
    private static volatile boolean loaded;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    static boolean isPattern(String domain) {
        return domain.indexOf('*') >= 0;
    }

    static Pattern compile(String pattern) {
        StringBuilder re = new StringBuilder();
        for (String part : pattern.toLowerCase(Locale.ROOT).split("\\*", -1)) {
            if (re.length() > 0) re.append("[a-z0-9-]*");
            re.append(Pattern.quote(part));
        }
        return Pattern.compile(re.toString());
    }

    /** Collects the wildcard sites of browser.txt's cosmetic rules. Called from Lists.load(). */
    static void update(Context c, String browserText) {
        List<Pattern> out = new ArrayList<>();
        for (String d : sites(browserText)) out.add(compile(d));
        patterns = out;
        if (!out.isEmpty()) ensureLoaded(c);
    }

    /** "aa*.com" etc. from lines like "aa*.com,~bb*.net##sel" (also #@#, #?#, #$#). */
    private static List<String> sites(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("!")) continue;
            int at = cosmeticSep(t);
            if (at <= 0) continue;
            for (String d : t.substring(0, at).split(",")) {
                d = d.trim();
                if (d.startsWith("~")) d = d.substring(1);
                if (isPattern(d) && !out.contains(d)) out.add(d);
            }
        }
        return out;
    }

    /** Index of "##", "#@#", "#?#" or "#$#" (where the site list ends), or -1. */
    static int cosmeticSep(String line) {
        int i = line.indexOf('#');
        while (i >= 0 && i + 1 < line.length()) {
            char n = line.charAt(i + 1);
            if (n == '#') return i;
            if ((n == '@' || n == '?' || n == '$') && i + 2 < line.length() && line.charAt(i + 2) == '#') return i;
            i = line.indexOf('#', i + 1);
        }
        return -1;
    }

    /**
     * Called for every DNS query, so it must be cheap: nothing to do unless browser.txt has patterns.
     * "www.aa7.com" is checked as itself and as each parent; the part that matches ("aa7.com") is kept.
     */
    static void seen(Context c, String name) {
        List<Pattern> ps = patterns;
        if (ps.isEmpty() || name == null) return;
        String d = name.toLowerCase(Locale.ROOT);
        while (d.indexOf('.') > 0) {
            for (Pattern p : ps) {
                if (p.matcher(d).matches()) { remember(c.getApplicationContext(), d); return; }
            }
            d = d.substring(d.indexOf('.') + 1);
        }
    }

    private static void remember(Context c, String domain) {
        ensureLoaded(c);
        synchronized (seen) {
            if (seen.size() >= MAX_SEEN || !seen.add(domain)) return;
        }
        writer.execute(() -> {
            try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), SEEN), true)) {
                out.write((domain + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
            SamsungBlocker.notifyUpdate(c);   // Samsung Internet re-reads: the rule now covers this domain
        });
    }

    private static void ensureLoaded(Context c) {
        if (loaded) return;
        synchronized (seen) {
            if (loaded) return;
            try {
                for (String line : Lists.read(c, SEEN).split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty()) seen.add(t);
                }
            } catch (IOException ignored) {}
            loaded = true;
        }
    }

    /** Remembered domains matching {@code pattern} exactly, in the order they were found. */
    static List<String> expand(Context c, String pattern) {
        ensureLoaded(c);
        Pattern p = compile(pattern);
        List<String> out = new ArrayList<>();
        synchronized (seen) {
            for (String d : seen) if (p.matcher(d).matches()) out.add(d);
        }
        return out;
    }

    static List<String> all(Context c) {
        ensureLoaded(c);
        synchronized (seen) { return new ArrayList<>(seen); }
    }

    static void clear(Context c) {
        synchronized (seen) { seen.clear(); }
        writer.execute(() -> {
            new File(c.getFilesDir(), SEEN).delete();
            SamsungBlocker.notifyUpdate(c);
        });
    }

    /**
     * One browser.txt line as Samsung Internet should get it: wildcard sites replaced by the domains
     * seen so far. Returns null when a rule meant only for wildcard sites has no domain yet — sending
     * it without sites would hide the element on every site.
     */
    static String expandLine(Context c, String line) {
        int at = cosmeticSep(line);
        if (at <= 0) return line;
        String sites = line.substring(0, at);
        if (!isPattern(sites)) return line;
        List<String> out = new ArrayList<>();
        boolean anyInclude = false;
        for (String d : sites.split(",")) {
            d = d.trim();
            boolean not = d.startsWith("~");
            String bare = not ? d.substring(1) : d;
            if (!not) anyInclude = true;
            if (!isPattern(bare)) { if (!bare.isEmpty()) out.add(d); continue; }
            for (String e : expand(c, bare)) {
                String s = not ? "~" + e : e;
                if (!out.contains(s)) out.add(s);
            }
        }
        // "aa*.com,~aa9.com": a site that is excluded must not also be listed as included.
        List<String> excluded = new ArrayList<>();
        for (String s : out) if (s.startsWith("~")) excluded.add(s.substring(1));
        boolean hasInclude = false;
        for (int i = out.size() - 1; i >= 0; i--) {
            String s = out.get(i);
            if (s.startsWith("~")) continue;
            boolean drop = false;
            for (String x : excluded) if (s.equals(x) || s.endsWith("." + x)) drop = true;
            if (drop) out.remove(i); else hasInclude = true;
        }
        if (anyInclude && !hasInclude) return null;
        return String.join(",", out) + line.substring(at);
    }

    /** "aa12.com" -> "aa*.com" (digits in the name before the last dot), or null if there are none. */
    static String suggest(String domain) {
        int dot = domain.lastIndexOf('.');
        if (dot <= 0) return null;
        String name = domain.substring(0, dot), s = name.replaceAll("[0-9]+", "*");
        return s.equals(name) ? null : s + domain.substring(dot);
    }
}
