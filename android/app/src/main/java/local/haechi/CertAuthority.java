package local.haechi;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * The phone's own certificate authority for HTTPS filtering (see {@link HttpsFilter}).
 *
 * The CA key is made inside the Android Keystore (the phone's secure hardware where there is one) and can not
 * be exported: signing happens in there, so not even this app, run-as or a backup can read the key. Only the CA
 * *certificate* is kept as a file and exported so the user can install it under
 * 설정 › 보안 › 인증서 설치 › CA 인증서. With it installed, browsers that trust user CAs (Chrome, Samsung
 * Internet) accept the per-site certificates made here.
 *
 * No BouncyCastle: certificates are written as DER by hand (X.509 v3, SHA256withRSA) and parsed back
 * with the framework's CertificateFactory.
 */
final class CertAuthority {
    private static final String DIR = "ca", CERT = "ca.der", KEY = "ca.key";   // ca.key: old builds kept the key as a file
    private static final String ALIAS = "haechi_ca", KEYSTORE = "AndroidKeyStore";
    private static final long DAY = 24L * 3600 * 1000;

    private final X509Certificate caCert;
    private final PrivateKey caKey;
    private final byte[] caKeyId;
    private KeyPair leafKeys;   // one key for every site certificate (RSA generation is slow on phones); made on first use
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SSLContext> sites = new LinkedHashMap<String, SSLContext>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SSLContext> e) { return size() > 300; }
    };

    private CertAuthority(X509Certificate caCert, PrivateKey caKey) throws GeneralSecurityException {
        this.caCert = caCert;
        this.caKey = caKey;
        this.caKeyId = keyId(caCert.getPublicKey());
    }

    private static volatile CertAuthority instance;

    /** The CA, made on first use. Slow the first time (RSA key generation): call off the main thread. */
    static CertAuthority get(Context c) throws IOException, GeneralSecurityException {
        CertAuthority ca = instance;
        if (ca != null) return ca;
        synchronized (CertAuthority.class) {
            if (instance == null) instance = loadOrCreate(c);
            return instance;
        }
    }

    /** A CA certificate AND its key (in the Keystore, or an old key file) are there. */
    static boolean exists(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        return new File(d, CERT).exists() && (new File(d, KEY).exists() || keystoreKey() != null);
    }

    /** Made by an older build that kept the key as a plain file: 인증서 새로 만들기 moves it into the Keystore. */
    static boolean legacyKey(Context c) {
        return new File(new File(c.getFilesDir(), DIR), KEY).exists();
    }

    private static PrivateKey keystoreKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            return (PrivateKey) ks.getKey(ALIAS, null);
        } catch (IOException | GeneralSecurityException | ClassCastException e) {
            return null;
        }
    }

    /** The CA certificate without making one (null if not made yet). */
    static X509Certificate certificate(Context c) {
        try {
            File f = new File(new File(c.getFilesDir(), DIR), CERT);
            return f.exists() ? parse(Files.readAllBytes(f.toPath())) : null;
        } catch (IOException | GeneralSecurityException e) {
            return null;
        }
    }

    /** True if the user has installed our CA certificate (it shows in the system's CA store as "user:…"). */
    static boolean isInstalled(Context c) {
        X509Certificate cert = certificate(c);
        if (cert == null) return false;
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null);
            return ks.getCertificateAlias(cert) != null;
        } catch (IOException | GeneralSecurityException e) {
            return false;
        }
    }

    /** "새 인증서 만들기": forgets the old CA (the user should remove it from the phone too). */
    static synchronized void reset(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        new File(d, CERT).delete();
        new File(d, KEY).delete();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (IOException | GeneralSecurityException ignored) {}
        synchronized (CertAuthority.class) { instance = null; }
    }

    X509Certificate caCert() { return caCert; }

    /** PEM text of the CA certificate, as the system's certificate installer reads it. */
    static String pem(X509Certificate cert) throws GeneralSecurityException {
        String b64 = android.util.Base64.encodeToString(cert.getEncoded(), android.util.Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        return sb.append("-----END CERTIFICATE-----\n").toString();
    }

    /** Server-side TLS context presenting a certificate for {@code host}, signed by our CA. Cached per host. */
    SSLContext contextFor(String host) throws GeneralSecurityException {
        synchronized (sites) {
            SSLContext ctx = sites.get(host);
            if (ctx != null) return ctx;
        }
        KeyPair keys = leafKeys();
        X509Certificate leaf = parse(leafCert(host, keys));
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{new OneKey(new X509Certificate[]{leaf, caCert}, keys.getPrivate())}, null, null);
        synchronized (sites) { sites.put(host, ctx); }
        return ctx;
    }

    private synchronized KeyPair leafKeys() throws GeneralSecurityException {
        if (leafKeys == null) leafKeys = rsa();
        return leafKeys;
    }

    // ---------------------------------------------------------------- making / loading the CA

    private static CertAuthority loadOrCreate(Context c) throws IOException, GeneralSecurityException {
        File d = new File(c.getFilesDir(), DIR);
        File certFile = new File(d, CERT), keyFile = new File(d, KEY);
        if (certFile.exists() && keyFile.exists()) {               // old build: key in a file, still works
            X509Certificate cert = parse(Files.readAllBytes(certFile.toPath()));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
            return new CertAuthority(cert, key);
        }
        PrivateKey stored = keystoreKey();
        if (certFile.exists() && stored != null) return new CertAuthority(parse(Files.readAllBytes(certFile.toPath())), stored);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("mkdir " + d);
        KeyPair kp = keystoreRsa();
        byte[] der = caCertDer(kp);
        write(certFile, der);
        return new CertAuthority(parse(der), kp.getPrivate());
    }

    private static byte[] caCertDer(KeyPair kp) throws GeneralSecurityException {
        SecureRandom r = new SecureRandom();
        byte[] tag = new byte[3];
        r.nextBytes(tag);
        String suffix = String.format(Locale.ROOT, "%02X%02X%02X", tag[0], tag[1], tag[2]);
        byte[] name = name("Haechi Local CA " + suffix, "Haechi (on this phone only)");
        long now = System.currentTimeMillis();
        byte[] id = keyId(kp.getPublic());
        byte[] exts = seq(
                ext(OID_BASIC_CONSTRAINTS, true, seq(bool(true))),
                ext(OID_KEY_USAGE, true, bits(0x06)),                  // keyCertSign, cRLSign
                ext(OID_SUBJECT_KEY_ID, false, octets(id)));
        return sign(kp.getPrivate(), tbs(serial(r), name, now - DAY, now + 3650 * DAY, name, kp.getPublic(), exts));
    }

    private byte[] leafCert(String host, KeyPair keys) throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        String cn = host.length() <= 64 ? host : "haechi";             // CN is capped at 64 chars; SAN carries the name
        byte[] issuer = caCert.getSubjectX500Principal().getEncoded();
        byte[] exts = seq(
                ext(OID_SAN, false, seq(tlv(0x82, host.getBytes(StandardCharsets.US_ASCII)))),   // dNSName
                ext(OID_BASIC_CONSTRAINTS, true, seq()),
                ext(OID_KEY_USAGE, true, bits(0xA0)),                 // digitalSignature, keyEncipherment
                ext(OID_EXT_KEY_USAGE, false, seq(oid(OID_SERVER_AUTH))),
                ext(OID_AUTHORITY_KEY_ID, false, seq(tlv(0x80, caKeyId))));
        return sign(caKey, tbs(serial(random), issuer, now - DAY, now + 365 * DAY, name(cn, null), keys.getPublic(), exts));
    }

    /**
     * Non-exportable signing key in the Android Keystore. Not StrongBox: a separate secure chip signs far more
     * slowly, and every new site needs one signature; the TEE still keeps the key out of reach.
     */
    private static KeyPair keystoreRsa() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA", KEYSTORE);
        g.initialize(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build());
        return g.generateKeyPair();
    }

    private static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
    }

    /** SHA-1 of the public key, for the key identifier extensions. */
    private static byte[] keyId(PublicKey k) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-1").digest(k.getEncoded());
    }

    // ---------------------------------------------------------------- X.509 pieces (DER)

    private static final String OID_SHA256_RSA = "1.2.840.113549.1.1.11", OID_CN = "2.5.4.3", OID_O = "2.5.4.10",
            OID_BASIC_CONSTRAINTS = "2.5.29.19", OID_KEY_USAGE = "2.5.29.15", OID_SUBJECT_KEY_ID = "2.5.29.14",
            OID_AUTHORITY_KEY_ID = "2.5.29.35", OID_SAN = "2.5.29.17", OID_EXT_KEY_USAGE = "2.5.29.37",
            OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";

    private static byte[] tbs(byte[] serial, byte[] issuer, long from, long to, byte[] subject, PublicKey key, byte[] exts) {
        return seq(
                tlv(0xA0, integer(BigInteger.valueOf(2))),            // v3
                serial,
                algorithm(),
                issuer,
                seq(time(from), time(to)),
                subject,
                key.getEncoded(),                                     // SubjectPublicKeyInfo, already DER
                tlv(0xA3, exts));
    }

    private static byte[] sign(PrivateKey key, byte[] tbs) throws GeneralSecurityException {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bitString = new byte[sig.length + 1];                  // leading 0 = no unused bits
        System.arraycopy(sig, 0, bitString, 1, sig.length);
        return seq(tbs, algorithm(), tlv(0x03, bitString));
    }

    private static byte[] algorithm() { return seq(oid(OID_SHA256_RSA), new byte[]{0x05, 0x00}); }

    private static byte[] serial(SecureRandom r) {
        byte[] b = new byte[16];
        r.nextBytes(b);
        b[0] &= 0x7F;                                                 // positive
        b[0] |= 0x01;                                                 // and no leading zero byte
        return tlv(0x02, b);
    }

    private static byte[] name(String cn, String org) {
        byte[] cnRdn = set(seq(oid(OID_CN), tlv(0x0C, cn.getBytes(StandardCharsets.UTF_8))));
        if (org == null) return seq(cnRdn);
        return seq(set(seq(oid(OID_O), tlv(0x0C, org.getBytes(StandardCharsets.UTF_8)))), cnRdn);
    }

    private static byte[] ext(String id, boolean critical, byte[] value) {
        return critical ? seq(oid(id), bool(true), octets(value)) : seq(oid(id), octets(value));
    }

    /** UTCTime up to 2049, GeneralizedTime after (RFC 5280). */
    private static byte[] time(long ms) {
        Date d = new Date(ms);
        boolean utc = d.before(new Date(2524608000000L));             // 2050-01-01
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    /** KeyUsage as a BIT STRING of one byte (bit 0 = 0x80). */
    private static byte[] bits(int b) {
        int unused = Integer.numberOfTrailingZeros(b);
        return tlv(0x03, new byte[]{(byte) unused, (byte) b});
    }

    private static byte[] bool(boolean v) { return new byte[]{0x01, 0x01, (byte) (v ? 0xFF : 0)}; }
    private static byte[] integer(BigInteger v) { return tlv(0x02, v.toByteArray()); }
    private static byte[] octets(byte[] v) { return tlv(0x04, v); }
    private static byte[] seq(byte[]... parts) { return tlv(0x30, cat(parts)); }
    private static byte[] set(byte[]... parts) { return tlv(0x31, cat(parts)); }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int n = 0;
            byte[] tmp = new byte[10];
            do { tmp[n++] = (byte) (v & 0x7F); v >>>= 7; } while (v > 0);
            for (int k = n - 1; k >= 0; k--) out.write(tmp[k] | (k > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 6);
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            int n = len > 0xFFFFFF ? 4 : len > 0xFFFF ? 3 : len > 0xFF ? 2 : 1;
            out.write(0x80 | n);
            for (int i = n - 1; i >= 0; i--) out.write(len >>> (8 * i));
        }
        out.write(value, 0, len);
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- key manager

    /** Always offers the one certificate chain it holds, whatever the client asks. */
    private static final class OneKey extends X509ExtendedKeyManager {
        private static final String ALIAS = "site";
        private final X509Certificate[] chain;
        private final PrivateKey key;

        OneKey(X509Certificate[] chain, PrivateKey key) { this.chain = chain; this.key = key; }

        @Override public String chooseServerAlias(String type, Principal[] issuers, Socket s) { return "RSA".equals(type) ? ALIAS : null; }
        @Override public String chooseEngineServerAlias(String type, Principal[] issuers, SSLEngine e) { return "RSA".equals(type) ? ALIAS : null; }
        @Override public String[] getServerAliases(String type, Principal[] issuers) { return "RSA".equals(type) ? new String[]{ALIAS} : null; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? chain.clone() : null; }
        @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
        @Override public String[] getClientAliases(String type, Principal[] issuers) { return null; }
        @Override public String chooseClientAlias(String[] type, Principal[] issuers, Socket s) { return null; }
    }
}
