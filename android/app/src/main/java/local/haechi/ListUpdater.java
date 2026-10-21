package local.haechi;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Keeps the bundled public lists fresh: downloads them from their original sources into
 * files/lists/<id>.txt, which Bundled reads instead of the copy inside the APK.
 *
 *  - Manual: "지금 업데이트" in the 기본 제공 목록 tab. Optional automatic mode (off by default): a JobScheduler job every day on an unmetered network (Wi-Fi), which
 *    only downloads when the last successful check is 3+ days old. "지금 업데이트" always checks.
 *  - A list that has not changed costs one small request (ETag / Last-Modified → 304).
 *  - DNS lists are converted exactly like tools/convert_lists.py; a result that is suspiciously
 *    small (broken download) is thrown away and the previous copy stays.
 *  - These downloads are the only network traffic besides DNS forwarding (README "네트워크 호출 위치").
 */
final class ListUpdater {
    private ListUpdater() {}

    static final class Source {
        final String id, url;
        final int minRules;   // sanity floor: fewer rules than this = broken download, keep the old copy
        Source(String id, String url, int minRules) { this.id = id; this.url = url; this.minRules = minRules; }
    }

    static final Source[] SOURCES = {
            new Source("adguard", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", 50000),
            new Source("listkr", "https://cdn.jsdelivr.net/npm/@list-kr/filterslists@latest/dist/filterslist-DNS.txt", 50),
            new Source("yous", "https://raw.githubusercontent.com/yous/YousList/master/youslist.txt", 1000),
    };

    static final String DIR = "lists";
    private static final long STALE_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int JOB_ID = 7101, MAX_BYTES = 30 << 20;
    private static final Object lock = new Object();

    /** Downloaded copy of a bundled list, or null to use the one in the APK. */
    static File file(Context c, String id) {
        File f = new File(new File(c.getFilesDir(), DIR), id + ".txt");
        return f.exists() ? f : null;
    }

    // ---------------------------------------------------------------- settings / status

    static boolean isAuto(Context c) { return prefs(c).getBoolean("auto", false); }   // off unless the user turns it on

    static void setAuto(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto", on).apply();
        schedule(c);
    }

    /** "마지막 확인 10-17 18:20 · 바뀐 목록 1개" etc., for the 기본 제공 목록 tab. */
    static String status(Context c) {
        String s = prefs(c).getString("status", "");
        return s.isEmpty() ? "아직 업데이트한 적 없음 (앱에 들어 있는 목록 사용 중)" : s;
    }

    // ---------------------------------------------------------------- scheduling

    /** (Re)schedules or cancels the daily Wi-Fi job. Safe to call often. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (!isAuto(c)) { js.cancel(JOB_ID); return; }
        if (js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(24L * 60 * 60 * 1000)
                .setPersisted(true)   // survives reboot (RECEIVE_BOOT_COMPLETED)
                .build());
    }

    /** The background job: runs the update on its own thread, never on the main thread. */
    public static final class Job extends JobService {
        @Override
        public boolean onStartJob(JobParameters p) {
            new Thread(() -> {
                update(getApplicationContext(), false);
                jobFinished(p, false);
            }, "list-update").start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters p) { return false; }
    }

    // ---------------------------------------------------------------- update

    /**
     * Checks every source (skipped when {@code force} is false and the last good check is recent).
     * Blocking: call off the main thread. Returns the status line.
     */
    static String update(Context c, boolean force) {
        synchronized (lock) {
            SharedPreferences p = prefs(c);
            if (!force && System.currentTimeMillis() - p.getLong("last_ok", 0) < STALE_MS) return status(c);
            new File(c.getFilesDir(), DIR).mkdirs();
            int changed = 0, failed = 0;
            boolean dnsChanged = false, yousChanged = false;
            StringBuilder errors = new StringBuilder();
            for (Source s : SOURCES) {
                try {
                    if (fetch(c, s)) {
                        changed++;
                        if (s.id.equals("yous")) yousChanged = true; else dnsChanged = true;
                    }
                } catch (IOException e) {
                    failed++;
                    errors.append("\n").append(s.id).append(": ").append(e.getMessage());
                }
            }
            String when = new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date());
            String st = "마지막 확인 " + when + " · " + (changed > 0 ? "새로 받은 목록 " + changed + "개" : "바뀐 목록 없음")
                    + (failed > 0 ? " · 실패 " + failed + "개" + errors : "");
            SharedPreferences.Editor e = p.edit().putString("status", st);
            if (failed == 0) e.putLong("last_ok", System.currentTimeMillis());
            e.apply();
            if (dnsChanged && (Bundled.isReady() || BlockerService.running)) Bundled.loadAsync(c);
            if (yousChanged) SamsungBlocker.notifyUpdate(c);
            return st;
        }
    }

