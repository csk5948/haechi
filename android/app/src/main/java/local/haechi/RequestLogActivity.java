package local.haechi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "요청 기록" screen: every request HTTPS filtering saw inside browser connections, newest first — the full
 * address, what kind of request it was, which page asked, and whether a rule blocked it. Tap one to turn it
 * into a blocking rule (or an exception) in 브라우저 규칙. Data lives only in memory (see RequestLog).
 */
public class RequestLogActivity extends Activity {
    private static final int RED = 0xFFD32F2F, GREEN = 0xFF2E7D32, GRAY = 0xFF888888;
    private static final int ALL = 0, ONLY_BLOCKED = 1, ONLY_PASSED = 2, ONLY_THIRD = 3, ONLY_SUSPECT = 4;

    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    private List<RequestLog.Entry> items = new ArrayList<>();
    private int filter = ALL;
    private String shownKey;
    private boolean onlyBodies;                                       // "응답 내용 있는 것만"
    // "내용에서 찾기": ids of kept answers whose text holds contentQuery (null = not searching contents)
    private java.util.Set<Long> contentHits;
    private String contentQuery;
    private int contentRun;
    private TextView contentState;
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    private TextView state;
    private EditText search;
    private ListView list;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("요청 기록 (HTTPS)");
        Labels.load(this);
        LinearLayout col = Ui.column(this);

        LinearLayout buttons = Ui.row(this);
        Button rec = Ui.primary(this, "● 녹화 시작", v -> { RequestLog.startNew(); refresh(); });
        Button stop = Ui.button(this, "■ 정지", v -> { RequestLog.pause(); refresh(); });
        Button clear = Ui.button(this, "지우기", v -> { RequestLog.clear(); refresh(); });
        buttons.addView(rec, Ui.weight1());
        buttons.addView(stop, Ui.weight1());
        buttons.addView(clear, Ui.weight1());
        col.addView(buttons);

        state = Ui.text(this, "", 13);
        col.addView(state);

