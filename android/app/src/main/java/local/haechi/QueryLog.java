package local.haechi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * In-memory record of which domains were looked up (and, on Android 10+, by which app).
 * Never written to disk; gone when the app process ends.
 *
 * The map is kept in "last seen" order (a domain is moved to the end each time it is seen),
 * so evicting the oldest one when we exceed {@link #MAX} is just removing the first entry.
 */
final class QueryLog {
    private QueryLog() {}

    static final int MAX = 2000;
    static final int MAX_APPS = 5;

    static final class Entry {
        final String domain;
        long lastSeen;
        int count;
        String lastApp;                                       // most recent asking app, or null
        final LinkedHashSet<String> apps = new LinkedHashSet<>();   // up to MAX_APPS, oldest first
        Entry(String domain) { this.domain = domain; }
    }

    private static final LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
    private static volatile boolean recording = true;   // on by default

    static boolean isRecording() { return recording; }

    /** "● 새로 녹화": forget everything and start recording. */
    static synchronized void startNew() {
        map.clear();
        recording = true;
    }

    /** "■ 정지": stop adding entries (filtering itself keeps running). */
    static void pause() { recording = false; }

    static synchronized void clear() { map.clear(); }

    static synchronized void record(String domain) {
        if (!recording) return;
        Entry e = map.remove(domain);         // re-insert to move it to the end (newest)
        if (e == null) e = new Entry(domain);
        e.lastSeen = System.currentTimeMillis();
        e.count++;
        map.put(domain, e);
        if (map.size() > MAX) {
            Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
            it.next();
            it.remove();
        }
    }

    /** Attaches the asking app to an already recorded domain (looked up a moment later, off the reader thread). */
    static synchronized void addApp(String domain, String app) {
        if (!recording || app == null) return;
        Entry e = map.get(domain);
        if (e == null) return;
        e.lastApp = app;
        e.apps.remove(app);                   // re-add as newest
        e.apps.add(app);
        if (e.apps.size() > MAX_APPS) {
            Iterator<String> it = e.apps.iterator();
            it.next();
            it.remove();
        }
    }

    /** Copy of all entries, newest first. Entries are copied so the UI never sees them change. */
    static synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(map.size());
        for (Entry e : map.values()) {
            Entry c = new Entry(e.domain);
            c.lastSeen = e.lastSeen;
            c.count = e.count;
            c.lastApp = e.lastApp;
            c.apps.addAll(e.apps);
            out.add(c);
        }
        Collections.reverse(out);
        return out;
    }
}
