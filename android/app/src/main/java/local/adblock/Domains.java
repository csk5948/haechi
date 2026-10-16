package local.adblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Turning user input (URLs, pasted text) into domains, and the "how much to block" dialog. */
final class Domains {
    private Domains() {}

    /** Second-level suffixes where the registrable domain has 3 labels (example.co.kr). */
    private static final List<String> TWO_LEVEL = Arrays.asList(
            "co.kr", "or.kr", "go.kr", "ne.kr", "ac.kr", "re.kr", "pe.kr",
            "co.jp", "ne.jp", "or.jp", "co.uk", "org.uk", "com.au", "com.cn", "com.tw", "com.br");

    /**
     * One line of input -> domain, or null. Accepts a plain domain, hosts format, "||x^",
     * or a URL ("https://ad.example.com/x.jpg" -> "ad.example.com").
     */
    static String normalize(String input) {
        String s = input.trim();
        if (s.isEmpty()) return null;
        int scheme = s.indexOf("://");
        if (scheme > 0 && scheme < 10) {
            String host = Uri.parse(s).getHost();
            if (host == null) return null;
            s = host;
        } else if (!s.startsWith("#") && !s.startsWith("!")) {
            int slash = s.indexOf('/');
            if (slash > 0 && s.indexOf(' ') < 0) s = s.substring(0, slash);   // "ad.example.com/x.jpg"
            int colon = s.lastIndexOf(':');
            if (colon > 0 && s.indexOf(':') == colon && s.indexOf(' ') < 0) s = s.substring(0, colon);  // ":8080"
        }
        return Lists.parseLine(s.toLowerCase(Locale.ROOT));
    }

    /** First URL or domain found in free text (shared text, clipboard). */
    static String extractHost(CharSequence text) {
        if (text == null) return null;
        String[] tokens = text.toString().split("\\s+");
        for (String t : tokens) if (t.contains("://")) { String d = normalize(t); if (d != null) return d; }
        for (String t : tokens) { String d = normalize(t.replaceAll("^[(<\"']+|[)>\"',.]+$", "")); if (d != null) return d; }
        return null;
    }

    /** ads.x.example.com -> [ads.x.example.com, x.example.com, example.com]; 3 labels for co.kr etc. */
    static List<String> scopes(String domain) {
        String[] l = domain.split("\\.");
        int n = l.length;
        int min = 2;
        if (n >= 3 && TWO_LEVEL.contains(l[n - 2] + "." + l[n - 1])) min = 3;
        List<String> out = new ArrayList<>();
        for (int k = n; k >= Math.min(min, n); k--) out.add(String.join(".", Arrays.copyOfRange(l, n - k, n)));
        return out;
    }

    /** "어디까지 차단할까요?" -> appends the chosen scope to block.txt. {@code done} runs afterwards either way. */
    static void chooseScopeAndBlock(Activity a, String domain, Runnable done) {
        List<String> s = scopes(domain);
        String[] labels = new String[s.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = s.get(i) + (i == 0 ? "" : "  (하위 전체)");
        }
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("차단할 범위 · " + domain)
                .setItems(labels, (d, which) -> {
                    String pick = s.get(which);
                    try {
                        if (Lists.blockSet().contains(pick)) {
                            Toast.makeText(a, pick + " 은(는) 이미 차단 목록에 있습니다", Toast.LENGTH_SHORT).show();
                        } else {
                            Lists.append(a, Lists.BLOCK, pick);
                            Toast.makeText(a, pick + " 차단", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException e) {
                        Toast.makeText(a, "실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .setOnDismissListener(d -> { if (done != null) done.run(); })
                .show();
    }
}
