package local.adblock;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * "분류" tags shown in the 기록 screen. Display only: has nothing to do with blocking.
 *
 * Rule (same as the Windows version, windows/Labels.cs):
 *  1. Known domains from assets/labels.txt ([광고] / [추적] / [CDN] sections). A name gets the
 *     tag of its most specific listed parent (ads.x.com uses x.com's tag if ads.x.com isn't listed).
 *  2. Otherwise split the name on '.' and '-'. If any WHOLE token is an ad word -> "광고?",
 *     else if any whole token is a tracking word -> "추적?". Whole tokens only, so
 *     "download" or "address" never match "ad".
 *  3. Otherwise no tag (null).
 */
final class Labels {
    private Labels() {}

    static final String AD = "광고", TRACK = "추적", CDN = "CDN", AD_GUESS = "광고?", TRACK_GUESS = "추적?";

    private static final Set<String> AD_WORDS = new HashSet<>(Arrays.asList(
            "ad", "ads", "adx", "adv", "advert", "adverts", "advertising", "adserver", "adservice", "adsrv",
            "adnet", "adtech", "pagead", "banner", "banners", "adfit", "admob"));
    private static final Set<String> TRACK_WORDS = new HashSet<>(Arrays.asList(
            "track", "tracker", "tracking", "analytics", "pixel", "beacon", "metrics", "telemetry",
            "stats", "stat", "log", "logs", "collect"));

    private static volatile Map<String, String> known = Collections.emptyMap();

    /** Reads assets/labels.txt once. */
    static synchronized void load(Context c) {
        if (!known.isEmpty()) return;
        Map<String, String> m = new HashMap<>();
        try (InputStream in = c.getAssets().open("labels.txt")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            String category = null;
            for (String line : buf.toString(StandardCharsets.UTF_8.name()).split("\n")) {
                line = line.trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    category = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                String d = Lists.parseLine(line);   // same comment / format rules as the lists
                if (d != null && category != null) m.put(d, category);
            }
        } catch (IOException ignored) {}
        known = m;
    }

    /** Tag for a domain, or null. */
    static String of(String name) {
        Map<String, String> k = known;
        for (String d = name; ; ) {
            String c = k.get(d);
            if (c != null) return c;
            int dot = d.indexOf('.');
            if (dot < 0) break;
            d = d.substring(dot + 1);
        }
        boolean track = false;
        for (String token : name.split("[.\\-]")) {
            if (AD_WORDS.contains(token)) return AD_GUESS;
            if (TRACK_WORDS.contains(token)) track = true;
        }
        return track ? TRACK_GUESS : null;
    }

    static boolean isAdOrTrack(String tag) {
        return AD.equals(tag) || TRACK.equals(tag) || AD_GUESS.equals(tag) || TRACK_GUESS.equals(tag);
    }
}
