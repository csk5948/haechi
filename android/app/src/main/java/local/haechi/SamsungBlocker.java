package local.haechi;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Samsung Internet "Ad blocker" (content blocker) integration, per
 * https://developer.samsung.com/internet/android/adblockers-guide.html :
 * the browser reads an Adblock Plus format filter list from our ContentProvider
 * ({@link ContentBlockerProvider}), and we broadcast ACTION_UPDATE when the rules change.
 * Everything stays on the phone: the list is handed from app to app, no network.
 */
final class SamsungBlocker {
    private SamsungBlocker() {}

    static final String ACTION_UPDATE = "com.samsung.android.sbrowser.contentBlocker.ACTION_UPDATE";
    static final String ACTION_SETTING = "com.samsung.android.sbrowser.contentBlocker.ACTION_SETTING";
    /** Samsung Internet release and beta. */
    static final String[] BROWSERS = {"com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta"};

    /** Tells Samsung Internet to fetch our filters again (exact broadcast from the guide). */
    static void notifyUpdate(Context c) {
        Uri data = Uri.parse("package:" + c.getPackageName());
        try {
            c.sendBroadcast(new Intent(ACTION_UPDATE).setData(data));        // as in the guide
            for (String pkg : BROWSERS) {                                     // explicit copies: not
                c.sendBroadcast(new Intent(ACTION_UPDATE).setData(data).setPackage(pkg));  // hit by Android 8+ limits
            }
        } catch (RuntimeException ignored) {}
    }

    /**
     * What Samsung Internet gets: browser.txt as written, YousList (if on), the user's block list
     * as "||domain^" (skipping allow-listed ones) and the allow list as "@@||domain^" exceptions.
     */
    static String buildFilters(Context c) throws IOException {
        StringBuilder out = new StringBuilder("[Adblock Plus 2.0]\n");
        if (!BlockerService.running) return out.toString();   // switched off: Samsung Internet gets no rules either
        Lists.load(c);   // the provider may run before any screen or the VPN service
        for (String line : Lists.read(c, Lists.BROWSER).split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("!")) continue;
            if (t.startsWith("#") && !t.startsWith("##") && !t.startsWith("#?#")) continue;   // '#' notes are not ABP
            t = Wildcards.expandLine(c, t);   // "aa*.com##x" -> "aa1.com,aa2.com##x" (null: none seen yet)
            if (t != null) out.append(t).append('\n');
        }
        if (Bundled.isOn(c, Bundled.YOUSLIST)) {
            try (java.io.BufferedReader r = Bundled.open(c, Bundled.YOUSLIST)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("!") || t.startsWith("[")) continue;   // comments / header
                    out.append(t).append('\n');
                }
            }
        }
        // The bundled DNS lists (185k domains) are NOT included: the DNS filter already blocks them system-wide.
        for (String d : sorted(Lists.blockSet())) {
            if (!Lists.isAllowed(d)) out.append("||").append(d).append("^\n");
        }
        for (String d : sorted(Lists.allowSet())) out.append("@@||").append(d).append("^\n");
        return out.toString();
    }

    private static List<String> sorted(java.util.Set<String> s) {
        List<String> l = new ArrayList<>(s);
        Collections.sort(l);
        return l;
    }

    /** Samsung Internet and its variants (beta, ...). Android verifies the caller's package name. */
    static boolean isBrowser(String pkg) {
        return pkg != null && (pkg.equals(BROWSERS[0]) || pkg.startsWith(BROWSERS[0] + "."));
    }
}
