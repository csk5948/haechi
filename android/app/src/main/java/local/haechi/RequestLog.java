package local.haechi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory record of the requests HTTPS filtering saw inside browser connections (newest first), so the
 * user can see what a page loads and turn any of it into a rule. Never written to disk; gone when the app
 * process ends. Full addresses can hold personal things (search words, tokens), hence memory only, and it
 * records only after the user presses 녹화 시작 (off by default; a single page can make hundreds of requests).
 */
final class RequestLog {
    private RequestLog() {}

    static final int MAX = 1500;
    static final int PASSED = 0, BLOCKED = 1, STYLED = 2, REWRITTEN = 3, TUNNEL = 4;   // TUNNEL: connection not opened

    /**
     * While recording, keep the browser from answering from its own cache (it would never ask us, so the request
     * would not show): answers get "Cache-Control: no-store" and requests lose If-None-Match / If-Modified-Since.
     */
    static volatile boolean noCache = true;

    static final class Entry {
        final long id = NEXT_ID.incrementAndGet();
        final long time = System.currentTimeMillis();
        volatile String requestHead, responseHead;   // start line + headers (cookies hidden)
        volatile byte[] body;                        // the answer's text as it came (maybe gzip/deflate), or null
        volatile String bodyEncoding, bodyNote;      // Content-Encoding of body; why there is none
        volatile boolean bodyCut;                    // only the first BODY_CAP bytes were kept
        final String url, host, page;         // page: host of the page that asked (or null)
        volatile String rule;                 // what blocked it, or what rewrote it
        final int type;
        volatile int result;
        volatile int status;                  // HTTP status of the answer, 0 = not seen (yet)
        Entry(String url, String host, String page, int type, int result, String rule) {
            this.url = url; this.host = host; this.page = page; this.type = type; this.result = result; this.rule = rule;
        }
    }

    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ArrayDeque<Entry> entries = new ArrayDeque<>();

    // Response texts are kept while recording so the user can read them and pick a pattern. Bounded:
    static final int BODY_CAP = 4 * 1024 * 1024;               // per answer (as received, usually still gzip)
    private static final long BODY_BUDGET = 32L * 1024 * 1024;  // all answers together; oldest dropped first
    private static long bodyTotal;
    private static volatile boolean recording;
    /** Bumped on every add / clear, so the screen only re-filters when something changed. */
    static volatile int version;   // off by default: a page makes hundreds of requests

    static boolean isRecording() { return recording; }

    static synchronized void startNew() {
        entries.clear();
        bodyTotal = 0;
        version++;
        recording = true;
    }

    static void pause() { recording = false; }

    static synchronized void clear() { entries.clear(); bodyTotal = 0; version++; }

    /** Records one request; returns the entry (to fill in the status later) or null when not recording. */
    static Entry add(String url, String host, String page, int type, int result, String rule) {
        if (!recording) return null;
        Entry e = new Entry(url, host, page, type, result, rule);
        synchronized (RequestLog.class) {
            entries.addFirst(e);
            while (entries.size() > MAX) {
                Entry old = entries.removeLast();
                if (old.body != null) bodyTotal -= old.body.length;
            }
            version++;
        }
        return e;
    }

    static synchronized Entry find(long id) {
        for (Entry e : entries) if (e.id == id) return e;
        return null;
    }

    /** Keeps an answer's text on its entry, dropping the oldest kept texts beyond the budget. */
    static synchronized void attach(Entry e, Capture c, String encoding) {
        if (e == null || c == null) return;
        byte[] data = c.bytes();
        e.bodyEncoding = encoding;
        e.bodyCut = c.cut;
        e.body = data;
        bodyTotal += data.length;
        Iterator<Entry> it = entries.descendingIterator();          // oldest first
        while (bodyTotal > BODY_BUDGET && it.hasNext()) {
            Entry old = it.next();
            if (old == e || old.body == null) continue;
            bodyTotal -= old.body.length;
            old.body = null;
            old.bodyNote = "메모리 한도(32MB)를 넘어 지움 · 다시 녹화해서 보세요";
        }
    }

    /** Text worth keeping: pages, JSON, scripts, styles, XML, plain text. */
    static boolean textual(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase(Locale.ROOT);
        return t.contains("text/") || t.contains("json") || t.contains("javascript") || t.contains("ecmascript") || t.contains("xml");
    }

    /** The kept answer as text: unpacked, in its charset (UTF-8 if none given). */
    static String text(Entry e) throws IOException {
        byte[] b = e.body;
        if (b == null) throw new IOException(e.bodyNote == null ? "내용이 없습니다" : e.bodyNote);
        String enc = e.bodyEncoding == null ? "" : e.bodyEncoding.trim().toLowerCase(Locale.ROOT);
        InputStream in = new java.io.ByteArrayInputStream(b);
        if (enc.contains("gzip")) in = new GZIPInputStream(in);
        else if (enc.equals("deflate")) in = new InflaterInputStream(in);
        else if (!enc.isEmpty() && !enc.equals("identity")) throw new IOException("'" + enc + "' 압축이라 풀 수 없습니다");
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length * 4);
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        } catch (java.io.EOFException cutShort) {
            // kept only the first part: show what unpacks
        }
        return new String(out.toByteArray(), charsetOf(headerValue(e.responseHead, "content-type")));
    }

    static String headerValue(String head, String name) {
        if (head == null) return null;
        for (String line : head.split("\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) return line.substring(c + 1).trim();
        }
        return null;
    }

    static Charset charsetOf(String contentType) {
        if (contentType != null) {
            int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String name = contentType.substring(i + 8).split("[;\\s]")[0].replace("\"", "").trim();
                try { return Charset.forName(name); } catch (RuntimeException ignored) {}
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Collects up to BODY_CAP bytes written to it (a copy of what passes by). */
    static final class Capture extends OutputStream {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream(16384);
        boolean cut;
        @Override public void write(int x) { if (b.size() < BODY_CAP) b.write(x); else cut = true; }
        @Override public void write(byte[] x, int off, int len) {
            int room = BODY_CAP - b.size();
            if (len > room) cut = true;
            if (room > 0) b.write(x, off, Math.min(len, room));
        }
        byte[] bytes() { return b.toByteArray(); }
    }

    static synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    /** Short Korean name of a request type (from Sec-Fetch-Dest). */
    static String typeName(int type) {
        switch (type) {
            case AdRules.DOCUMENT: return "페이지";
            case AdRules.SUBDOCUMENT: return "프레임";
            case AdRules.SCRIPT: return "스크립트";
            case AdRules.IMAGE: return "이미지";
            case AdRules.STYLESHEET: return "스타일";
            case AdRules.FONT: return "글꼴";
            case AdRules.MEDIA: return "미디어";
            case AdRules.XHR: return "데이터";
            case AdRules.OBJECT: return "플러그인";
            case AdRules.WEBSOCKET: return "웹소켓";
            case 0: return "알 수 없음";
            default: return "기타";
        }
    }
}
