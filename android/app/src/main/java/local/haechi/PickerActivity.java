package local.haechi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "광고 요소 골라 숨기기": an in-app browser (WebView) where you tap an ad, widen/narrow the pick,
 * and add it to browser.txt as "site##selector" for Samsung Internet. Also a share target, so a page
 * open in Samsung Internet can be shared straight here. The WebView's page loads are the only network
 * traffic of this screen; the picked selector never leaves the phone.
 */
public class PickerActivity extends Activity {
    static final String EXTRA_URL = "url";
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private EditText address, selector;
    private TextView info;
    private Button pickButton, previewButton, backButton, forwardButton;
    private EditText site;
    private LinearLayout pickPane;
    private String script;
    private boolean picking, previewing, settingText, siteFilled, byClassNames = true;
    private int matches;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("광고 요소 골라 숨기기");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);   // ← leaves the screen
        try { Lists.load(this); } catch (IOException ignored) {}
        try { script = readAsset(this, "picker.js"); } catch (IOException e) { script = ""; }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 8);

        // ---- address bar: page history (◀ ▶ ⟳) is separate from the phone's back key, which leaves this screen ----
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 8));
        backButton = small("◀", v -> { if (web.canGoBack()) web.goBack(); });
        forwardButton = small("▶", v -> { if (web.canGoForward()) web.goForward(); });
        bar.addView(backButton);
        bar.addView(forwardButton);
        bar.addView(small("⟳", v -> web.reload()));
        address = Ui.field(this, new EditText(this));
        address.setHint("주소 (예: namu.wiki)");
        address.setSingleLine(true);
        address.setTextSize(14);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setOnEditorActionListener((v, id, ev) -> { go(); return true; });
        bar.addView(address, Ui.weight1());
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        goParams.setMarginStart(Ui.dp(this, 6));
        bar.addView(Ui.primary(this, "이동", v -> go()), goParams);
        col.addView(bar);

        // ---- page ----
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        // Look like Samsung Internet so sites serve the same layout the rules will run against.
        s.setUserAgentString(s.getUserAgentString().replace("; wv)", ")").replace("Version/4.0 ", "")
                .replace("Chrome/", "SamsungBrowser/27.0 Chrome/"));
        web.addJavascriptInterface(new Bridge(), "HaechiPicker");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                return !"http".equals(scheme) && !"https".equals(scheme);   // no intent://, market://, ...
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                address.setText(url);
                clearPick();
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                inject();   // early, so taps while the rest still loads already go to the picker
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                address.setText(url);   // in-page navigation (namu.wiki etc.) fires no page events
                updateHistoryButtons();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                address.setText(url);
                updateHistoryButtons();
                inject();
            }
        });
        col.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- picker controls ----
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        android.graphics.drawable.GradientDrawable sheet = Ui.rounded(Ui.CARD, 0);
        float r = Ui.dp(this, 24);
        sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});   // bottom sheet: round top corners
        panel.setBackground(sheet);
        panel.setElevation(Ui.dp(this, 8));

        LinearLayout top = Ui.row(this);
        pickButton = Ui.button(this, "", v -> setPicking(!picking));
        top.addView(pickButton, Ui.weight1());
        top.addView(Ui.button(this, "▲ 넓게", v -> js("__haechi.up()")));
        top.addView(Ui.button(this, "▼ 좁게", v -> js("__haechi.down()")));
        top.addView(Ui.round(this, "?", v -> startActivity(new Intent(this, GuideActivity.class))), Ui.after(this));
        panel.addView(top);

        info = Ui.text(this, "", 13);
        panel.addView(info);

        pickPane = new LinearLayout(this);
        pickPane.setOrientation(LinearLayout.VERTICAL);
        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton byClass = Ui.chip(this, new RadioButton(this)), byShape = Ui.chip(this, new RadioButton(this));
        byClass.setText("클래스 이름으로");
        byShape.setText("구조로 (이름이 무작위인 사이트)");
        byClass.setId(View.generateViewId());
        byShape.setId(View.generateViewId());
        byClass.setOnClickListener(v -> setMode(true));
        byShape.setOnClickListener(v -> setMode(false));
        mode.addView(byClass, Ui.chipParams(this));
        mode.addView(byShape, Ui.chipParams(this));
        mode.check(byClass.getId());
        pickPane.addView(mode);

        selector = Ui.field(this, new EditText(this));
        selector.setTypeface(Typeface.MONOSPACE);
        selector.setTextSize(13);
        selector.setMaxLines(3);
        selector.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        selector.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) {
                if (settingText) return;
                handler.removeCallbacks(recount);
                handler.postDelayed(recount, 300);   // typed by hand: re-check what it matches
            }
        });
        pickPane.addView(selector);

        // Which sites: this one (default), empty = every site, or a wildcard like aa*.com for mirrors.
        LinearLayout siteRow = Ui.row(this);
        siteRow.addView(Ui.text(this, "사이트 ", 14));
        site = Ui.field(this, new EditText(this));
        site.setHint("비우면 모든 사이트");
        site.setSingleLine(true);
        site.setTextSize(14);
        site.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        siteRow.addView(site, Ui.weight1());
        siteRow.addView(Ui.button(this, "숫자 → *", v -> {
            String w = Wildcards.suggest(site.getText().toString().trim());
            if (w == null) toast("사이트 이름에 숫자가 없습니다. 직접 * 를 넣어도 됩니다 (예: aa*.com)");
            else site.setText(w);
        }));
        pickPane.addView(siteRow);
        pickPane.addView(Ui.text(this, "* = 글자·숫자 아무거나 (aa*.com → aa1.com, aa2.com …). "
                + "새 주소는 해치가 DNS로 처음 볼 때 규칙에 더해집니다.", 12));

        LinearLayout bottom = Ui.row(this);
        previewButton = Ui.button(this, "", v -> setPreview(!previewing));
        bottom.addView(previewButton, Ui.weight1());
        bottom.addView(Ui.primary(this, "규칙 추가", v -> addRule()), Ui.weight1());
        pickPane.addView(bottom);
        panel.addView(pickPane);
        col.addView(panel);
        setContentView(col);

        clearPick();
        setPicking(false);
        updateHistoryButtons();
        String url = startUrl(getIntent());
        if (url != null) load(url);
        else address.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = startUrl(intent);
        if (url != null) load(url);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        // The phone's back key goes back a page like a browser. Only on the first page does it work the
        // screen, and leaving takes a second press, so going back never closes the picker by surprise.
        if (code != KeyEvent.KEYCODE_BACK) return super.onKeyDown(code, ev);
        if (web.canGoBack()) { web.goBack(); return true; }
        if (picking) { setPicking(false); return true; }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBack > 2000) {
            lastBack = now;
            toast("첫 페이지입니다. 한 번 더 누르면 닫힙니다");
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    private long lastBack;

    /** ← in the title bar: leave the screen at once (the phone's back key goes back a page). */
    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void updateHistoryButtons() {
        backButton.setEnabled(web.canGoBack());
        forwardButton.setEnabled(web.canGoForward());
    }

    /** Narrow button for the address bar. */
    private Button small(String label, View.OnClickListener l) {
        return Ui.round(this, label, l);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        web.destroy();
        super.onDestroy();
    }

    /** Shared text from another app, or EXTRA_URL from our own screens. */
    private static String startUrl(Intent i) {
        if (i == null) return null;
        String u = i.getStringExtra(EXTRA_URL);
        if (u != null) return u;
        CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        if (m.find()) return m.group();
        String host = Domains.extractHost(text);
        return host == null ? null : "https://" + host + "/";
    }

    private void go() {
        String u = address.getText().toString().trim();
        if (u.isEmpty()) return;
        if (!u.contains("://")) u = "https://" + u;
        load(u);
        address.clearFocus();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void load(String url) {
        address.setText(url);
        web.loadUrl(url);
    }

    // ---------------------------------------------------------------- page side

    /** Picker script + the rules browser.txt already has for this site (so they show as hidden). */
    private void inject() {
        Wildcards.seen(this, host());   // a mirror opened here counts too (even with the filter off)
        List<String> rules = rulesFor(this, host());
        web.evaluateJavascript(script + ";__haechi.setRules(" + new JSONArray(rules) + ");"
                + "__haechi.mode(" + byClassNames + ");" + (picking ? "__haechi.start();" : ""), null);
        if (!rules.isEmpty() && matches == 0 && !picking)
            info.setText("이 사이트에 이미 있는 숨김 규칙 " + rules.size() + "개 적용 중");
    }

    private void js(String code) {
        web.evaluateJavascript("window.__haechi&&" + code, null);
    }

    private void setMode(boolean byClass) {
        byClassNames = byClass;
        js("__haechi.mode(" + byClass + ")");
    }

    private void setPicking(boolean on) {
        picking = on;
        pickButton.setText(on ? "■ 고르기 끝" : "● 요소 고르기");
        js(on ? "__haechi.start()" : "__haechi.stop()");
        if (on && matches == 0) info.setText("광고를 누르세요. 화면은 그대로 스크롤됩니다.");
    }

    private void setPreview(boolean on) {
        previewing = on;
        previewButton.setText(on ? "숨김 취소" : "미리 숨기기");
        js("__haechi.preview(" + JSONObject.quote(sel()) + "," + on + ")");
    }

    private void clearPick() {
        matches = 0;
        previewing = false;
        settingText = true;
        selector.setText("");
        settingText = false;
        previewButton.setText("미리 숨기기");
        siteFilled = false;
        info.setText("");
        pickPane.setVisibility(View.GONE);
        js("__haechi.clear()");
    }

    private final Runnable recount = () -> web.evaluateJavascript(
            "window.__haechi?__haechi.show(" + JSONObject.quote(sel()) + "):-2", v -> {
                int n;
                try { n = Integer.parseInt(v); } catch (NumberFormatException e) { n = -1; }
                matches = Math.max(n, 0);
                info.setText(n < 0 ? "선택자를 이해할 수 없습니다" : "일치 " + n + "개");
                if (previewing) setPreview(true);
            });

    /** Called from picker.js (on a binder thread). It only fills in the fields; adding still needs a tap. */
    private final class Bridge {
        @JavascriptInterface
        public void picked(String sel, int count, String what, int up) {
            handler.post(() -> {
                if (isDestroyed()) return;
                matches = count;
                settingText = true;
                selector.setText(sel);
                settingText = false;
                info.setText(what + (up > 0 ? " (" + up + "단계 위)" : "") + " · 일치 " + count + "개");
                if (!siteFilled) { site.setText(ruleDomain()); siteFilled = true; }   // keep the user's edit
                pickPane.setVisibility(View.VISIBLE);
                if (previewing) setPreview(true);
            });
        }
    }

    // ---------------------------------------------------------------- rule

    private String sel() {
        return selector.getText().toString().replace('\n', ' ').trim();
    }

    private String host() {
        String u = web.getUrl();
        String h = u == null ? null : Uri.parse(u).getHost();
        return h == null ? "" : h.toLowerCase(Locale.ROOT);
    }

    /** "www.namu.wiki" -> "namu.wiki" (the rule covers subdomains anyway). */
    private String ruleDomain() {
        String h = host();
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private void addRule() {
        String s = sel();
        if (s.isEmpty() || matches < 1) { toast("페이지에서 일치하는 요소가 없습니다"); return; }
        if (s.contains("{") || s.contains("}")) { toast("선택자에 { } 는 쓸 수 없습니다"); return; }
        String domain = site.getText().toString().trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (!domain.matches("[a-z0-9.*,~_-]*")) { toast("사이트에 쓸 수 없는 글자가 있습니다"); return; }
        if (Wildcards.isPattern(domain) && rulesFor(domain + "##x", host()) == null)
            toast("주의: 지금 페이지(" + host() + ")는 " + domain + " 에 맞지 않습니다");
        String rule = domain + "##" + s;
        try {
            for (String line : Lists.read(this, Lists.BROWSER).split("\n")) {
                if (line.trim().equals(rule)) { toast("이미 있는 규칙입니다"); return; }
            }
            Lists.appendAll(this, Lists.BROWSER, Collections.singletonList(rule));   // also notifies Samsung Internet
            Wildcards.seen(this, host());   // wildcard rule: this page's domain is its first match
        } catch (IOException e) {
            toast("실패: " + e.getMessage());
            return;
        }
        toast("추가함 · " + rule);
        setPicking(false);
        clearPick();
        inject();   // re-apply: the new rule now hides it here too
    }

    /**
     * Element-hiding selectors from browser.txt that apply to {@code host}: "##sel" everywhere,
     * "a.com,b.com##sel" on those sites and their subdomains, "~a.com" excluded. Exceptions (#@#) and
     * extended syntax (#?#, #$#) are skipped.
     */
    static List<String> rulesFor(Context c, String host) {
        List<String> out = new ArrayList<>();
        String text;
        try { text = Lists.read(c, Lists.BROWSER); } catch (IOException e) { return out; }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("!")) continue;
            String sel = rulesFor(t, host);
            if (sel != null) out.add(sel);
        }
        return out;
    }

    /** The selector of one "sites##sel" line if it applies to {@code host}, else null. */
    private static String rulesFor(String line, String host) {
        int at = line.indexOf("##");
        if (at < 0 || line.length() == at + 2) return null;
        String domains = line.substring(0, at), sel = line.substring(at + 2);
        if (domains.contains("#") || sel.contains("{") || sel.contains("}")) return null;
        boolean hit = domains.isEmpty(), excluded = false;
        for (String d : domains.split(",")) {
            d = d.trim().toLowerCase(Locale.ROOT);
            boolean not = d.startsWith("~");
            if (not) d = d.substring(1);
            if (d.isEmpty() || !siteMatches(d, host)) continue;
            if (not) excluded = true; else hit = true;
        }
        return hit && !excluded ? sel : null;
    }

    /** "namu.wiki" matches itself and subdomains; "aa*.com" also matches www.aa7.com etc. */
    private static boolean siteMatches(String d, String host) {
        if (!Wildcards.isPattern(d)) return host.equals(d) || host.endsWith("." + d);
        java.util.regex.Pattern p = Wildcards.compile(d);
        for (String h = host; h.indexOf('.') > 0; h = h.substring(h.indexOf('.') + 1)) {
            if (p.matcher(h).matches()) return true;
        }
        return false;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String readAsset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
