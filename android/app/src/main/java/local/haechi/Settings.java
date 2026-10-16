package local.haechi;

import android.content.Context;
import android.content.SharedPreferences;

/** User settings: which DNS server allowed queries are forwarded to, and the browser split proxy. */
final class Settings {
    private Settings() {}

    static final String CLOUDFLARE = "cloudflare", GOOGLE = "google", CARRIER = "carrier";
    private static final String FILE = "settings", KEY_DNS = "dns", KEY_SPLIT = "split_hello";

    /** Browser proxy that splits the TLS ClientHello; read when the VPN starts. Off by default. */
    static boolean splitHello(Context c) {
        return prefs(c).getBoolean(KEY_SPLIT, false);
    }

    static void setSplitHello(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_SPLIT, on).apply();
    }

    /** DoH URL in use, or null for "carrier DNS, unencrypted". Read by BlockerService per query. */
    static volatile String dohUrl = Doh.CLOUDFLARE;

    static String dnsMode(Context c) {
        return prefs(c).getString(KEY_DNS, CLOUDFLARE);   // default: Cloudflare encrypted
    }

    static void load(Context c) {
        dohUrl = urlFor(dnsMode(c));
    }

    /** Saves and applies at once (the running filter reads dohUrl for every query). */
    static void setDnsMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_DNS, mode).apply();
        dohUrl = urlFor(mode);
    }

    private static String urlFor(String mode) {
        if (GOOGLE.equals(mode)) return Doh.GOOGLE;
        if (CARRIER.equals(mode)) return null;
        return Doh.CLOUDFLARE;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
