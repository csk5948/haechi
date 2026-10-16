package local.adblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Public block lists bundled read-only in assets/bundled/ (never copied into the user's files).
 *
 *  - DNS lists (AdGuard DNS, List-KR): "domain" = block incl. subdomains, "@@domain" = exception,
 *    "#" = comment. All enabled DNS lists are merged into ONE packed table (below), loaded on a
 *    background thread. Until it is loaded, only the user's own lists apply.
 *  - YousList: Adblock Plus rules, handed to Samsung Internet only (see SamsungBlocker).
 *
 * Memory: 185k domains as a HashSet<String> would take ~20 MB of heap. Instead all domains are
 * stored back-to-back in one sorted byte[] with an int[] of offsets and a byte[] of sources
 * (~5 MB), and looked up with binary search (~18 comparisons per label).
 */
final class Bundled {
    private Bundled() {}

    static final class Info {
        final String id, title, asset;
        final boolean dns;
        Info(String id, String title, String asset, boolean dns) {
            this.id = id; this.title = title; this.asset = asset; this.dns = dns;
        }
    }

    static final Info[] LISTS = {
            new Info("adguard", "AdGuard DNS", "bundled/adguard-dns.txt", true),
            new Info("listkr", "List-KR", "bundled/list-kr-dns.txt", true),
            new Info("yous", "YousList → 삼성 인터넷", "bundled/youslist.txt", false),
    };
    static final Info YOUSLIST = LISTS[2];

    /** A bundled rule that matched: which list, and the listed (parent) domain. */
    static final class Match {
        final String list, rule;
        Match(String list, String rule) { this.list = list; this.rule = rule; }
    }

    private static volatile Table block = Table.EMPTY, except = Table.EMPTY;
    private static volatile boolean ready, loading;
    private static int generation;

    // ---------------------------------------------------------------- on/off (SharedPreferences)

    static boolean isOn(Context c, Info i) {
        return prefs(c).getBoolean("bundled_" + i.id, true);   // all ON by default
    }

    /** Saves the switch, reloads the DNS table in the background, and tells Samsung Internet. */
    static void setOn(Context c, Info i, boolean on) {
        prefs(c).edit().putBoolean("bundled_" + i.id, on).apply();
        if (i.dns) loadAsync(c);
        SamsungBlocker.notifyUpdate(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- loading

    static boolean isReady() { return ready; }
    static boolean isLoading() { return loading; }
    static int blockCount() { return block.size(); }

    /** Loads once (service start, or when the UI first needs a verdict). */
    static synchronized void ensureLoaded(Context c) {
        if (!ready && !loading) loadAsync(c);
    }

    /** (Re)builds the table from the enabled DNS lists on a background thread, then swaps it in. */
    static synchronized void loadAsync(Context c) {
        final Context app = c.getApplicationContext();
        final int gen = ++generation;
        loading = true;
        Thread t = new Thread(() -> {
            Builder b = new Builder(), e = new Builder();
            for (int i = 0; i < LISTS.length; i++) {
                Info info = LISTS[i];
                if (!info.dns || !isOn(app, info)) continue;
                try (BufferedReader r = open(app, info)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.charAt(0) == '#') continue;
                        if (line.startsWith("@@")) e.add(line, 2, (byte) i);
                        else b.add(line, 0, (byte) i);
                    }
                } catch (IOException ignored) {}
            }
            Table nb = b.build(), ne = e.build();
            synchronized (Bundled.class) {
                if (gen != generation) return;   // a newer reload started meanwhile
                block = nb;
                except = ne;
                ready = true;
                loading = false;
            }
        }, "bundled-load");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    static BufferedReader open(Context c, Info i) throws IOException {
        return new BufferedReader(new InputStreamReader(c.getAssets().open(i.asset), StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- lookup

    static Match findBlock(String name) { return find(block, name); }
    static Match findException(String name) { return find(except, name); }

    /** Walks name and its parents (ads.x.com, x.com, com); first hit wins. */
    private static Match find(Table t, String name) {
        if (t.size() == 0) return null;
        for (String d = name; ; ) {
            int i = t.find(d);
            if (i >= 0) return new Match(LISTS[t.src[i]].title, d);
            int dot = d.indexOf('.');
            if (dot < 0) return null;
            d = d.substring(dot + 1);
        }
    }

    // ---------------------------------------------------------------- UI helpers (stream, no table)

    /** First comment lines of a list (source URL, conversion date / version). */
    static String header(Context c, Info i) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = open(c, i)) {
            String line;
            for (int n = 0; n < 8 && (line = r.readLine()) != null; n++) {
                String t = line.replaceFirst("^[#!]\\s*", "").trim();
                if (!(line.startsWith("#") || line.startsWith("!")) || t.isEmpty()) continue;
                if (t.startsWith("License")) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    static final class Hit {
        final String domain, list;
        final boolean exception;
        Hit(String domain, String list, boolean exception) { this.domain = domain; this.list = list; this.exception = exception; }
    }

    /** Substring search over the enabled DNS lists by streaming the asset files. */
    static List<Hit> search(Context c, String q, int limit, java.util.concurrent.atomic.AtomicBoolean cancel) {
        List<Hit> out = new ArrayList<>();
        for (Info info : LISTS) {
            if (!info.dns || !isOn(c, info)) continue;
            try (BufferedReader r = open(c, info)) {
                String line;
                while ((line = r.readLine()) != null && out.size() < limit && !cancel.get()) {
                    if (line.isEmpty() || line.charAt(0) == '#' || !line.contains(q)) continue;
                    boolean ex = line.startsWith("@@");
                    out.add(new Hit(ex ? line.substring(2).trim() : line.trim(), info.title, ex));
                }
            } catch (IOException ignored) {}
        }
        return out;
    }

    // ---------------------------------------------------------------- packed sorted table

    static final class Table {
        static final Table EMPTY = new Table(new byte[0], new int[]{0}, new byte[0]);
        final byte[] data;   // all domains, sorted, back to back
        final int[] off;     // entry i = data[off[i] .. off[i+1])
        final byte[] src;    // index into LISTS

        Table(byte[] data, int[] off, byte[] src) { this.data = data; this.off = off; this.src = src; }

        int size() { return off.length - 1; }

        int find(String key) {
            int lo = 0, hi = size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = compare(data, off[mid], off[mid + 1], key);
                if (c < 0) lo = mid + 1; else if (c > 0) hi = mid - 1; else return mid;
            }
            return -1;
        }

        /** Compares stored bytes with an ASCII key (domains are lower-case ASCII / punycode). */
        private static int compare(byte[] a, int s, int e, String k) {
            int n = Math.min(e - s, k.length());
            for (int i = 0; i < n; i++) {
                int x = a[s + i] & 0xFF, y = k.charAt(i);
                if (x != y) return x - y;
            }
            return (e - s) - k.length();
        }
    }

    /** Collects entries, then sorts and de-duplicates them into a Table. */
    static final class Builder {
        private byte[] data = new byte[1 << 16];
        private int len, n;
        private int[] off = new int[1024];
        private byte[] src = new byte[1024];

        /** Adds line[from..] if it is a plain domain (a-z 0-9 . - _ ; upper case folded). */
        void add(String line, int from, byte s) {
            int end = line.length();
            while (end > from && line.charAt(end - 1) == '.') end--;
            int start = from;
            while (start < end && line.charAt(start) == '.') start++;
            if (end - start < 1) return;
            if (data.length - len < end - start) data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, len + end - start));
            int p = len;
            for (int i = start; i < end; i++) {
                char ch = line.charAt(i);
                if (ch >= 'A' && ch <= 'Z') ch += 32;
                boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.' || ch == '_';
                if (!ok) return;   // anything else (spaces, '/', '^', non-ASCII): skip the line
                data[p++] = (byte) ch;
            }
            if (n + 1 >= off.length) {
                off = java.util.Arrays.copyOf(off, off.length * 2);
                src = java.util.Arrays.copyOf(src, src.length * 2);
            }
            off[n] = len;
            src[n] = s;
            n++;
            len = p;
            off[n] = len;
        }

        Table build() {
            if (n == 0) return Table.EMPTY;
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            mergeSort(idx);   // stable: for duplicates the earlier list (AdGuard) wins
            byte[] d = new byte[len];
            int[] o = new int[n + 1];
            byte[] s = new byte[n];
            int m = 0, p = 0, prev = -1;
            for (int k : idx) {
                if (prev >= 0 && cmp(prev, k) == 0) continue;   // duplicate domain
                int l = off[k + 1] - off[k];
                System.arraycopy(data, off[k], d, p, l);
                o[m] = p;
                s[m] = src[k];
                p += l;
                m++;
                prev = k;
            }
            o[m] = p;
            return new Table(java.util.Arrays.copyOf(d, p), java.util.Arrays.copyOf(o, m + 1), java.util.Arrays.copyOf(s, m));
        }

        private int cmp(int a, int b) {
            int as = off[a], ae = off[a + 1], bs = off[b], be = off[b + 1];
            int l = Math.min(ae - as, be - bs);
            for (int i = 0; i < l; i++) {
                int x = data[as + i] & 0xFF, y = data[bs + i] & 0xFF;
                if (x != y) return x - y;
            }
            return (ae - as) - (be - bs);
        }

        /** Bottom-up merge sort of entry indices (fast on the already-sorted list files). */
        private void mergeSort(int[] a) {
            int[] tmp = new int[a.length];
            for (int w = 1; w < a.length; w *= 2) {
                for (int lo = 0; lo < a.length - w; lo += 2 * w) {
                    int mid = lo + w, hi = Math.min(lo + 2 * w, a.length);
                    if (cmp(a[mid - 1], a[mid]) <= 0) continue;   // already in order
                    int i = lo, j = mid, k = lo;
                    while (i < mid && j < hi) tmp[k++] = cmp(a[i], a[j]) <= 0 ? a[i++] : a[j++];
                    while (i < mid) tmp[k++] = a[i++];
                    while (j < hi) tmp[k++] = a[j++];
                    System.arraycopy(tmp, lo, a, lo, hi - lo);
                }
            }
        }
    }
}
