package local.adblock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/**
 * DNS-over-HTTPS (RFC 8484) exchange over a connection the caller opened. Plain java.net only,
 * so it can also be tested on a desktop JVM.
 *
 * The URLs use IP literals (https://1.1.1.1/dns-query, https://8.8.8.8/dns-query): both
 * certificates contain those IPs, so no DNS lookup is needed to reach the DoH server.
 */
final class Doh {
    private Doh() {}

    static final String CLOUDFLARE = "https://1.1.1.1/dns-query";
    static final String GOOGLE = "https://8.8.8.8/dns-query";
    /** Same servers over IPv6 (certificates list these too) — Korean 5G is mostly IPv6. */
    static final String CLOUDFLARE_V6 = "https://[2606:4700:4700::1111]/dns-query";
    static final String GOOGLE_V6 = "https://[2001:4860:4860::8888]/dns-query";
    static final int TIMEOUT_MS = 2500;

    /** IPv4 and IPv6 address of the selected provider; the caller tries the last one that worked first. */
    static String[] endpoints(String url) {
        if (GOOGLE.equals(url)) return new String[]{GOOGLE, GOOGLE_V6};
        return new String[]{CLOUDFLARE, CLOUDFLARE_V6};
    }
    private static final int MAX_REPLY = 65535;

    /**
     * POSTs the DNS message {@code msg[off..off+len)} and returns the raw DNS reply.
     * The request is sent with ID 0 (RFC 8484 §4.1, cache friendly); the original ID is put
     * back into the reply. Throws IOException on any network / HTTP / format problem.
     */
    static byte[] exchange(HttpURLConnection c, byte[] msg, int off, int len) throws IOException {
        if (len < 12) throw new IOException("short query");
        byte[] body = new byte[len];
        System.arraycopy(msg, off, body, 0, len);
        byte id0 = body[0], id1 = body[1];
        body[0] = 0;
        body[1] = 0;
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/dns-message");
            c.setRequestProperty("Accept", "application/dns-message");
            c.setFixedLengthStreamingMode(len);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                drain(c.getErrorStream());   // read it fully so the connection can be reused
                throw new IOException("HTTP " + code);
            }
            byte[] reply;
            try (InputStream in = c.getInputStream()) {
                reply = readAll(in);
            }
            if (reply.length < 12) throw new IOException("short reply");
            reply[0] = id0;                  // restore the asking app's query ID
            reply[1] = id1;
            return reply;
        } catch (IOException e) {
            drain(c.getErrorStream());
            throw e;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_REPLY) throw new IOException("reply too large");
        }
        return buf.toByteArray();
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try (InputStream s = in) {
            byte[] chunk = new byte[1024];
            while (s.read(chunk) > 0) { /* discard */ }
        } catch (IOException ignored) {}
    }
}
