package local.haechi;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny HTTP proxy on 127.0.0.1 that the VPN hands to browsers ({@code VpnService.Builder.setHttpProxy}).
 *
 *  - HTTPS ({@code CONNECT host:port}): connects to the site, answers 200, then reads the browser's
 *    first TLS record (the ClientHello, which carries the site name in clear text) and sends it as
 *    TWO TCP segments, cut in the middle of the name. A filter that only looks at the first segment
 *    no longer sees a whole name. Everything after that is copied unchanged; nothing is decrypted.
 *  - Plain HTTP: the request line is rewritten to origin form and the rest is relayed.
 *
 * The site name is looked up with the normal system resolver, i.e. through this app's own DNS
 * filter, so blocked domains (answered 0.0.0.0 / ::) are refused here with 403 as well.
 *
 * With HTTPS filtering on, a browser's CONNECT is first offered to {@link HttpsFilter}; what it does not
 * take is relayed as above (split only if SNI 쪼개기 is on too).
 */
final class SplitProxy {
    interface Protector { boolean protect(Socket s); }

    static final AtomicInteger connections = new AtomicInteger();
    static final AtomicInteger splits = new AtomicInteger();
    static final AtomicInteger splitFails = new AtomicInteger();   // split sent, but the site never answered

    /** 0 = TCP split; 1 = TCP split + short pause; 2 = TLS record split + TCP split + pause. */
    static volatile int mode = 1;
    private static final int PAUSE_MS = 40;

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int FIRST_BYTES_TIMEOUT_MS = 15000;
    private static final int MAX_HEAD = 16384;