        LinearLayout searchRow = Ui.row(this);
        search = Ui.field(this, new EditText(this));
        search.setHint("주소 검색 · 내용은 오른쪽 버튼");
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, id, ev) -> { searchContents(); return true; });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (contentHits != null && !s.toString().trim().equals(contentQuery)) {   // new words: old content hits no longer apply
                    contentHits = null;
                    contentState.setVisibility(View.GONE);
                }
                refresh();
            }
        });
        searchRow.addView(search, Ui.weight1());
        searchRow.addView(Ui.primary(this, "내용 찾기", v -> searchContents()), wrapAfter());
        col.addView(searchRow, Ui.gap(this, 4));
        contentState = Ui.text(this, "", 12);
        contentState.setVisibility(View.GONE);
        col.addView(contentState);

        // works together with the chips below: e.g. "다른 사이트 것만" + this
        android.widget.Switch bodies = Ui.toggle(this);
        bodies.setText("응답 내용이 보관된 것만");
        bodies.setTextColor(Ui.TEXT);
        bodies.setOnCheckedChangeListener((v, on) -> { onlyBodies = on; refresh(); });
        col.addView(bodies, Ui.gap(this, 6));
        android.widget.Switch fresh = Ui.toggle(this);
        fresh.setText("녹화 중엔 브라우저 캐시 안 쓰게 하기");
        fresh.setTextColor(Ui.TEXT);
        fresh.setChecked(RequestLog.noCache);
        fresh.setOnCheckedChangeListener((v, on) -> RequestLog.noCache = on);
        col.addView(fresh);
        col.addView(Ui.text(this, "크롬은 한 번 받은 스크립트·이미지를 저장해 두고 다시 묻지 않아 기록에 안 나와요. 이걸 켜면 녹화 중에 받은 것은 "
                + "저장하지 못하게 해 매번 보입니다. 이미 저장된 것은 크롬 설정 › 인터넷 사용 기록 삭제 › '캐시된 이미지 및 파일'을 "
                + "한 번 지운 뒤 다시 여세요.", 12));

        RadioGroup filters = new RadioGroup(this);
        filters.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"전체", "막은 것만", "통과만", "다른 사이트 것만", "광고·추적 의심만"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = Ui.chip(this, new RadioButton(this));
            r.setText(labels[i]);
            r.setId(View.generateViewId());
            final int f = i;
            r.setOnClickListener(v -> { filter = f; refresh(); });
            filters.addView(r, Ui.chipParams(this));
            if (i == ALL) filters.check(r.getId());
        }
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        filterScroll.addView(filters);
        col.addView(filterScroll);

        list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showActions(items.get(pos)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int s) { scrollState = s; }
            @Override public void onScroll(AbsListView v, int a, int b, int c) {}
        });
        list.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 22)));
        list.setClipToOutline(true);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFFEDF1EF));
        list.setDividerHeight(1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(this, 8);
        col.addView(list, lp);
        setContentView(col);
    }

    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(ticker); }

    private void refresh() {
        boolean filtering = BlockerService.running && Settings.httpsFilter(this);
        state.setText((RequestLog.isRecording() ? "● 녹화 중" : "■ 기록 안 함 · '● 녹화 시작'을 누르면 그때부터 기록 (차단은 계속 동작)")
                + (filtering ? "" : " · HTTPS 필터링이 꺼져 있어 기록되지 않음")
                + " · 녹화 중엔 응답 내용(글자)도 보관 · 메모리에만 있고 앱이 끝나면 사라짐");
        if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) return;

        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String key = RequestLog.version + "|" + filter + "|" + q + "|" + contentRun + "|" + onlyBodies;
        if (key.equals(shownKey)) {                                  // nothing new: only repaint (status may have changed)
            adapter.notifyDataSetChanged();
            return;
        }
        shownKey = key;
        List<RequestLog.Entry> next = new ArrayList<>();
        for (RequestLog.Entry e : RequestLog.snapshot()) {
            boolean inText = contentHits != null && contentHits.contains(e.id);
            if (!q.isEmpty() && !inText && !e.url.toLowerCase(Locale.ROOT).contains(q)
                    && (e.page == null || !e.page.contains(q))) continue;
            if (onlyBodies && e.body == null) continue;
            if (filter == ONLY_BLOCKED && e.result != RequestLog.BLOCKED) continue;
            if (filter == ONLY_PASSED && e.result == RequestLog.BLOCKED) continue;
            if (filter == ONLY_THIRD && (e.page == null || AdRules.site(e.page).equals(AdRules.site(e.host)))) continue;
            if (filter == ONLY_SUSPECT && !Labels.isAdOrTrack(Labels.of(e.host)) && !suspectPath(e.url)) continue;
            next.add(e);
        }

        // Keep the row the user is looking at in place when new rows appear above it.
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int offset = top == null ? 0 : top.getTop();
        RequestLog.Entry anchor = (first > 0 || offset < 0) && first < items.size() ? items.get(first) : null;

        items = next;
        adapter.notifyDataSetChanged();

        if (anchor != null) {
            int i = items.indexOf(anchor);
            if (i >= 0) list.setSelectionFromTop(i, offset);
        }
    }

    private LinearLayout.LayoutParams wrapAfter() { return wrapAfter(this); }

    /** Button beside a text field: as wide as its label, small gap before it. */
    static LinearLayout.LayoutParams wrapAfter(android.content.Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(c, 6));
        return lp;
    }

    /**
     * Looks for the search words inside every kept answer (unpacking each: off the main thread), so a word that
     * is only in the content — "adPlacements" — shows which requests carry it. Case is ignored.
     */
    private void searchContents() {
        String q = search.getText().toString().trim();
        getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
        if (q.isEmpty()) {
            Toast.makeText(this, "찾을 글자를 넣으세요 (예: adPlacements)", Toast.LENGTH_SHORT).show();
            return;
        }
        contentState.setVisibility(View.VISIBLE);
        contentState.setText("응답 내용에서 '" + q + "' 찾는 중…");
        int run = ++contentRun;
        List<RequestLog.Entry> all = RequestLog.snapshot();
        new Thread(() -> {
            java.util.Set<Long> found = new java.util.HashSet<>();
            int kept = 0;
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(q),
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
            for (RequestLog.Entry e : all) {
                if (e.body == null) continue;
                kept++;
                try {
                    if (p.matcher(RequestLog.text(e)).find()) found.add(e.id);
                } catch (IOException | RuntimeException ignored) {}
            }
            int n = kept;
            handler.post(() -> {
                if (run != contentRun) return;                       // a newer search started
                contentHits = found;
                contentQuery = q;
                contentRun++;
                contentState.setText("응답 내용에 '" + q + "'가 든 요청 " + found.size() + "개 (보관된 응답 " + n + "개 중)"
                        + (n == 0 ? " · 녹화 중에 그 페이지를 다시 열어야 내용이 보관됩니다" : "") + " · 표시: 📄 내용에 있음");
                refresh();
            });
        }, "content-search").start();
    }

    /** Ad-ish words as whole path pieces (same idea as Labels: "ads" yes, "downloads" no). */
    private static boolean suspectPath(String url) {
        int s = url.indexOf('/', url.indexOf("://") + 3);
        if (s < 0) return false;
        for (String w : url.substring(s).toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            switch (w) {
                case "ad": case "ads": case "adv": case "advert": case "adserver": case "banner": case "banners":
                case "track": case "tracker": case "tracking": case "pixel": case "beacon": case "analytics":
                case "sponsor": case "sponsored": case "promotion": case "popup": case "doubleclick":
                    return true;
                default:
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- tap actions

    private void showActions(RequestLog.Entry e) {
        String host = e.host;
        String path = pathOf(e.url);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (e.body != null) {
            labels.add("응답 내용 보기 · 찾아서 지우기 규칙 만들기");
            String carry = contentHits != null && contentHits.contains(e.id) ? contentQuery : null;   // open already searched
            actions.add(() -> startActivity(new android.content.Intent(this, BodyActivity.class)
                    .putExtra(BodyActivity.EXTRA_ID, e.id).putExtra(BodyActivity.EXTRA_QUERY, carry)));
        } else if (e.result == RequestLog.TUNNEL) {
            labels.add("이 연결은 풀지 않아 안의 요청을 볼 수 없음 (" + e.rule + ")");
            actions.add(() -> {});
        } else if (e.result != RequestLog.BLOCKED) {
            labels.add("응답 내용 없음: " + (e.bodyNote != null ? e.bodyNote : e.status == 0 ? "아직 응답 전" : "보관 안 함"));
            actions.add(() -> {});
        }
        if (e.requestHead != null || e.responseHead != null) {
            labels.add("머리글 보기");
            actions.add(() -> {
                String s = "보낸 요청\n" + (e.requestHead == null ? "-" : e.requestHead) + "\n받은 응답\n" + (e.responseHead == null ? "-" : e.responseHead);
                TextView t = Ui.text(this, s, 12);
                t.setTypeface(android.graphics.Typeface.MONOSPACE);
                t.setTextIsSelectable(true);
                int pad = Ui.dp(this, 16);
                t.setPadding(pad, pad, pad, pad);
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.addView(t);
                new AlertDialog.Builder(this).setTitle("머리글 (쿠키·로그인 값은 숨김)").setView(sv).setNegativeButton("닫기", null).show();
            });
        }
        if (e.result == RequestLog.BLOCKED) {
            if (path.length() > 1) {
                String r = "@@||" + host + path + "^";
                labels.add("이 주소는 허용: " + r);
                actions.add(() -> editRule(r, e, true));
            }
            String r = "@@||" + host + "^";
            labels.add("이 호스트는 모두 허용: " + r);
            actions.add(() -> editRule(r, e, true));
            labels.add("막은 규칙 복사");
            actions.add(() -> copy(e.rule));
        } else {
            if (path.length() > 1) {
                String r = "||" + host + path + "^";
                labels.add("이 주소 막기: " + r);
                actions.add(() -> editRule(r, e, false));
            }
            int dir = path.lastIndexOf('/');
            if (dir > 0) {
                String r = "||" + host + path.substring(0, dir + 1);
                labels.add("이 폴더 막기: " + r);
                actions.add(() -> editRule(r, e, false));
            }
            String r = "||" + host + "^";
            labels.add("이 호스트에서 오는 것 모두 막기: " + r);
            actions.add(() -> editRule(r, e, false));
            String site = AdRules.site(host);
            if (!site.equals(host)) {                                // stg.ads.com, c.ads.com, … all at once
                String rs = "||" + site + "^";
                labels.add("이 사이트의 모든 호스트 막기: " + rs);
                actions.add(() -> editRule(rs, e, false));
            }
        }
        labels.add("주소 복사");
        actions.add(() -> copy(e.url));

        StringBuilder info = new StringBuilder(e.url.length() > 400 ? e.url.substring(0, 400) + "…" : e.url)
                .append("\n\n종류: ").append(RequestLog.typeName(e.type))
                .append(" · 상태: ").append(resultName(e))
                .append(e.status > 0 ? " (HTTP " + e.status + ")" : "")
                .append("\n요청한 페이지: ").append(e.page == null ? "알 수 없음" : e.page);
        if (e.rule != null) info.append(e.result == RequestLog.TUNNEL ? "\n이유: "
                : e.result == RequestLog.BLOCKED ? "\n막은 규칙: " : "\n고친 규칙: ").append(e.rule);
        String tag = Labels.of(host);
        if (tag != null) info.append("\n분류: ").append(tag);
        TextView header = Ui.text(this, info.toString(), 14);
        header.setTextIsSelectable(true);
        int p = Ui.dp(this, 20);
        header.setPadding(p, p, p, Ui.dp(this, 8));

        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("닫기", null)
                .show();
    }

    /** Lets the user adjust the rule (and limit it to the asking site), then adds it to 브라우저 규칙. */
    private void editRule(String rule, RequestLog.Entry e, boolean exception) {
        LinearLayout box = Ui.column(this);
        EditText field = Ui.field(this, new EditText(this));
        field.setText(rule);
        field.setTypeface(android.graphics.Typeface.MONOSPACE);
        field.setTextSize(13);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(field);
        CheckBox onlyHere = new CheckBox(this);
        if (e.page != null) {
            onlyHere.setText(e.page + " 에서 열린 것만 (" + "$domain=" + AdRules.site(e.page) + ")");
            onlyHere.setTextColor(Ui.TEXT);
            box.addView(onlyHere);
        }
        box.addView(Ui.text(this, (exception ? "예외 규칙" : "막는 규칙") + "으로 브라우저 규칙 끝에 추가합니다. "
                + "HTTPS 필터링(크롬)과 삼성 인터넷 광고 차단기에 함께 적용되고, 페이지는 막지 않습니다. "
                + "* = 아무 글자, ^ = 주소의 끝이나 구분 문자.", 12));
        new AlertDialog.Builder(this)
                .setTitle(exception ? "허용 규칙 추가" : "막는 규칙 추가")
                .setView(box)
                .setPositiveButton("추가", (d, w) -> {
                    String r = field.getText().toString().trim();
                    if (r.isEmpty()) return;
                    if (onlyHere.isChecked() && e.page != null) {
                        r += (r.contains("$") ? "," : "$") + "domain=" + AdRules.site(e.page);
                    }
                    try {
                        Lists.appendAll(this, Lists.BROWSER, Collections.singletonList(r));
                        Toast.makeText(this, "추가함 · 다음 요청부터 적용: " + r, Toast.LENGTH_LONG).show();
                    } catch (IOException ex) {
                        Toast.makeText(this, "실패: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void copy(String text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("haechi", text));
        Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show();
    }

    /** "/path/file.js" without query or fragment. */
    private static String pathOf(String url) {
        int s = url.indexOf('/', url.indexOf("://") + 3);
        if (s < 0) return "/";
        int end = url.length();
        for (char c : new char[]{'?', '#'}) {
            int i = url.indexOf(c, s);
            if (i >= 0 && i < end) end = i;
        }
        return url.substring(s, end);
    }

    private static String resultName(RequestLog.Entry e) {
        return e.result == RequestLog.TUNNEL ? "풀지 않음" : e.result == RequestLog.BLOCKED ? "막음" : e.result == RequestLog.STYLED ? "통과 · 숨김 규칙 넣음"
                : e.result == RequestLog.REWRITTEN ? "통과 · 내용 고침" : "통과";
    }

    // ---------------------------------------------------------------- list rows

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : newRow();
            RequestLog.Entry e = items.get(i);
            SpannableStringBuilder b = new SpannableStringBuilder();
            if (contentHits != null && contentHits.contains(e.id)) b.append("📄 ");
            int hostAt = b.length();
            b.append(e.host);
            b.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), hostAt, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int s = e.url.indexOf('/', e.url.indexOf("://") + 3);
            String rest = s < 0 ? "" : e.url.substring(s);
            b.append(rest.length() > 90 ? rest.substring(0, 90) + "…" : rest);
            b.append("\n").append(time.format(new Date(e.time))).append("  ").append(RequestLog.typeName(e.type)).append("  ");
            int start = b.length();
            b.append(resultName(e));
            if (e.result == RequestLog.BLOCKED) color(b, start, RED);
            else if (e.result == RequestLog.TUNNEL) color(b, start, GRAY);
            else if (e.result == RequestLog.STYLED || e.result == RequestLog.REWRITTEN) color(b, start, GREEN);
            if (e.status >= 400) {
                start = b.length();
                b.append(" ").append(String.valueOf(e.status));
                color(b, start, GRAY);
            }
            if (e.page != null && !e.page.equals(e.host)) {
                start = b.length();
                b.append("  ← ").append(e.page);
                color(b, start, GRAY);
            }
            if (e.body != null) {
                start = b.length();
                b.append("  ").append(String.format(Locale.KOREA, "내용 %,dKB", Math.max(1, e.body.length / 1024)));
                color(b, start, GRAY);
            }
            if (e.rule != null) {
                start = b.length();
                b.append("\n").append(e.rule);
                color(b, start, GRAY);
            }
            t.setText(b);
            return t;
        }

        private TextView newRow() {
            TextView t = new TextView(RequestLogActivity.this);
            t.setTextSize(13);
            int p = Ui.dp(RequestLogActivity.this, 8);
            t.setPadding(p, p, p, p);
            return t;
        }
    }

    private static void color(SpannableStringBuilder b, int start, int color) {
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
