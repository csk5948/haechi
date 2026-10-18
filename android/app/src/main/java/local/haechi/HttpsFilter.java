package local.haechi;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Process;
import android.system.OsConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS filtering inside {@link SplitProxy}: for a browser's {@code CONNECT host:443}, the relay finishes TLS
 * itself with a certificate for that site signed by the phone's own CA ({@link CertAuthority}), opens its own
 * verified TLS connection to the real site, and in between reads the HTTP/1.1 requests:
 *
 *  - a request an Adblock rule blocks ({@link AdRules}) never leaves the phone (answered 204 / empty page);
 *  - a web page gets a {@code <style>} with the hiding rules for its site, right after {@code <head>}
 *    (gzip/deflate is unpacked for that; brotli is not offered for pages, so sites send gzip).
 *
 * Only browsers that trust user CAs can be filtered, so the relay only takes over connections of known
 * browsers (and of this app's own picker); connections of other apps are passed through untouched. When the
 * owning app cannot be told, a site whose TLS handshake fails is remembered and passed through until the
 * filter restarts. Sites on the allow list are never opened. The upstream connection checks the real
 * certificate with the phone's normal trust store; if that fails the browser gets the original connection
 * and sees the real error itself.
 */
final class HttpsFilter {
    interface Opener { Socket open(String host, int port, OutputStream cout) throws IOException; }

    static final int PASS = 0, HANDLED = 1;

    static final AtomicInteger sessions = new AtomicInteger(), handshakeFails = new AtomicInteger(),
            blockedRequests = new AtomicInteger(), styledPages = new AtomicInteger(), passedApps = new AtomicInteger(),
            rewrittenResponses = new AtomicInteger();
    /** Sites passed through after a failed handshake (the app did not trust our CA). Cleared on restart. */
    static final Set<String> learnedBypass = ConcurrentHashMap.newKeySet();

    /** Browsers that trust certificates the user installed (Firefox does not by default; it gets learned). */
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
            "com.microsoft.emmx", "com.brave.browser", "com.naver.whale", "com.opera.browser", "com.opera.gx",
            "com.vivaldi.browser", "com.kiwibrowser.browser", "org.mozilla.firefox", "org.mozilla.firefox_beta",
            "com.duckduckgo.mobile.android"));

    private static final int HANDSHAKE_TIMEOUT_MS = 15000, IDLE_MS = 60000, IO_MS = 120000, MAX_HEAD = 65536,
            MAX_SNIFF = 65536;

    private final Context context;
    private final CertAuthority ca;
    private final ExecutorService pool;
    private final Opener opener;
    private final ConnectivityManager cm;

    HttpsFilter(Context context, CertAuthority ca, ExecutorService pool, Opener opener) {
        this.context = context.getApplicationContext();
        this.ca = ca;
        this.pool = pool;
        this.opener = opener;
        this.cm = context.getSystemService(ConnectivityManager.class);
    }

    static void resetStats() {
        sessions.set(0); handshakeFails.set(0); blockedRequests.set(0); styledPages.set(0); passedApps.set(0);
        rewrittenResponses.set(0);
        learnedBypass.clear();
    }

    // ---------------------------------------------------------------- taking a CONNECT

    /**
     * Called right after the browser's CONNECT (nothing answered yet). PASS = not ours, relay as usual;
     * HANDLED = the whole connection was served here (both sockets are closed when this returns).
     */
    int handle(Socket client, InputStream in, OutputStream cout, String host, int port) {
        String h = host.toLowerCase(Locale.ROOT);
        if (port != 443) return pass(h, port, "443이 아닌 포트");
        if (isIp(h)) return pass(h, port, "이름 없이 IP 주소로 접속");
        if (learnedBypass.contains(h)) return pass(h, port, "앞서 인증서를 거부해 그냥 통과시키는 사이트");
        if (Lists.isAllowed(h)) return pass(h, port, "허용 목록 사이트");
        String[] app = new String[1];
        Boolean browser = fromBrowser(client, app);
        if (browser == Boolean.FALSE) { passedApps.incrementAndGet(); return pass(h, port, "브라우저가 아닌 앱: " + app[0]); }

        Socket raw = null;
        SSLSocket up = null, down = null;
        try {
            raw = opener.open(h, port, cout);
            if (raw == null) return HANDLED;                         // blocked by DNS / unreachable: already answered
            try {
                up = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, h, port, true);
                up.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
                up.startHandshake();
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(h, up.getSession())) throw new IOException("name");
            } catch (IOException e) {
                close(up); close(raw);                               // bad / odd certificate: let the browser see it
                return pass(h, port, "사이트 인증서를 확인하지 못함 (" + e.getMessage() + ")");
            }
            SSLContext ctx = ca.contextFor(h);
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            cout.flush();
            if (in.available() > 0) return HANDLED;                  // spoke before our answer: give up on this one
            down = (SSLSocket) ctx.getSocketFactory().createSocket(client, h, port, true);
            down.setUseClientMode(false);
            down.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            try {
                down.startHandshake();
            } catch (IOException e) {
                handshakeFails.incrementAndGet();
                if (browser == null) learnedBypass.add(h);            // probably an app that only trusts system CAs
                RequestLog.Entry refused = RequestLog.add("https://" + h + "/", h, null, 0, RequestLog.TUNNEL, null);
                if (refused != null) refused.rule = "브라우저(앱)가 해치 인증서를 거부함" + (browser == null ? " · 다음부터 그냥 통과" : "");
                return HANDLED;
            }
            sessions.incrementAndGet();
            exchange(down, up, h);
        } catch (IOException | GeneralSecurityException | RuntimeException ignored) {
        } finally {
            close(down); close(up); close(raw);
        }
        return HANDLED;
    }

    /** Relayed without opening it; while recording, the request log shows the connection and why. */
    private static int pass(String host, int port, String why) {
        RequestLog.Entry e = RequestLog.add("https://" + host + (port == 443 ? "" : ":" + port) + "/", host, null, 0, RequestLog.TUNNEL, null);
        if (e != null) e.rule = why;
        return PASS;
    }

    /** TRUE = a browser or us, FALSE = another app, null = cannot tell (older Android / lookup refused). */
    private Boolean fromBrowser(Socket client, String[] app) {
        if (Build.VERSION.SDK_INT < 29) return null;
        int uid;
        try {
            uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_TCP,
                    (InetSocketAddress) client.getRemoteSocketAddress(), (InetSocketAddress) client.getLocalSocketAddress());
        } catch (RuntimeException e) {
            return null;
        }
        if (uid == Process.INVALID_UID) return null;
        if (uid == Process.myUid()) return true;                     // the picker's WebView
        PackageManager pm = context.getPackageManager();
        String[] pkgs = pm.getPackagesForUid(uid);
        if (pkgs == null) return null;
        for (String p : pkgs) if (BROWSERS.contains(p)) return true;
        app[0] = pkgs[0];
        try { app[0] = pm.getApplicationInfo(pkgs[0], 0).loadLabel(pm) + " (" + pkgs[0] + ")"; } catch (PackageManager.NameNotFoundException ignored) {}
        return false;
    }

    // ---------------------------------------------------------------- HTTP/1.1 between the two

    private void exchange(SSLSocket down, SSLSocket up, String host) throws IOException {
        BufferedInputStream cin = new BufferedInputStream(down.getInputStream(), 16384);
        OutputStream cout = new BufferedOutputStream(down.getOutputStream(), 16384);
        BufferedInputStream uin = new BufferedInputStream(up.getInputStream(), 16384);
        OutputStream uout = new BufferedOutputStream(up.getOutputStream(), 16384);
        String origin = "https://" + host;
        while (true) {
            down.setSoTimeout(IDLE_MS);
            Head req = Head.read(cin);
            if (req == null) return;
            down.setSoTimeout(IO_MS);
            up.setSoTimeout(IO_MS);
            String[] rl = req.first.split(" ", 3);
            if (rl.length < 3) return;
            String method = rl[0].toUpperCase(Locale.ROOT);
            String url = rl[1].startsWith("/") ? origin + rl[1] : rl[1];
            boolean websocket = "websocket".equalsIgnoreCase(req.get("upgrade"));
            int type = websocket ? AdRules.WEBSOCKET : typeOf(req);
            AdRules rules = AdRules.get(context);

            String from = pageHost(req);
            String rule = rules.blocks(new AdRules.Request(url, host, from, type, thirdParty(req)));
            if (rule != null) {
                blockedRequests.incrementAndGet();
                RequestLog.Entry logged = RequestLog.add(url, host, from, type, RequestLog.BLOCKED, rule);
                if (logged != null) logged.status = type == AdRules.SUBDOCUMENT ? 200 : 204;
                skipBody(cin, req);
                sendBlocked(cout, type, req.wantsClose());
                if (req.wantsClose()) return;
                continue;
            }

            boolean page = "GET".equals(method) && !websocket && (type == AdRules.DOCUMENT || type == AdRules.SUBDOCUMENT
                    || (type == 0 && String.valueOf(req.get("accept")).contains("text/html")));
            String css = page ? rules.css(host) : null;
            List<AdRules.Rewrite> edits = websocket ? java.util.Collections.emptyList()
                    : rules.rewrites(new AdRules.Request(url, host, from, type, thirdParty(req)));
            if (css != null || !edits.isEmpty()) req.set("Accept-Encoding", "gzip, deflate");   // no brotli: we must unpack it
            RequestLog.Entry logged = RequestLog.add(url, host, from, type, RequestLog.PASSED, null);
            if (logged != null) {
                req.set("Accept-Encoding", "gzip, deflate");     // while recording: answers we can unpack and show
                if (RequestLog.noCache) {                         // a full answer, not "304 nothing changed"
                    req.remove("If-None-Match");
                    req.remove("If-Modified-Since");
                }
                logged.requestHead = headText(req);
            }
            if ("100-continue".equalsIgnoreCase(req.get("expect"))) {
                req.remove("Expect");
                cout.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
            }
            uout.write(req.bytes());
            copyBody(cin, uout, req);
            uout.flush();

            Head res;
            while (true) {
                res = Head.read(uin);
                if (res == null) return;
                int code = res.status();
                if (code >= 100 && code < 200 && code != 101) {      // interim answers pass straight through
                    cout.write(res.bytes());
                    cout.flush();
                    continue;
                }
                break;
            }
            int code = res.status();
            if (logged != null) {
                logged.status = code;
                logged.responseHead = headText(res);
                if (RequestLog.noCache) {                         // don't let the browser keep it: next time it asks again
                    res.set("Cache-Control", "no-store");
                    res.remove("Expires");
                    res.remove("ETag");
                    res.remove("Last-Modified");
                }
            }
            if (code == 101) {                                       // WebSocket etc.: raw pipe from now on
                cout.write(res.bytes());
                cout.flush();
                down.setSoTimeout(0);
                up.setSoTimeout(0);
                pipe(cin, cout, uin, uout, down, up);
                return;
            }
            boolean noBody = "HEAD".equals(method) || code == 204 || code == 304;
            boolean close = req.wantsClose() || res.wantsClose();
            RequestLog.Capture keep = null;
            if (logged != null) {
                if (noBody || contentLength(res) == 0) logged.bodyNote = "내용 없는 응답";
                else if (!RequestLog.textual(res.get("content-type"))) logged.bodyNote = "글자가 아닌 응답 (" + res.get("content-type") + ")";
                else keep = new RequestLog.Capture();
            }
            if (!noBody && !edits.isEmpty() && code >= 200 && code < 300 && contentLength(res) != 0 && rewritable(res)) {
                int[] changed = new int[1];
                String sheet = css != null && code == 200 && styleable(res) ? css : null;
                close |= sendRewritten(uin, cout, res, edits, sheet, changed, keep);
                RequestLog.attach(logged, keep, null);
                if (changed[0] > 0) {
                    rewrittenResponses.incrementAndGet();
                    if (logged != null) {
                        logged.result = RequestLog.REWRITTEN;
                        logged.rule = changed[0] + "곳 고침 · " + edits.get(0).rule + (edits.size() > 1 ? " 외 " + (edits.size() - 1) + "개" : "");
                    }
                } else if (logged != null) {
                    if (sheet != null) logged.result = RequestLog.STYLED;
                    logged.rule = "고칠 곳 없음 · " + edits.get(0).rule;    // a rule applied, its pattern wasn't in there
                }
                if (sheet != null) styledPages.incrementAndGet();
                cout.flush();
                if (close) return;
                continue;
            }
            if (!edits.isEmpty() && !noBody && logged != null)            // why a matching rule could not edit it
                logged.rule = "못 고침 (HTTP " + code + " · " + res.get("content-type") + " · 압축 " + res.get("content-encoding") + ")";
            if (!noBody && css != null && code == 200 && contentLength(res) != 0 && styleable(res)) {
                close |= sendStyled(uin, cout, res, css, keep);
                RequestLog.attach(logged, keep, null);
                styledPages.incrementAndGet();
                if (logged != null) logged.result = RequestLog.STYLED;
            } else {
                cout.write(res.bytes());
                String enc = res.get("content-encoding");
                if (!noBody) close |= copyResponseBody(uin, cout, res, keep);
                RequestLog.attach(logged, keep, enc);
            }
            cout.flush();
            if (close) return;
        }
    }

    /** Start line + headers for the request log; cookie and login values are not kept. */
    private static String headText(Head h) {
        StringBuilder sb = new StringBuilder(h.first).append('\n');
        for (String[] kv : h.headers) {
            String n = kv[0].toLowerCase(Locale.ROOT);
            boolean secret = n.equals("cookie") || n.equals("set-cookie") || n.equals("authorization") || n.equals("proxy-authorization");
            sb.append(kv[0]).append(": ").append(secret ? "(숨김)" : kv[1]).append('\n');
        }
        return sb.length() > 16384 ? sb.substring(0, 16384) : sb.toString();
    }

    // ---------------------------------------------------------------- requests

    /** Request type from Chrome's Sec-Fetch-Dest header (0 = not sent). */
    private static int typeOf(Head req) {
        String d = req.get("sec-fetch-dest");
        if (d == null) return 0;
        switch (d.trim().toLowerCase(Locale.ROOT)) {
            case "document": return AdRules.DOCUMENT;
            case "iframe": case "frame": case "fencedframe": return AdRules.SUBDOCUMENT;
            case "script": case "worker": case "sharedworker": case "serviceworker": return AdRules.SCRIPT;
            case "image": return AdRules.IMAGE;
            case "style": return AdRules.STYLESHEET;
            case "font": return AdRules.FONT;
            case "audio": case "video": case "track": return AdRules.MEDIA;
            case "object": case "embed": return AdRules.OBJECT;
            case "empty": return AdRules.XHR;
            default: return AdRules.OTHER;
        }
    }

    /** The page that made the request, from Referer (or Origin). */
    private static String pageHost(Head req) {
        String r = req.get("referer");
        if (r == null) r = req.get("origin");
        if (r == null) return null;
        int s = r.indexOf("://");
        if (s < 0) return null;
        int e = s + 3;
        while (e < r.length() && "/?#:".indexOf(r.charAt(e)) < 0) e++;
        String h = r.substring(s + 3, e).toLowerCase(Locale.ROOT);
        int at = h.lastIndexOf('@');
        return h.isEmpty() ? null : at >= 0 ? h.substring(at + 1) : h;
    }

    private static Boolean thirdParty(Head req) {
        String s = req.get("sec-fetch-site");
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.equals("cross-site")) return true;
        return s.equals("same-origin") || s.equals("same-site") || s.equals("none") ? Boolean.FALSE : null;
    }

    /** What a blocked request gets: an empty page for frames (no error box), 204 otherwise. */
    private static void sendBlocked(OutputStream out, int type, boolean close) throws IOException {
        String c = close ? "Connection: close\r\n" : "";
        String r = type == AdRules.SUBDOCUMENT
                ? "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 0\r\nCache-Control: no-store\r\n" + c + "\r\n"
                : "HTTP/1.1 204 No Content\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n" + c + "\r\n";
        out.write(r.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    // ---------------------------------------------------------------- bodies

    private static long contentLength(Head h) {
        String v = h.get("content-length");
        if (v == null) return -1;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private static boolean chunked(Head h) {
        String te = h.get("transfer-encoding");
        return te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
    }

    /** Request body as framed (a request without length has none). */
    private static void copyBody(InputStream in, OutputStream out, Head req) throws IOException {
        if (chunked(req)) copyChunked(in, out, null);
        else copyN(in, out, Math.max(0, contentLength(req)), null);
    }

    /** Reads away a blocked request's body so the connection can carry the next request. */
    private static void skipBody(InputStream in, Head req) throws IOException {
        copyBody(in, new OutputStream() { @Override public void write(int b) {} @Override public void write(byte[] b, int o, int l) {} }, req);
    }

    /** Response body as framed (its data also to {@code tee}, if any); true if it ran until the connection closed. */
    private static boolean copyResponseBody(InputStream in, OutputStream out, Head res, OutputStream tee) throws IOException {
        if (chunked(res)) { copyChunked(in, out, tee); return false; }
        long n = contentLength(res);
        if (n >= 0) { copyN(in, out, n, tee); return false; }
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) >= 0) {
            out.write(buf, 0, r);
            out.flush();
            if (tee != null) tee.write(buf, 0, r);
        }
        return true;
    }

    private static void copyN(InputStream in, OutputStream out, long n, OutputStream tee) throws IOException {
        byte[] buf = new byte[16384];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new EOFException();
            out.write(buf, 0, r);
            if (tee != null) tee.write(buf, 0, r);
            n -= r;
            if (in.available() == 0) out.flush();                    // keep streaming responses moving
        }
    }

    /** Relays a chunked body exactly as it is (sizes, data, trailers); only the data goes to {@code tee}. */
    private static void copyChunked(InputStream in, OutputStream out, OutputStream tee) throws IOException {
        while (true) {
            String line = readLine(in);
            out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            long size = chunkSize(line);
            if (size == 0) {
                String t;
                do {
                    t = readLine(in);
                    out.write((t + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                } while (!t.isEmpty());
                return;
            }
            copyN(in, out, size, tee);
            String crlf = readLine(in);
            out.write((crlf + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            if (in.available() == 0) out.flush();
        }
    }

    private static long chunkSize(String line) throws IOException {
        int semi = line.indexOf(';');
        String hex = (semi < 0 ? line : line.substring(0, semi)).trim();
        try { return Long.parseLong(hex, 16); } catch (NumberFormatException e) { throw new IOException("chunk " + line); }
    }

    // ---------------------------------------------------------------- pages: insert the style sheet

    private static boolean styleable(Head res) {
        String type = String.valueOf(res.get("content-type")).toLowerCase(Locale.ROOT);
        if (!type.contains("text/html") || type.contains("utf-16")) return false;
        String enc = res.get("content-encoding");
        if (enc == null) return true;
        enc = enc.trim().toLowerCase(Locale.ROOT);
        return enc.isEmpty() || enc.equals("identity") || enc.equals("gzip") || enc.equals("x-gzip") || enc.equals("deflate");
    }

    /** Text we can edit with $replace: pages, JSON, scripts, XML; packed with gzip/deflate at most. */
    private static boolean rewritable(Head res) {
        String type = String.valueOf(res.get("content-type")).toLowerCase(Locale.ROOT);
        if (type.contains("utf-16")) return false;
        if (!type.contains("text/") && !type.contains("json") && !type.contains("javascript")
                && !type.contains("ecmascript") && !type.contains("xml")) return false;
        String enc = res.get("content-encoding");
        if (enc == null) return true;
        enc = enc.trim().toLowerCase(Locale.ROOT);
        return enc.isEmpty() || enc.equals("identity") || enc.equals("gzip") || enc.equals("x-gzip") || enc.equals("deflate");
    }

    private static final int MAX_REWRITE = 16 * 1024 * 1024;

    /**
     * Reads the whole answer, unpacks it, applies the $replace edits (and the page's style, if any) and sends it
     * with a Content-Length. {@code changed[0]} = number of replaced matches. True if the upstream must close.
     * An answer over 16 MB is passed on unedited.
     */
    private static boolean sendRewritten(InputStream uin, OutputStream cout, Head res, List<AdRules.Rewrite> edits,
                                         String css, int[] changed, OutputStream keep) throws IOException {
        boolean untilClose = !chunked(res) && contentLength(res) < 0;
        InputStream body = chunked(res) ? new ChunkedIn(uin) : untilClose ? uin : new LimitedIn(uin, contentLength(res));
        String enc = String.valueOf(res.get("content-encoding")).trim().toLowerCase(Locale.ROOT);
        InputStream text = enc.contains("gzip") ? new GZIPInputStream(body, 16384)
                : enc.equals("deflate") ? new InflaterInputStream(body) : body;
        for (String drop : new String[]{"Content-Length", "Content-Encoding", "Transfer-Encoding", "Content-MD5"}) res.remove(drop);

        ByteArrayOutputStream all = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[16384];
        int n;
        while ((n = text.read(buf)) >= 0) {
            all.write(buf, 0, n);
            if (all.size() > MAX_REWRITE) {                          // too big to hold: pass it on as it is
                res.set("Transfer-Encoding", "chunked");
                cout.write(res.bytes());
                ChunkedOut out = new ChunkedOut(cout);
                out.write(all.toByteArray());
                while ((n = text.read(buf)) >= 0) out.write(buf, 0, n);
                while (body.read(buf) >= 0) {}
                out.finish();
                return untilClose;
            }
        }
        while (body.read(buf) >= 0) {}                               // rest of the framing (gzip may stop early)
        if (keep != null) all.writeTo(keep);                         // the request log shows the answer as it came

        java.nio.charset.Charset cs = charsetOf(res);
        String s = new String(all.toByteArray(), cs);
        for (AdRules.Rewrite e : edits) {
            java.util.regex.Matcher m = e.find.matcher(s);
            StringBuffer out = new StringBuffer(s.length());
            int count = 0;
            while (m.find()) {
                try {
                    m.appendReplacement(out, e.with);
                } catch (RuntimeException bad) {                     // "$9" without such a group, …
                    m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(e.with));
                }
                count++;
                if (!e.global) break;
            }
            if (count == 0) continue;
            m.appendTail(out);
            s = out.toString();
            changed[0] += count;
        }
        byte[] data = s.getBytes(cs);
        if (css != null) {
            int at = insertAt(data, true);
            String nonce = allowStyle(res, css);
            byte[] tag = ("<style id=\"haechi\"" + (nonce == null ? "" : " nonce=\"" + nonce + "\"") + ">" + css + "</style>")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] joined = new byte[data.length + tag.length];
            System.arraycopy(data, 0, joined, 0, at);
            System.arraycopy(tag, 0, joined, at, tag.length);
            System.arraycopy(data, at, joined, at + tag.length, data.length - at);
            data = joined;
        }
        res.set("Content-Length", String.valueOf(data.length));
        cout.write(res.bytes());
        cout.write(data);
        return untilClose;
    }

    /** Charset from Content-Type, UTF-8 when none (or unknown). */
    private static java.nio.charset.Charset charsetOf(Head res) {
        String type = String.valueOf(res.get("content-type"));
        int i = type.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i >= 0) {
            String name = type.substring(i + 8).split("[;\\s]")[0].replace("\"", "").trim();
            try { return java.nio.charset.Charset.forName(name); } catch (RuntimeException ignored) {}
        }
        return StandardCharsets.UTF_8;
    }

    /** Sends the page unpacked and chunked, with our style after <head>. True if the upstream must close. */
    private static boolean sendStyled(InputStream uin, OutputStream cout, Head res, String css, OutputStream keep) throws IOException {
        boolean untilClose = !chunked(res) && contentLength(res) < 0;
        InputStream body = chunked(res) ? new ChunkedIn(uin) : untilClose ? uin : new LimitedIn(uin, contentLength(res));
        String enc = String.valueOf(res.get("content-encoding")).trim().toLowerCase(Locale.ROOT);
        InputStream page = enc.contains("gzip") ? new GZIPInputStream(body, 16384)
                : enc.equals("deflate") ? new InflaterInputStream(body) : body;

        // read until <head …> is seen (or 64 KB), then insert
        ByteArrayOutputStream start = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int at = -1, n;
        while (start.size() < MAX_SNIFF && (n = page.read(buf)) >= 0) {
            start.write(buf, 0, n);
            at = insertAt(start.toByteArray(), false);
            if (at >= 0) break;
        }
        byte[] first = start.toByteArray();
        if (at < 0) at = insertAt(first, true);

        for (String drop : new String[]{"Content-Length", "Content-Encoding", "Transfer-Encoding", "Content-MD5"}) res.remove(drop);
        res.set("Transfer-Encoding", "chunked");
        String nonce = allowStyle(res, css);
        cout.write(res.bytes());
        ChunkedOut out = new ChunkedOut(cout);
        String tag = "<style id=\"haechi\"" + (nonce == null ? "" : " nonce=\"" + nonce + "\"") + ">" + css + "</style>";
        if (keep != null) keep.write(first, 0, first.length);
        out.write(first, 0, at);
        out.write(tag.getBytes(StandardCharsets.UTF_8));
        out.write(first, at, first.length - at);
        cout.flush();
        while ((n = page.read(buf)) >= 0) {
            out.write(buf, 0, n);
            if (keep != null) keep.write(buf, 0, n);
            if (uin.available() == 0) cout.flush();
        }
        while (body.read(buf) >= 0) {}                               // the rest of the framing (gzip may stop early)
        out.finish();
        return untilClose;
    }

    /**
     * Where the style goes: after "<head …>"; when there is none (only on the last try), after the doctype,
     * or at the very start. -1 = not seen yet.
     */
    static int insertAt(byte[] b, boolean last) {
        int head = find(b, "<head", true);
        if (head >= 0) {
            int gt = indexOf(b, (byte) '>', head);
            return gt < 0 ? (last ? b.length : -1) : gt + 1;
        }
        if (!last) return -1;
        int doctype = find(b, "<!doctype", false);
        if (doctype >= 0) {
            int gt = indexOf(b, (byte) '>', doctype);
            return gt < 0 ? b.length : gt + 1;
        }
        return 0;
    }

    /** Case-insensitive search; with {@code tagEnd}, the match must be followed by '>', '/' or whitespace. */
    private static int find(byte[] b, String what, boolean tagEnd) {
        int m = what.length();
        outer:
        for (int i = 0; i + m <= b.length; i++) {
            for (int k = 0; k < m; k++) {
                int c = b[i + k];
                if (c >= 'A' && c <= 'Z') c += 32;
                if (c != what.charAt(k)) continue outer;
            }
            if (!tagEnd) return i;
            if (i + m >= b.length) return -1;                        // can't tell yet
            byte next = b[i + m];
            if (next == '>' || next == '/' || next == ' ' || next == '\t' || next == '\n' || next == '\r') return i;
        }
        return -1;
    }

    private static int indexOf(byte[] b, byte v, int from) {
        for (int i = from; i < b.length; i++) if (b[i] == v) return i;
        return -1;
    }

    /**
     * A strict Content-Security-Policy would refuse our inline style. For each policy that does not already allow
     * inline styles, add our style's hash to the directive that governs <style> elements. Returns a nonce to
     * put on the tag when a policy uses one (some browsers want it even with the hash).
     */
    private static String allowStyle(Head res, String css) {
        String hash;
        try {
            hash = "'sha256-" + android.util.Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(css.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP) + "'";
        } catch (GeneralSecurityException e) {
            return null;
        }
        String nonce = null;
        for (String[] h : res.headers) {
            if (!h[0].equalsIgnoreCase("content-security-policy")) continue;
            String[] dirs = h[1].split(";");
            int elem = -1, style = -1, def = -1;
            for (int i = 0; i < dirs.length; i++) {
                String d = dirs[i].trim().toLowerCase(Locale.ROOT);
                if (d.startsWith("style-src-elem ") || d.equals("style-src-elem")) elem = i;
                else if (d.startsWith("style-src ") || d.equals("style-src")) style = i;
                else if (d.startsWith("default-src ") || d.equals("default-src")) def = i;
            }
            int gov = elem >= 0 ? elem : style >= 0 ? style : def;
            if (gov < 0) continue;
            String d = dirs[gov].trim();
            String low = d.toLowerCase(Locale.ROOT);
            boolean restricted = !low.contains("'unsafe-inline'") || low.contains("'nonce-") || low.contains("'sha");
            if (!restricted) continue;
            int ni = low.indexOf("'nonce-");
            if (ni >= 0 && nonce == null) {
                int end = d.indexOf('\'', ni + 7);
                if (end > ni) nonce = d.substring(ni + 7, end);
            }
            if (gov == def) {                                        // no style directive: add one from default-src
                String values = d.substring("default-src".length());
                dirs = Arrays.copyOf(dirs, dirs.length + 1);
                dirs[dirs.length - 1] = " style-src-elem" + values + " " + hash;
            } else {
                dirs[gov] = " " + d + " " + hash;
            }
            h[1] = String.join(";", dirs).trim();
        }
        return nonce;
    }

    // ---------------------------------------------------------------- plumbing

    private void pipe(InputStream cin, OutputStream cout, InputStream uin, OutputStream uout, Socket a, Socket b) {
        try {
            pool.execute(() -> { pump(uin, cout); close(a); close(b); });
        } catch (RejectedExecutionException e) {
            return;
        }
        pump(cin, uout);
        close(a);
        close(b);
    }

    private static void pump(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) { out.write(buf, 0, n); out.flush(); }
        } catch (IOException ignored) {}
    }

    private static boolean isIp(String h) {
        return h.indexOf(':') >= 0 || h.matches("[0-9.]+");
    }

    private static void close(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) {}
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            if (sb.length() > 8192) throw new IOException("line too long");
            sb.append((char) c);
        }
        throw new EOFException();
    }

    /** Start line + headers, in order, case kept. */
    static final class Head {
        String first;
        final List<String[]> headers = new ArrayList<>();

        static Head read(InputStream in) throws IOException {
            Head h = new Head();
            int total = 0;
            String line;
            try {
                do { line = readLine(in); } while (line.isEmpty() && h.first == null && total++ < 4);   // stray CRLFs
            } catch (EOFException e) {
                return null;
            }
            h.first = line;
            while (!(line = readLine(in)).isEmpty()) {
                total += line.length();
                if (total > MAX_HEAD) throw new IOException("head too long");
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                h.headers.add(new String[]{line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
            }
            return h;
        }

        String get(String name) {
            for (String[] kv : headers) if (kv[0].equalsIgnoreCase(name)) return kv[1];
            return null;
        }

        void set(String name, String value) {
            remove(name);
            headers.add(new String[]{name, value});
        }

        void remove(String name) {
            headers.removeIf(kv -> kv[0].equalsIgnoreCase(name));
        }

        int status() {
            String[] p = first.split(" ", 3);
            try { return p.length > 1 ? Integer.parseInt(p[1]) : 0; } catch (NumberFormatException e) { return 0; }
        }

        boolean wantsClose() {
            String c = get("connection");
            if (c != null && c.toLowerCase(Locale.ROOT).contains("close")) return true;
            return first.contains("HTTP/1.0") && (c == null || !c.toLowerCase(Locale.ROOT).contains("keep-alive"));
        }

        byte[] bytes() {
            StringBuilder sb = new StringBuilder(first).append("\r\n");
            for (String[] kv : headers) sb.append(kv[0]).append(": ").append(kv[1]).append("\r\n");
            return sb.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    /** Body of a chunked message, unwrapped; ends at the last chunk (trailers read away). */
    private static final class ChunkedIn extends InputStream {
        private final InputStream in;
        private long left;
        private boolean done;

        ChunkedIn(InputStream in) { this.in = in; }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (done) return -1;
            if (left == 0) {
                left = chunkSize(readLine(in));
                if (left == 0) {
                    while (!readLine(in).isEmpty()) {}
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException();
            left -= n;
            if (left == 0) readLine(in);                             // CRLF after the data
            return n;
        }

        @Override public int available() throws IOException { return done ? 0 : (int) Math.min(left, in.available()); }
    }

    private static final class LimitedIn extends FilterInputStream {
        private long left;
        LimitedIn(InputStream in, long n) { super(in); left = n; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int c = in.read();
            if (c < 0) throw new EOFException();
            left--;
            return c;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException();
            left -= n;
            return n;
        }
        @Override public int available() throws IOException { return (int) Math.min(left, in.available()); }
    }

    private static final class ChunkedOut {
        private final OutputStream out;
        ChunkedOut(OutputStream out) { this.out = out; }
        void write(byte[] b) throws IOException { write(b, 0, b.length); }
        void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(b, off, len);
            out.write('\r');
            out.write('\n');
        }
        void finish() throws IOException { out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1)); }
    }
}