    /** Downloads one list if it changed. True when a new copy was written. */
    private static boolean fetch(Context c, Source s) throws IOException {
        SharedPreferences p = prefs(c);
        boolean have = file(c, s.id) != null;
        // NO_PROXY: with SNI 쪼개기 / HTTPS filtering on, the VPN's proxy is our own relay, which would open this
        // connection with the phone's CA — and this app (rightly) trusts only the system's certificates.
        HttpURLConnection con = (HttpURLConnection) new URL(s.url).openConnection(java.net.Proxy.NO_PROXY);
        con.setConnectTimeout(15000);
        con.setReadTimeout(60000);
        con.setRequestProperty("User-Agent", "Haechi");
        if (have) {   // only ask for the body if it changed since our copy
            String etag = p.getString("etag_" + s.id, null), mod = p.getString("mod_" + s.id, null);
            if (etag != null) con.setRequestProperty("If-None-Match", etag);
            if (mod != null) con.setRequestProperty("If-Modified-Since", mod);
        }
        try {
            int code = con.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return false;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            String body;
            try (InputStream in = con.getInputStream()) { body = readCapped(in); }
            String out = s.id.equals("yous") ? checkAbp(body, s) : convertDns(body, s);
            File dst = new File(new File(c.getFilesDir(), DIR), s.id + ".txt"), tmp = new File(dst.getPath() + ".tmp");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                fo.write(out.getBytes(StandardCharsets.UTF_8));
                fo.getFD().sync();
            }
            if (!tmp.renameTo(dst)) throw new IOException("저장 실패");
            p.edit().putString("etag_" + s.id, con.getHeaderField("ETag"))
                    .putString("mod_" + s.id, con.getHeaderField("Last-Modified")).apply();
            return true;
        } finally {
            con.disconnect();
        }
    }

    private static final Pattern DOMAIN = Pattern.compile(
            "^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$");

    /** Same rules as tools/convert_lists.py: only "||domain^" (and "@@||domain^"), no other $options. */
    static String convertDns(String text, Source s) throws IOException {
        TreeSet<String> block = new TreeSet<>(), allow = new TreeSet<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || "!#[".indexOf(line.charAt(0)) >= 0) continue;
            boolean exception = line.startsWith("@@");
            if (exception) line = line.substring(2);
            if (!line.startsWith("||")) continue;
            line = line.substring(2);
            int dollar = line.indexOf('$');
            String options = dollar < 0 ? "" : line.substring(dollar + 1);
            if (!options.isEmpty() && !options.equals("important")) continue;
            String domain = (dollar < 0 ? line : line.substring(0, dollar));
            while (domain.endsWith("^") || domain.endsWith("|")) domain = domain.substring(0, domain.length() - 1);
            domain = domain.toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(domain).matches()) continue;
            (exception ? allow : block).add(domain);
        }
        if (block.size() < s.minRules) throw new IOException("규칙이 너무 적음 (" + block.size() + "개) · 이전 목록 유지");
        String title = s.id.equals("adguard") ? "AdGuard DNS filter" : "List-KR DNS";
        StringBuilder out = new StringBuilder("# " + title + "\n# 출처: " + s.url + "\n# 변환: "
                + new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date())
                + " · 차단 " + block.size() + "개 · 예외 " + allow.size() + "개 · 앱에서 받음\n");
        for (String d : block) out.append(d).append('\n');
        for (String d : allow) out.append("@@").append(d).append('\n');
        return out.toString();
    }

    /** YousList is used as-is (Adblock Plus format for Samsung Internet); just check it looks right. */
    private static String checkAbp(String text, Source s) throws IOException {
        if (!text.startsWith("[Adblock")) throw new IOException("Adblock Plus 형식이 아님 · 이전 목록 유지");
        int rules = 0;
        for (String line : text.split("\n")) if (!line.isEmpty() && line.charAt(0) != '!' && line.charAt(0) != '[') rules++;
        if (rules < s.minRules) throw new IOException("규칙이 너무 적음 (" + rules + "개) · 이전 목록 유지");
        return text.replace("\r", "");
    }

    private static String readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) throw new IOException("파일이 너무 큼");
        }
        return buf.toString(StandardCharsets.UTF_8.name()).replace("\r", "");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("list_update", Context.MODE_PRIVATE);
    }
}
