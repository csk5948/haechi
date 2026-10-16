package local.adblock;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Hands our Adblock Plus format filter list to Samsung Internet.
 *
 * Authority "local.adblock.contentBlocker.contentProvider" (required format from Samsung's guide).
 * The guide does not say which method the browser calls; the open-source Samsung content
 * blockers (Adblock Plus for Samsung Internet, AdGuard Content Blocker) both serve the list from
 * openFile() as a read-only file, and return null from call() ("no encryption"). Same here.
 *
 * The provider must be exported, so it only answers Samsung Internet (and ourselves):
 * other apps cannot read the user's lists through it.
 */
public class ContentBlockerProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * No caller check: "Samsung Internet couldn't use the ad blocker" appeared with one, so the browser
     * may read through a package we didn't expect. The list only holds blocking rules (no personal data);
     * every access is recorded and shown on the main screen instead.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String caller = getCallingPackage();
        try {
            File f = new File(getContext().getCacheDir(), "samsung_filters.txt");
            String filters = SamsungBlocker.buildFilters(getContext());
            byte[] data = filters.getBytes(StandardCharsets.UTF_8);
            synchronized (ContentBlockerProvider.class) {
                File tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(f)) throw new IOException("rename failed");
            }
            int rules = filters.split("\n").length;
            record("openFile", caller, "시험 " + SamsungBlocker.testLevel(getContext()) + " · 규칙 " + rules + "줄 · " + data.length / 1024 + "KB 보냄");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            record("openFile", caller, "오류: " + e);
            throw new FileNotFoundException(e.getMessage());
        }
    }

    /** As in the Adblock Plus implementation: null = "we do not use encryption". */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        record("call", getCallingPackage(), "method=" + method + " arg=" + arg + " extras=" + (extras == null ? null : extras.keySet()));
        return null;
    }

    /** Last few accesses, for the 연동 상태 text on the main screen. */
    private void record(String what, String caller, String detail) {
        String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.KOREA).format(new java.util.Date())
                + " " + what + " ← " + caller + "\n  " + detail;
        android.content.SharedPreferences p = getContext().getSharedPreferences("samsung_diag", 0);
        String old = p.getString("log", "");
        String joined = line + (old.isEmpty() ? "" : "\n" + old);
        String[] parts = joined.split("\n");
        StringBuilder keep = new StringBuilder();
        for (int i = 0; i < Math.min(parts.length, 12); i++) keep.append(i > 0 ? "\n" : "").append(parts[i]);
        p.edit().putString("log", keep.toString()).apply();
    }

    static String diagnostics(android.content.Context c) {
        String log = c.getSharedPreferences("samsung_diag", 0).getString("log", "");
        return log.isEmpty() ? "아직 삼성 인터넷이 규칙을 요청한 적 없음" : log;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
