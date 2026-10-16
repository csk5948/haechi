package local.haechi;

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
 * Authority "local.haechi.contentBlocker.contentProvider" (required format from Samsung's guide).
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
     * the last read is shown on the main screen instead.
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
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
            record("규칙 " + rules + "줄 · " + data.length / 1024 + "KB");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            record("오류: " + e);
            throw new FileNotFoundException(e.getMessage());
        }
    }

    /** As in the Adblock Plus implementation: null = "we do not use encryption". */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return null;
    }

    /** When Samsung Internet last read the list, for the main screen. */
    private void record(String detail) {
        String when = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA).format(new java.util.Date());
        getContext().getSharedPreferences("samsung_diag", 0).edit().putString("last", when + " · " + detail).apply();
    }

    static String lastRead(android.content.Context c) {
        String last = c.getSharedPreferences("samsung_diag", 0).getString("last", "");
        return last.isEmpty() ? "삼성 인터넷이 아직 규칙을 읽어 간 적 없음" : "삼성 인터넷이 마지막으로 읽어 감: " + last;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
