package local.haechi;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Adblock Plus rules that HTTPS filtering applies inside the connection (see {@link HttpsFilter}):
 * browser.txt, every switched-on pack, and YousList (if on) — the same text Samsung Internet gets.
 *
 *  - Request rules: {@code ||host/path^}, {@code /path/ad_*.js}, {@code |https://…|}, {@code @@} exceptions, options
 *    {@code $domain=a.com|~b.com}, {@code third-party}/{@code first-party}, and request types (script, image, …
 *    known from the browser's Sec-Fetch-Dest header). A rule with any other option is skipped, so it never
 *    blocks more than it says. Top-level pages are never blocked (only the parts they load).
 *  - Hiding rules: {@code ##sel}, {@code a.com,~b.com##sel} (also {@code aa*.com}), {@code #@#} exceptions,
 *    {@code @@||site^$elemhide / $generichide / $document}. Extended syntax (#?#, :has-text, …) is skipped.
 *  - Rewriting rules (AdGuard syntax): {@code ||host/path$replace=/regex/replacement/flags} edits the text of a
 *    matching response (page, JSON, script) before the browser sees it — for ads that come inside the same
 *    answer as the content (YouTube's "adPlacements"). Flags: g = every match, i = ignore case. Inside the
 *    value, {@code \/} and {@code \,} stand for / and , and {@code $1} is a captured group. {@code @@…$replace}
 *    switches rewriting off for matching addresses. Not handed to Samsung Internet (it has no such thing).
 *
 * Built lazily from the files and rebuilt after {@link #invalidate()} (called whenever any rule changes).
 */
final class AdRules {
    // request types, from Sec-Fetch-Dest
    static final int SCRIPT = 1, IMAGE = 2, STYLESHEET = 4, SUBDOCUMENT = 8, XHR = 16, MEDIA = 32, FONT = 64,
            OBJECT = 128, PING = 256, WEBSOCKET = 512, OTHER = 1024, DOCUMENT = 2048;
    private static final int ALL_BUT_DOCUMENT = DOCUMENT - 1;

    private static volatile AdRules current;
    private static volatile boolean dirty = true;

    static void invalidate() { dirty = true; }

    static AdRules get(Context c) {
        AdRules r = current;
        if (r != null && !dirty) return r;
        synchronized (AdRules.class) {
            if (current == null || dirty) {
                dirty = false;
                current = build(c);
            }
            return current;
        }
    }

    /** One request, as far as the proxy can tell. */
    static final class Request {
        final String url, host, pageHost;   // pageHost: from Referer / Origin, or null
        final int type;                     // one of the types above, 0 = unknown
        final Boolean thirdParty;           // null = unknown
        Request(String url, String host, String pageHost, int type, Boolean thirdParty) {
            this.url = url; this.host = host; this.pageHost = pageHost; this.type = type; this.thirdParty = thirdParty;
        }
        boolean isThirdParty() {
            if (thirdParty != null) return thirdParty;
            return pageHost != null && !site(pageHost).equals(site(host));
        }
    }

    private static final class Rule {
        final String text;
        final Pattern pattern;
        int types = ALL_BUT_DOCUMENT;
        boolean typesGiven;
        int party;                          // 0 any, 1 third-party only, 2 first-party only
        String[] domains, notDomains;
        boolean elemhide, generichide;      // exception options
        Pattern find;                       // $replace: what to look for in the response (null = exception)
        String with;
        boolean global;
        Rule(String text, Pattern pattern) { this.text = text; this.pattern = pattern; }

        boolean matches(Request r, int type) {
            if (typesGiven ? (type == 0 || (types & type) == 0) : (type != 0 && (types & type) == 0)) return false;
            if (party == 1 && !r.isThirdParty()) return false;
            if (party == 2 && r.isThirdParty()) return false;
            if (domains != null || notDomains != null) {
                String page = r.pageHost;
                if (page == null) { if (domains != null) return false; }
                else {
                    if (domains != null && !anyMatch(domains, page)) return false;
                    if (notDomains != null && anyMatch(notDomains, page)) return false;
                }
            }
            return pattern.matcher(r.url).find();
        }
    }

    // request rules: "||host…" indexed by host, the rest scanned
    private final Map<String, List<Rule>> blockByHost = new HashMap<>(), allowByHost = new HashMap<>();
    private final List<Rule> blockOther = new ArrayList<>(), allowOther = new ArrayList<>();
    private final List<Rule> pageExceptions = new ArrayList<>();   // $document / $elemhide / $generichide

    // hiding rules
    private final List<String> generic = new ArrayList<>();
    private final List<String[]> specific = new ArrayList<>();      // {domains, selector}
    private final Set<String> genericExcept = new HashSet<>();
    private final List<String[]> specificExcept = new ArrayList<>();
    private final Map<String, String> cssCache = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 200; }
    };
    private final List<Rule> rewrites = new ArrayList<>(), rewriteExceptions = new ArrayList<>();
    int requestRules, hideRules;

    /** One $replace edit for a response. */
    static final class Rewrite {
        final Pattern find;
        final String with, rule;
        final boolean global;
        Rewrite(Rule r) { find = r.find; with = r.with; global = r.global; rule = r.text; }
    }

    // ---------------------------------------------------------------- questions

    /** The rule that blocks this request, or null. */
    String blocks(Request r) {
        if (r.type == DOCUMENT) return null;
        if (pageExcepted(r.pageHost, false, false)) return null;
        Rule hit = find(blockByHost, blockOther, r, r.type);
        if (hit == null) return null;
        return find(allowByHost, allowOther, r, r.type) == null ? hit.text : null;
    }

    /** The $replace edits for this request's response, in rule order (empty: leave it alone). */
    List<Rewrite> rewrites(Request r) {
        if (rewrites.isEmpty() || pageExcepted(r.pageHost, false, false)) return java.util.Collections.emptyList();
        for (Rule e : rewriteExceptions) if (e.matches(r, r.type)) return java.util.Collections.emptyList();
        List<Rewrite> out = new ArrayList<>();
        for (Rule rule : rewrites) if (rule.matches(r, r.type)) out.add(new Rewrite(rule));
        return out;
    }

    /** Style sheet hiding this page's ad elements, or null if there is nothing to hide. */
    String css(String host) {
        synchronized (cssCache) {
            String hit = cssCache.get(host);
            if (hit != null) return hit.isEmpty() ? null : hit;
        }
        Request page = new Request("https://" + host + "/", host, host, DOCUMENT, false);
        if (pageExcepted(page, true, false)) return null;
        boolean noGeneric = pageExcepted(page, false, true);
        Set<String> except = new HashSet<>(genericExcept);
        for (String[] e : specificExcept) if (domainsApply(e[0], host)) except.add(e[1]);
        LinkedHashMap<String, Boolean> sels = new LinkedHashMap<>();
        if (!noGeneric) for (String s : generic) sels.put(s, true);
        for (String[] s : specific) if (domainsApply(s[0], host)) sels.put(s[1], true);
        StringBuilder css = new StringBuilder();
        for (String s : sels.keySet()) {
            if (except.contains(s)) continue;
            css.append(s).append("{display:none!important}\n");    // one rule each: a bad selector only drops itself
        }
        String out = css.toString();
        synchronized (cssCache) { cssCache.put(host, out); }
        return out.isEmpty() ? null : out;
    }

    private boolean pageExcepted(String pageHost, boolean elemhide, boolean generichide) {
        if (pageHost == null || pageExceptions.isEmpty()) return false;
        return pageExcepted(new Request("https://" + pageHost + "/", pageHost, pageHost, DOCUMENT, false), elemhide, generichide);
    }

    /** "@@||site^$document" switches everything off on that page; $elemhide / $generichide only hiding. */
    private boolean pageExcepted(Request page, boolean elemhide, boolean generichide) {
        for (Rule e : pageExceptions) {
            boolean applies = (e.types & DOCUMENT) != 0 || (elemhide && e.elemhide) || (generichide && (e.generichide || e.elemhide));
            if (applies && e.pattern.matcher(page.url).find()) return true;
        }
        return false;
    }

    private static Rule find(Map<String, List<Rule>> byHost, List<Rule> other, Request r, int type) {
        for (String h = r.host; ; ) {
            List<Rule> l = byHost.get(h);
            if (l != null) for (Rule rule : l) if (rule.matches(r, type)) return rule;
            int dot = h.indexOf('.');
            if (dot < 0) break;
            h = h.substring(dot + 1);
        }
        for (Rule rule : other) if (rule.matches(r, type)) return rule;
        return null;
    }

    // ---------------------------------------------------------------- building

    private static AdRules build(Context c) {
        AdRules r = new AdRules();
        try {
            Lists.load(c);
            String mine = Lists.read(c, Lists.BROWSER) + "\n" + Packs.enabledText(c);
            for (String line : mine.split("\n")) r.add(line);
        } catch (IOException ignored) {}
        if (Bundled.isOn(c, Bundled.YOUSLIST)) {
            try (BufferedReader in = Bundled.open(c, Bundled.YOUSLIST)) {
                String line;
                while ((line = in.readLine()) != null) r.add(line);
            } catch (IOException ignored) {}
        }
        // the user's own block / allow lists, as Samsung Internet gets them
        for (String d : Lists.blockSet()) if (!Lists.isAllowed(d)) r.add("||" + d + "^");
        for (String d : Lists.allowSet()) r.add("@@||" + d + "^");
        return r;
    }

    private void add(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("!") || t.startsWith("[")) return;
        int hash = t.indexOf('#');
        if (hash >= 0 && addHiding(t)) return;
        if (t.startsWith("#")) return;                               // '#' notes in browser.txt
        try { addRequest(t); } catch (RuntimeException ignored) {}   // malformed rule: skip it
    }

    /** True if the line was a hiding rule (used or skipped). */
    private boolean addHiding(String t) {
        int at = t.indexOf("##"), ex = t.indexOf("#@#");
        if (t.contains("#?#") || t.contains("#$#") || t.contains("#%#") || t.contains("#@?#") || t.contains("#@$#")) return true;
        boolean exception = ex >= 0 && (at < 0 || ex < at);
        int sep = exception ? ex : at;
        if (sep < 0) return false;
        String domains = t.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        String sel = t.substring(sep + (exception ? 3 : 2)).trim();
        if (sel.isEmpty() || !usableSelector(sel) || domains.contains("/")) return true;
        if (exception) {
            if (domains.isEmpty()) genericExcept.add(sel);
            else specificExcept.add(new String[]{domains, sel});
        } else {
            if (domains.isEmpty()) generic.add(sel);
            else specific.add(new String[]{domains, sel});
            hideRules++;
        }
        return true;
    }

    private static final String[] EXTENDED = {":-abp-", ":has-text(", ":contains(", ":xpath(", ":matches-css", ":upward(",
            ":remove(", ":style(", ":min-text-length(", ":watch-attr(", ":matches-path(", ":others(", ":if(", ":if-not(", ":nth-ancestor("};

    private static boolean usableSelector(String sel) {
        if (sel.contains("{") || sel.contains("}") || sel.contains("<") || sel.contains("\\")) return false;
        String low = sel.toLowerCase(Locale.ROOT);
        for (String e : EXTENDED) if (low.contains(e)) return false;
        return true;
    }

    private void addRequest(String t) {
        boolean exception = t.startsWith("@@");
        if (exception) t = t.substring(2);
        int rp = replaceAt(t);
        if (rp > 0) { addRewrite(t, rp, exception); return; }
        String options = null;
        int dollar = t.lastIndexOf('$');
        if (dollar >= 0 && OPTIONS.matcher(t.substring(dollar + 1)).matches()) {
            options = t.substring(dollar + 1);
            t = t.substring(0, dollar);
        }
        if (t.length() > 1 && t.startsWith("/") && t.endsWith("/")) return;   // regex rules: not supported
        if (t.isEmpty() || t.equals("*")) {
            if (options == null) return;
            t = "*";
        }
        boolean matchCase = options != null && options.toLowerCase(Locale.ROOT).contains("match-case");
        Rule rule = new Rule((exception ? "@@" : "") + t + (options == null ? "" : "$" + options), compile(t, matchCase));
        if (options != null && !applyOptions(rule, options, exception)) return;
        if (rule.typesGiven && !exception && (rule.types & DOCUMENT) != 0) {
            rule.types &= ~DOCUMENT;                                 // never block a whole page
            if (rule.types == 0) return;
        }
        if (exception && (rule.elemhide || rule.generichide || (rule.typesGiven && (rule.types & DOCUMENT) != 0))) {
            pageExceptions.add(rule);
            if (!rule.typesGiven || rule.types == DOCUMENT) return;
        }
        String host = anchoredHost(t);
        Map<String, List<Rule>> index = exception ? allowByHost : blockByHost;
        if (host != null) {
            List<Rule> l = index.get(host);
            if (l == null) index.put(host, l = new ArrayList<>(1));
            l.add(rule);
        } else {
            (exception ? allowOther : blockOther).add(rule);
        }
        if (!exception) requestRules++;
    }

    /** Index of "replace" in "…$replace=…" / "…,replace…" (the option's name), or -1. */
    private static int replaceAt(String t) {
        for (int i = t.indexOf("replace"); i > 0; i = t.indexOf("replace", i + 1)) {
            char before = t.charAt(i - 1);
            int end = i + 7;
            if ((before == '$' || before == ',') && (end == t.length() || t.charAt(end) == '=' || t.charAt(end) == ',')) return i;
        }
        return -1;
    }

    /** "pattern$opts,replace=/re/with/flags,more" (the value's '/' and ',' escaped with a backslash). */
    private void addRewrite(String t, int rp, boolean exception) {
        int dollar = t.lastIndexOf('$', rp - 1);
        if (dollar < 0) return;
        String pattern = t.substring(0, dollar);
        StringBuilder opts = new StringBuilder(t.substring(dollar + 1, rp - (t.charAt(rp - 1) == ',' ? 1 : 0)));
        String rest = t.substring(rp + 7);
        Pattern find = null;
        String with = null;
        boolean global = false;
        if (rest.startsWith("=")) {
            String v = rest.substring(1);
            if (!v.startsWith("/")) return;
            List<String> parts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            int i = 1;
            for (; i < v.length() && parts.size() < 2; i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length() && (v.charAt(i + 1) == '/' || v.charAt(i + 1) == ',')) { cur.append(v.charAt(++i)); continue; }
                if (c == '/') { parts.add(cur.toString()); cur.setLength(0); continue; }
                cur.append(c);
            }
            if (parts.size() < 2) return;
            int comma = v.indexOf(',', i);
            String flags = comma < 0 ? v.substring(i) : v.substring(i, comma);
            if (comma >= 0) opts.append(opts.length() > 0 ? "," : "").append(v.substring(comma + 1));
            global = flags.contains("g");
            try {
                find = Pattern.compile(parts.get(0), flags.contains("i") ? Pattern.CASE_INSENSITIVE : 0);
            } catch (RuntimeException e) {
                return;                                              // bad regex: skip the rule
            }
            with = parts.get(1);
        } else if (!exception) {
            return;                                                  // "$replace" without a value only makes sense as @@
        } else if (rest.startsWith(",")) {
            opts.append(opts.length() > 0 ? "," : "").append(rest.substring(1));
        }
        if (pattern.isEmpty()) pattern = "*";
        Rule rule = new Rule((exception ? "@@" : "") + t, compile(pattern, false));
        if (opts.length() > 0 && !applyOptions(rule, opts.toString(), exception)) return;
        if (!rule.typesGiven) rule.types |= DOCUMENT;                // pages can be rewritten too
        rule.find = find;
        rule.with = with;
        rule.global = global;
        (exception ? rewriteExceptions : rewrites).add(rule);
    }

    private static final Pattern OPTIONS = Pattern.compile("[A-Za-z0-9_~,=|.*\\-]+");

    /** False if the rule uses an option we cannot honour (then it is skipped entirely). */
    private static boolean applyOptions(Rule r, String options, boolean exception) {
        int types = 0, notTypes = 0;
        for (String o : options.split(",")) {
            String opt = o.trim().toLowerCase(Locale.ROOT);
            if (opt.isEmpty()) continue;
            boolean not = opt.startsWith("~");
            String name = not ? opt.substring(1) : opt;
            if (name.startsWith("domain=")) {
                List<String> yes = new ArrayList<>(), no = new ArrayList<>();
                for (String d : name.substring(7).split("\\|")) {
                    if (d.startsWith("~")) { if (d.length() > 1) no.add(d.substring(1)); }
                    else if (!d.isEmpty()) yes.add(d);
                }
                if (!yes.isEmpty()) r.domains = yes.toArray(new String[0]);
                if (!no.isEmpty()) r.notDomains = no.toArray(new String[0]);
                continue;
            }
            switch (name) {
                case "third-party": case "3p": r.party = not ? 2 : 1; continue;
                case "first-party": case "1p": r.party = not ? 1 : 2; continue;
                case "match-case": case "important": continue;
                case "elemhide": case "ehide": if (!exception) return false; r.elemhide = true; continue;
                case "generichide": case "ghide": if (!exception) return false; r.generichide = true; continue;
                default: break;
            }
            int bit = typeBit(name);
            if (bit == 0) return false;                              // csp, redirect, popup, removeparam, …
            if (not) notTypes |= bit; else types |= bit;
        }
        if (types != 0) { r.types = types; r.typesGiven = true; }
        else if (notTypes != 0) { r.types = ALL_BUT_DOCUMENT & ~notTypes; r.typesGiven = true; }
        else if (r.elemhide || r.generichide) r.types = 0;           // only switches hiding off
        return true;
    }

    private static int typeBit(String name) {
        switch (name) {
            case "script": return SCRIPT;
            case "image": return IMAGE;
            case "stylesheet": case "css": return STYLESHEET;
            case "subdocument": case "frame": return SUBDOCUMENT;
            case "xmlhttprequest": case "xhr": return XHR;
            case "media": return MEDIA;
            case "font": return FONT;
            case "object": return OBJECT;
            case "ping": return PING;
            case "websocket": return WEBSOCKET;
            case "other": return OTHER;
            case "document": case "doc": return DOCUMENT;
            default: return 0;
        }
    }

    /** "||ads.example.com^…" -> "ads.example.com" (index key), or null when there is no plain host. */
    private static String anchoredHost(String t) {
        if (!t.startsWith("||")) return null;
        int end = 2;
        while (end < t.length() && "^/*:|?$".indexOf(t.charAt(end)) < 0) end++;
        if (end < t.length() && t.charAt(end) == '*') return null;   // "||ads*.com" – not a whole host
        String h = t.substring(2, end).toLowerCase(Locale.ROOT);
        return h.isEmpty() || h.endsWith(".") ? null : h;
    }

    /** Adblock pattern -> regex: ||=scheme+any subdomain, |=edge, ^=separator, *=anything. */
    static Pattern compile(String t, boolean matchCase) {
        StringBuilder re = new StringBuilder();
        int i = 0, end = t.length();
        if (t.startsWith("||")) { re.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?"); i = 2; }
        else if (t.startsWith("|")) { re.append('^'); i = 1; }
        boolean tail = end > i && t.endsWith("|");
        if (tail) end--;
        for (; i < end; i++) {
            char ch = t.charAt(i);
            if (ch == '*') re.append(".*");
            else if (ch == '^') re.append("(?:[^\\w\\-.%]|$)");
            else if ("\\.+?()[]{}|$".indexOf(ch) >= 0) re.append('\\').append(ch);
            else re.append(ch);
        }
        if (tail) re.append('$');
        return Pattern.compile(re.toString(), matchCase ? 0 : Pattern.CASE_INSENSITIVE);
    }

    // ---------------------------------------------------------------- domains

    /** "a.com,~b.a.com" applies to host? (a.com and its subdomains, minus excluded ones; aa*.com patterns too) */
    private static boolean domainsApply(String list, String host) {
        boolean hit = false;
        for (String d : list.split(",")) {
            d = d.trim();
            boolean not = d.startsWith("~");
            if (not) d = d.substring(1);
            if (d.isEmpty() || !siteMatches(d, host)) continue;
            if (not) return false;
            hit = true;
        }
        return hit;
    }

    private static boolean anyMatch(String[] domains, String host) {
        for (String d : domains) if (siteMatches(d, host)) return true;
        return false;
    }

    private static boolean siteMatches(String d, String host) {
        if (!Wildcards.isPattern(d)) return host.equals(d) || host.endsWith("." + d);
        Pattern p = Wildcards.compile(d);
        for (String h = host; h.indexOf('.') > 0; h = h.substring(h.indexOf('.') + 1)) {
            if (p.matcher(h).matches()) return true;
        }
        return false;
    }

    private static final Set<String> SECOND_LEVEL = new HashSet<>(java.util.Arrays.asList(
            "co", "or", "ne", "go", "ac", "re", "pe", "com", "net", "org", "gov", "edu", "kr"));

    /** Rough registrable domain: news.example.co.kr -> example.co.kr, a.b.example.com -> example.com. */
    static String site(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        int keep = p[p.length - 1].length() == 2 && SECOND_LEVEL.contains(p[p.length - 2]) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) sb.append(i > p.length - keep ? "." : "").append(p[i]);
        return sb.toString();
    }
}
