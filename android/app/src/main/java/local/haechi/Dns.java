package local.haechi;

/**
 * IPv4 / UDP / DNS byte-level helpers. Pure functions on byte arrays: no I/O, no state.
 *
 * Only what this app needs: recognise a DNS query sent to our fake DNS address, read its
 * question name, build a "blocked" answer, and wrap any DNS reply back into an IPv4/UDP packet.
 */
final class Dns {
    private Dns() {}

    /** 10.111.222.2 - the fake DNS server address announced to the system (see BlockerService). */
    static final byte[] FAKE_DNS = {10, 111, (byte) 222, 2};

    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;

    /** One DNS query taken from the tunnel. All offsets are absolute positions inside {@code packet}. */
    static final class Query {
        byte[] packet;     // the whole IPv4 packet (owned copy)
        int ipHeaderLen;   // usually 20
        int dnsOffset;     // start of the DNS message (after IPv4 + UDP headers)
        int dnsLength;     // length of the DNS message
        String name;       // lower-case question name, or null if it could not be read
        int qtype;         // question type (1 = A, 28 = AAAA, ...)
        int questionEnd;   // first byte after the question section
    }

    /**
     * Returns the query if {@code p} is an unfragmented IPv4/UDP packet to FAKE_DNS port 53,
     * otherwise null (the caller drops it). If the question cannot be parsed, {@code name} stays
     * null and the caller simply forwards the query unchanged.
     */
    static Query parse(byte[] p, int len) {
        if (len < 20 || ((p[0] >> 4) & 0xF) != 4) return null;  // IPv4 only
        int ihl = (p[0] & 0x0F) * 4;
        if (ihl < 20 || len < ihl + 8) return null;
        if (p[9] != 17) return null;                              // protocol must be UDP
        if ((u16(p, 6) & 0x3FFF) != 0) return null;               // fragments (MF flag or offset): drop
        for (int i = 0; i < 4; i++) {
            if (p[16 + i] != FAKE_DNS[i]) return null;            // destination must be our fake DNS
        }
        if (u16(p, ihl + 2) != 53) return null;                   // UDP destination port 53
        int udpLen = u16(p, ihl + 4);
        if (udpLen < 8 + 12 || ihl + udpLen > len) return null;   // need at least a DNS header

        Query q = new Query();
        q.packet = p;
        q.ipHeaderLen = ihl;
        q.dnsOffset = ihl + 8;
        q.dnsLength = udpLen - 8;
        readQuestion(q);
        return q;
    }

    /** Reads QNAME / QTYPE of the first question. Compression pointers are not supported (queries don't use them). */
    private static void readQuestion(Query q) {
        byte[] p = q.packet;
        int end = q.dnsOffset + q.dnsLength;
        if (u16(p, q.dnsOffset + 4) == 0) return;                 // QDCOUNT == 0
        int i = q.dnsOffset + 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            if (i >= end) return;
            int labelLen = p[i++] & 0xFF;
            if (labelLen == 0) break;                             // root label: end of name
            if ((labelLen & 0xC0) != 0) return;                   // compression / reserved: give up
            if (i + labelLen > end) return;
            if (name.length() > 0) name.append('.');
            for (int k = 0; k < labelLen; k++) {
                char c = (char) (p[i + k] & 0xFF);
                name.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
            }
            i += labelLen;
        }
        if (i + 4 > end) return;                                  // QTYPE + QCLASS
        q.qtype = u16(p, i);
        q.questionEnd = i + 4;
        q.name = name.toString();
    }

    /**
     * Builds the DNS answer for a blocked name: A -> 0.0.0.0, AAAA -> ::, other types -> NOERROR
     * with no answers ("NODATA").
     *
     * Why NODATA instead of NXDOMAIN for other types: NXDOMAIN means "this name does not exist at
     * all", which would contradict the 0.0.0.0 we hand out for A of the same name, and some
     * resolvers cache NXDOMAIN for every type. NODATA just says "no record of this type".
     */
    static byte[] blockedAnswer(Query q) {
        byte[] p = q.packet;
        int qStart = q.dnsOffset + 12;
        int qLen = q.questionEnd - qStart;
        int rdLen = q.qtype == TYPE_A ? 4 : q.qtype == TYPE_AAAA ? 16 : 0;
        int answerLen = rdLen > 0 ? 12 + rdLen : 0;   // ptr(2)+type(2)+class(2)+ttl(4)+rdlen(2)+rdata

        byte[] r = new byte[12 + qLen + answerLen];
        r[0] = p[q.dnsOffset];                         // same ID
        r[1] = p[q.dnsOffset + 1];
        int qFlags = u16(p, q.dnsOffset + 2);
        int flags = 0x8000                             // QR = response
                | (qFlags & 0x7800)                    // OPCODE copied
                | (qFlags & 0x0100)                    // RD copied
                | 0x0080;                              // RA = 1, RCODE = 0 (NOERROR)
        put16(r, 2, flags);
        put16(r, 4, 1);                                // QDCOUNT: we echo the first question only
        put16(r, 6, rdLen > 0 ? 1 : 0);                // ANCOUNT
        // NSCOUNT and ARCOUNT stay 0.
        System.arraycopy(p, qStart, r, 12, qLen);      // question echoed

        if (rdLen > 0) {
            int a = 12 + qLen;
            put16(r, a, 0xC00C);                       // name = pointer to the question name
            put16(r, a + 2, q.qtype);
            put16(r, a + 4, 1);                        // class IN
            put16(r, a + 6, 0);                        // TTL high 16 bits
            put16(r, a + 8, 60);                       // TTL = 60 s
            put16(r, a + 10, rdLen);
            // RDATA is left as zeros: 0.0.0.0 or ::
        }
        return r;
    }

    /**
     * Wraps a DNS reply into an IPv4/UDP packet going back to whoever sent {@code q}:
     * addresses and ports swapped, IPv4 header checksum computed, UDP checksum 0
     * (0 means "no checksum", which IPv4 allows).
     */
    static byte[] wrapReply(Query q, byte[] dns, int dnsLen) {
        byte[] p = q.packet;
        int ihl = q.ipHeaderLen;
        int total = 20 + 8 + dnsLen;
        byte[] r = new byte[total];

        r[0] = 0x45;                                   // IPv4, header 20 bytes
        put16(r, 2, total);
        put16(r, 6, 0x4000);                           // Don't Fragment
        r[8] = 64;                                     // TTL
        r[9] = 17;                                     // UDP
        System.arraycopy(p, 16, r, 12, 4);             // src = original destination
        System.arraycopy(p, 12, r, 16, 4);             // dst = original source
        put16(r, 10, checksum(r, 0, 20));

        r[20] = p[ihl + 2];                            // src port = original dst port (53)
        r[21] = p[ihl + 3];
        r[22] = p[ihl];                                // dst port = original src port
        r[23] = p[ihl + 1];
        put16(r, 24, 8 + dnsLen);
        // r[26..27] = UDP checksum 0

        System.arraycopy(dns, 0, r, 28, dnsLen);
        return r;
    }

    /** Internet checksum (RFC 1071) over an even number of bytes. */
    static int checksum(byte[] b, int off, int len) {
        int sum = 0;
        for (int i = off; i < off + len; i += 2) sum += u16(b, i);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return ~sum & 0xFFFF;
    }

    static int u16(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    static void put16(byte[] b, int i, int v) {
        b[i] = (byte) (v >> 8);
        b[i + 1] = (byte) v;
    }
}