    private final Protector protector;
    private final boolean split;
    private volatile HttpsFilter filter;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(0, 512, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private volatile ServerSocket server;

    SplitProxy(Protector protector, boolean split) {
        this.protector = protector;
        this.split = split;
    }

    /** Turns on HTTPS filtering for new connections. */
    void setFilter(android.content.Context c, CertAuthority ca) {
        filter = new HttpsFilter(c, ca, pool, this::open);
    }

    /** Port of the running relay (0 = not running); the picker uses it to check its own proxy. */
    static volatile int activePort;

    /** Starts listening on a free loopback port and returns it, or -1 on failure. */
    int start() {
        try {
            ServerSocket s = new ServerSocket(0, 64, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            server = s;
            Thread t = new Thread(() -> acceptLoop(s), "split-proxy");
            t.setDaemon(true);
            t.start();
            activePort = s.getLocalPort();
            return activePort;
        } catch (IOException e) {
            return -1;
        }
    }

    void stop() {
        activePort = 0;
        ServerSocket s = server;
        server = null;
        if (s != null) try { s.close(); } catch (IOException ignored) {}
        for (Socket k : open) close(k);
        pool.shutdownNow();
    }

    private void acceptLoop(ServerSocket s) {
        while (server == s) {
            try {
                Socket c = s.accept();
                c.setTcpNoDelay(true);
                open.add(c);
                try {
                    pool.execute(() -> serve(c));
                } catch (RejectedExecutionException e) {
                    close(c);
                }
            } catch (IOException e) {
                if (server != s) return;   // closed by stop()
            }
        }
    }

    // ---------------------------------------------------------------- one browser connection

    private void serve(Socket client) {
        Socket up = null;
        boolean piping = false, split = false;
        try {
            client.setSoTimeout(FIRST_BYTES_TIMEOUT_MS);
            BufferedInputStream in = new BufferedInputStream(client.getInputStream(), 8192);
            OutputStream cout = client.getOutputStream();
            String head = readHead(in);
            String[] lines = head == null ? new String[0] : head.split("\r\n");
            String[] rl = lines.length == 0 ? new String[0] : lines[0].split(" ", 3);
            if (rl.length < 3) { reply(cout, "400 Bad Request"); return; }
            connections.incrementAndGet();

            if (rl[0].equalsIgnoreCase("CONNECT")) {
                String[] hp = hostPort(rl[1], 443);
                if (hp == null) { reply(cout, "400 Bad Request"); return; }
                HttpsFilter f = filter;
                if (f != null && f.handle(client, in, cout, hp[0], Integer.parseInt(hp[1])) == HttpsFilter.HANDLED) return;
                up = open(hp[0], Integer.parseInt(hp[1]), cout);
                if (up == null) return;
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                if (this.split) split = firstFlight(in, up.getOutputStream());
            } else {
                String target = rl[1];
                if (!target.regionMatches(true, 0, "http://", 0, 7)) { reply(cout, "400 Bad Request"); return; }
                int slash = target.indexOf('/', 7);
                String authority = slash < 0 ? target.substring(7) : target.substring(7, slash);
                String path = slash < 0 ? "/" : target.substring(slash);
                String[] hp = hostPort(authority, 80);
                if (hp == null) { reply(cout, "400 Bad Request"); return; }
                up = open(hp[0], Integer.parseInt(hp[1]), cout);
                if (up == null) return;
                StringBuilder req = new StringBuilder(rl[0]).append(' ').append(path).append(' ').append(rl[2]).append("\r\n");
                for (int i = 1; i < lines.length; i++) {
                    String l = lines[i].toLowerCase();
                    if (l.startsWith("proxy-") || l.startsWith("connection:") || l.startsWith("keep-alive:")) continue;
                    req.append(lines[i]).append("\r\n");
                }
                req.append("Connection: close\r\n\r\n");
                up.getOutputStream().write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            }

            client.setSoTimeout(0);
            piping = true;
            pipe(client, in, up, split);
        } catch (IOException | RuntimeException e) {
            // reset / timeout / bad request: just drop the connection
        } finally {
            if (!piping) { close(client); close(up); }
        }
    }

    /** Connects to the site; on failure answers the browser (403 blocked, 502 otherwise) and returns null. */
    private Socket open(String host, int port, OutputStream cout) throws IOException {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (IOException e) {
            reply(cout, "502 Bad Gateway");
            return null;
        }
        boolean allBlank = true;
        for (InetAddress a : addrs) if (!a.isAnyLocalAddress()) allBlank = false;
        if (allBlank) { reply(cout, "403 Blocked"); return null; }   // our DNS filter said 0.0.0.0 / ::
        for (InetAddress a : addrs) {
            if (a.isAnyLocalAddress()) continue;
            Socket s = new Socket();
            open.add(s);
            try {
                s.setTcpNoDelay(true);
                protector.protect(s);
                s.connect(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                close(s);
            }
        }
        reply(cout, "502 Bad Gateway");
        return null;
    }

    // ---------------------------------------------------------------- the split

    /**
     * Reads the browser's first TLS record and writes it upstream in two pieces. Anything that
     * does not look like TLS is passed on untouched.
     */
    private boolean firstFlight(InputStream in, OutputStream out) throws IOException {
        int b = in.read();
        if (b < 0) return false;
        if (b != 0x16) {                       // not a TLS handshake record
            out.write(b);
            out.flush();
            return false;
        }
        byte[] hdr = new byte[5];
        hdr[0] = (byte) b;
        readFully(in, hdr, 1, 4);
        int len = ((hdr[3] & 0xff) << 8) | (hdr[4] & 0xff);
        if (len == 0 || len > 16640) {
            out.write(hdr);
            out.flush();
            return false;
        }
        byte[] rec = new byte[5 + len];
        System.arraycopy(hdr, 0, rec, 0, 5);
        readFully(in, rec, 5, len);

        int cut = sniMiddle(rec);
        if (cut <= 0 || cut >= rec.length) cut = Math.min(3, rec.length - 1);   // unknown layout: still split
        int m = mode;
        if (m >= 2) {
            // Cut the handshake into two TLS records (legal: a handshake message may span records),
            // so a filter that reassembles TCP still sees only half a name per record.
            int k = cut > 5 ? cut - 5 : len / 2;
            if (k < 1 || k >= len) k = Math.max(1, len / 2);
            byte[] one = {rec[0], rec[1], rec[2], (byte) (k >> 8), (byte) k};
            byte[] two = {rec[0], rec[1], rec[2], (byte) ((len - k) >> 8), (byte) (len - k)};
            out.write(one);
            out.write(rec, 5, k);
            out.flush();
            pause();
            out.write(two);
            out.write(rec, 5 + k, len - k);
            out.flush();
        } else {
            out.write(rec, 0, cut);
            out.flush();                       // TCP_NODELAY: leaves as its own segment
            if (m >= 1) pause();
            out.write(rec, cut, rec.length - cut);
            out.flush();
        }
        splits.incrementAndGet();
        return true;
    }

    /** Gives the first piece time to leave the phone as its own packet before the second one is sent. */
    private static void pause() {
        try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Offset in the middle of the server name inside a ClientHello record, or -1 if there is none. */
    static int sniMiddle(byte[] r) {
        try {
            if (r.length < 44 || r[5] != 1) return -1;              // handshake type 1 = ClientHello
            int p = 5 + 4 + 2 + 32;                                  // record hdr, handshake hdr, version, random
            p += 1 + (r[p] & 0xff);                                  // session id
            p += 2 + (((r[p] & 0xff) << 8) | (r[p + 1] & 0xff));     // cipher suites
            p += 1 + (r[p] & 0xff);                                  // compression methods
            int end = p + 2 + (((r[p] & 0xff) << 8) | (r[p + 1] & 0xff));
            p += 2;
            end = Math.min(end, r.length);
            while (p + 4 <= end) {
                int type = ((r[p] & 0xff) << 8) | (r[p + 1] & 0xff);
                int elen = ((r[p + 2] & 0xff) << 8) | (r[p + 3] & 0xff);
                p += 4;
                if (type == 0) {                                     // server_name
                    int nameLen = ((r[p + 3] & 0xff) << 8) | (r[p + 4] & 0xff);
                    int nameAt = p + 5;                              // list len(2) + name type(1) + name len(2)
                    if (nameLen < 2 || nameAt + nameLen > r.length) return -1;
                    return nameAt + nameLen / 2;
                }
                p += elen;
            }
        } catch (ArrayIndexOutOfBoundsException ignored) {}
        return -1;
    }

    // ---------------------------------------------------------------- plumbing

    /** Copies both directions until both ended; the last one to finish closes both sockets. */
    private void pipe(Socket client, InputStream cin, Socket up, boolean split) {
        AtomicInteger done = new AtomicInteger();
        try {
            InputStream uin = up.getInputStream();
            pool.execute(() -> copy(uin, client, done, client, up, split));
        } catch (IOException | RejectedExecutionException e) {
            close(client); close(up);
            return;
        }
        copy(cin, up, done, client, up, false);
    }

    private void copy(InputStream from, Socket to, AtomicInteger done, Socket a, Socket b, boolean watch) {
        byte[] buf = new byte[16384];
        long total = 0;
        try {
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = from.read(buf)) >= 0) {
                total += n;
                out.write(buf, 0, n);
                out.flush();
            }
            try { to.shutdownOutput(); } catch (IOException ignored) {}   // pass on the EOF, keep the other way open
        } catch (IOException e) {
            done.set(2);                                                  // reset: tear both down now
        }
        if (watch && total == 0) splitFails.incrementAndGet();           // site hung up without a single byte
        if (done.incrementAndGet() >= 2) { close(a); close(b); }
    }

    private void close(Socket s) {
        if (s == null) return;
        open.remove(s);
        try { s.close(); } catch (IOException ignored) {}
    }

    private static void reply(OutputStream out, String status) {
        try {
            out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {}
    }

    /** Request line + headers up to the blank line, or null if too long / closed early. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(512);
        int state = 0;                                  // progress through \r\n\r\n
        while (bo.size() < MAX_HEAD) {
            int c = in.read();
            if (c < 0) return null;
            bo.write(c);
            if (c == (state % 2 == 0 ? '\r' : '\n')) {
                if (++state == 4) {
                    String s = bo.toString("ISO-8859-1");
                    return s.substring(0, s.length() - 4);
                }
            } else {
                state = c == '\r' ? 1 : 0;
            }
        }
        return null;
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(b, off, len);
            if (n < 0) throw new EOFException();
            off += n;
            len -= n;
        }
    }

    /** "host", "host:port", "[v6]:port" -> {host, port}; null if malformed. */
    private static String[] hostPort(String s, int defPort) {
        String host = s;
        String port = String.valueOf(defPort);
        if (s.startsWith("[")) {
            int e = s.indexOf(']');
            if (e < 0) return null;
            host = s.substring(1, e);
            if (s.length() > e + 1) {
                if (s.charAt(e + 1) != ':') return null;
                port = s.substring(e + 2);
            }
        } else {
            int c = s.lastIndexOf(':');
            if (c >= 0) { host = s.substring(0, c); port = s.substring(c + 1); }
        }
        try {
            int p = Integer.parseInt(port);
            if (host.isEmpty() || p < 1 || p > 65535) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return new String[]{host, port};
    }
}
