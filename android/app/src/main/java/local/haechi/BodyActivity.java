package local.haechi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * "응답 내용": the text of one answer the request log kept (unpacked), to read it and pick what to remove.
 * Search shows every place a word (or regex) appears with the text around it; from there a $replace rule is
 * made — the user decides what goes, nothing is chosen for them.
 */
public class BodyActivity extends Activity {
    static final String EXTRA_ID = "id", EXTRA_QUERY = "query";
    private static final int SHOW_MAX = 100_000;          // a TextView with more freezes the screen
    private static final int AROUND = 120, MAX_HITS = 500;

    private RequestLog.Entry entry;
    private String text = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView info, full;
    private EditText query;
    private CheckBox regex;
    private ListView hitList;
    private final List<int[]> hits = new ArrayList<>();
    private final HitAdapter adapter = new HitAdapter();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        entry = RequestLog.find(getIntent().getLongExtra(EXTRA_ID, -1));
        if (entry == null) {
            Toast.makeText(this, "기록이 지워져 볼 수 없습니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setTitle("응답 내용");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout col = Ui.column(this);

        TextView url = Ui.text(this, entry.url.length() > 300 ? entry.url.substring(0, 300) + "…" : entry.url, 13);
        url.setTextIsSelectable(true);
        col.addView(url);
        info = Ui.text(this, "푸는 중…", 12);
        col.addView(info);

        LinearLayout searchRow = Ui.row(this);
        query = Ui.field(this, new EditText(this));
        query.setHint("찾을 글자 (예: adPlacements, banner)");
        query.setSingleLine(true);
        query.setTextSize(14);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, id, ev) -> { search(); return true; });
        searchRow.addView(query, Ui.weight1());
        searchRow.addView(Ui.primary(this, "찾기", v -> search()), RequestLogActivity.wrapAfter(this));
        col.addView(searchRow, Ui.gap(this, 6));
        regex = new CheckBox(this);
        regex.setText("정규식으로 찾기");
        regex.setTextColor(Ui.TEXT);
        col.addView(regex);

        LinearLayout buttons = Ui.row(this);
        buttons.addView(Ui.button(this, "지우기 규칙 만들기", v -> makeRule(query.getText().toString(), regex.isChecked())), Ui.weight1());
        buttons.addView(Ui.button(this, "머리글", v -> showHeads()), Ui.weight1());
        buttons.addView(Ui.button(this, "전체 복사", v -> copy(text)), Ui.weight1());
        col.addView(buttons);

        hitList = new ListView(this);
        hitList.setAdapter(adapter);
        hitList.setOnItemClickListener((p, v, pos, id) -> showHit(hits.get(pos)));
        hitList.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 18)));
        hitList.setClipToOutline(true);
        hitList.setDivider(new android.graphics.drawable.ColorDrawable(0xFFEDF1EF));
        hitList.setDividerHeight(1);
        hitList.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = Ui.dp(this, 8);
        col.addView(hitList, lp);

        // the text itself (first part), selectable
        ScrollView scroll = new ScrollView(this);
        full = new TextView(this);
        full.setTypeface(Typeface.MONOSPACE);
        full.setTextSize(11);
        full.setTextColor(Ui.TEXT);
        full.setTextIsSelectable(true);
        int p = Ui.dp(this, 10);
        full.setPadding(p, p, p, p);
        scroll.addView(full);
        scroll.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 18)));
        scroll.setClipToOutline(true);
        scroll.setTag("full");
        col.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);

        new Thread(() -> {                                    // unpacking a few MB: off the main thread
            String t, msg;
            try {
                t = RequestLog.text(entry);
                msg = null;
            } catch (IOException e) {
                t = "";
                msg = e.getMessage();
            }
            String body = t, err = msg;
            handler.post(() -> shown(body, err));
        }, "body-unpack").start();
    }

    private void shown(String body, String err) {
        text = body;
        String type = RequestLog.headerValue(entry.responseHead, "content-type");
        StringBuilder sb = new StringBuilder();
        sb.append(RequestLog.typeName(entry.type)).append(" · HTTP ").append(entry.status)
                .append(" · ").append(type == null ? "종류 모름" : type)
                .append(" · ").append(String.format(java.util.Locale.KOREA, "%,d자", text.length()));
        if (entry.bodyCut) sb.append(" · 앞 4MB만 보관됨");
        if (err != null) sb.append("\n").append(err);
        if (text.length() > SHOW_MAX) sb.append("\n아래에는 앞 ").append(SHOW_MAX / 1000).append("천 자만 보입니다. 나머지는 '찾기'로 보세요");
        info.setText(sb);
        full.setText(text.length() > SHOW_MAX ? text.substring(0, SHOW_MAX) + "\n…" : text);
        String carried = getIntent().getStringExtra(EXTRA_QUERY);   // came from "내용에서 찾기": show the hits at once
        if (carried != null && !carried.isEmpty() && !text.isEmpty()) {
            query.setText(carried);
            search();
        }
    }

    // ---------------------------------------------------------------- search

    private void search() {
        String q = query.getText().toString();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
        hits.clear();
        if (q.isEmpty()) {
            hitList.setVisibility(View.GONE);
            setFullVisible(true);
            return;
        }
        Pattern p;
        try {
            p = regex.isChecked() ? Pattern.compile(q) : Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            Toast.makeText(this, "정규식이 틀렸습니다: " + e.getDescription(), Toast.LENGTH_LONG).show();
            return;
        }
        Matcher m = p.matcher(text);
        int total = 0;
        while (m.find()) {
            if (m.end() == m.start()) { if (m.end() >= text.length()) break; continue; }
            total++;
            if (hits.size() < MAX_HITS) hits.add(new int[]{m.start(), m.end()});
        }
        adapter.notifyDataSetChanged();
        hitList.setVisibility(View.VISIBLE);
        setFullVisible(false);
        Toast.makeText(this, total == 0 ? "없습니다" : total + "곳" + (total > MAX_HITS ? " (앞 " + MAX_HITS + "곳만 표시)" : ""),
                Toast.LENGTH_SHORT).show();
    }

    private void setFullVisible(boolean on) {
        View scroll = ((ViewGroup) full.getParent());
        scroll.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    /** One hit with a wide margin around it; select any part of it to make a rule from exactly that. */
    private void showHit(int[] h) {
        int from = Math.max(0, h[0] - 1500), to = Math.min(text.length(), h[1] + 1500);
        SpannableStringBuilder b = new SpannableStringBuilder(text.substring(from, to));
        b.setSpan(new BackgroundColorSpan(0xFFFFE082), h[0] - from, h[1] - from, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = new TextView(this);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(11);
        t.setTextColor(Ui.TEXT);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        t.setText(b);
        ScrollView s = new ScrollView(this);
        s.addView(t);
        s.post(() -> {
            android.text.Layout l = t.getLayout();
            if (l != null) s.scrollTo(0, Math.max(0, l.getLineTop(l.getLineForOffset(h[0] - from)) - Ui.dp(this, 80)));
        });
        new AlertDialog.Builder(this)
                .setTitle("위치 " + String.format(java.util.Locale.KOREA, "%,d", h[0]) + " (노란 곳) · 글자를 골라 규칙으로")
                .setView(s)
                .setPositiveButton("고른 글자로 규칙", (d, w) -> {
                    int a = t.getSelectionStart(), z = t.getSelectionEnd();
                    String pick = a >= 0 && z > a ? t.getText().subSequence(a, z).toString() : text.substring(h[0], h[1]);
                    makeRule(pick, false);
                })
                .setNeutralButton("복사", (d, w) -> {
                    int a = t.getSelectionStart(), z = t.getSelectionEnd();
                    copy(a >= 0 && z > a ? t.getText().subSequence(a, z).toString() : t.getText().toString());
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    // ---------------------------------------------------------------- rule

    /**
     * $replace rule for this address: what to find (plain text is escaped; a regex is used as is), what to put
     * instead (empty = delete). Shows how many places it hits in this answer before adding.
     */
    private void makeRule(String find, boolean isRegex) {
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.label(this, "주소 (이 주소의 응답만 고침)"));
        EditText where = field("||" + entry.host + pathOf(entry.url));
        box.addView(where);
        box.addView(Ui.label(this, "찾을 것 (정규식)"));
        EditText what = field(find.isEmpty() ? "" : isRegex ? find : regexEscape(find));
        box.addView(what);
        box.addView(Ui.label(this, "바꿀 말 (비우면 지움)"));
        EditText with = field("");
        box.addView(with);
        CheckBox all = new CheckBox(this);
        all.setText("모두 바꾸기 (g)");
        all.setTextColor(Ui.TEXT);
        all.setChecked(true);
        box.addView(all);
        TextView preview = Ui.text(this, "", 12);
        box.addView(preview);
        box.addView(Ui.text(this, "JSON 안의 이름을 지우면 문법이 깨질 수 있어요. 그럴 땐 이름만 바꾸세요 "
                + "(예: \"adPlacements\" → \"no_ads\"). 브라우저 규칙에 추가되고 다음 응답부터 적용됩니다.", 12));
        Runnable count = () -> {
            try {
                Matcher m = Pattern.compile(what.getText().toString()).matcher(text);
                int n = 0;
                while (m.find() && n < 100000) { if (m.end() == m.start()) break; n++; }
                preview.setText("이 응답에서 " + n + "곳이 걸립니다");
            } catch (PatternSyntaxException e) {
                preview.setText("정규식이 틀렸습니다: " + e.getDescription());
            }
        };
        count.run();
        what.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                handler.removeCallbacks(count);
                handler.postDelayed(count, 400);
            }
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("응답 고치기 규칙")
                .setView(scroll)
                .setPositiveButton("추가", (d, w) -> {
                    String f = what.getText().toString();
                    if (f.isEmpty()) return;
                    try {
                        Pattern.compile(f);
                    } catch (PatternSyntaxException e) {
                        Toast.makeText(this, "정규식이 틀려 추가하지 않았습니다", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String rule = where.getText().toString().trim() + "$replace=/" + valueEscape(f) + "/"
                            + valueEscape(with.getText().toString()) + "/" + (all.isChecked() ? "g" : "");
                    try {
                        Lists.appendAll(this, Lists.BROWSER, Collections.singletonList(rule));
                        Toast.makeText(this, "추가함: " + rule, Toast.LENGTH_LONG).show();
                    } catch (IOException e) {
                        Toast.makeText(this, "실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private EditText field(String value) {
        EditText e = Ui.field(this, new EditText(this));
        e.setText(value);
        e.setTypeface(Typeface.MONOSPACE);
        e.setTextSize(13);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return e;
    }

    /** Plain text -> regex that matches exactly it. */
    static String regexEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\.[]{}()*+?^$|".indexOf(c) >= 0) b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /** Inside "$replace=/…/…/", a '/' or ',' must carry a backslash. */
    static String valueEscape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '/' || c == ',') && (i == 0 || s.charAt(i - 1) != '\\')) b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

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

    private void showHeads() {
        String s = "보낸 요청\n" + (entry.requestHead == null ? "-" : entry.requestHead)
                + "\n받은 응답\n" + (entry.responseHead == null ? "-" : entry.responseHead);
        TextView t = Ui.text(this, s, 12);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setTitle("머리글 (쿠키·로그인 값은 숨김)").setView(sv)
                .setNeutralButton("복사", (d, w) -> copy(s)).setNegativeButton("닫기", null).show();
    }

    private void copy(String s) {
        if (s.length() > 1_000_000) s = s.substring(0, 1_000_000);   // clipboard can't carry much more
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("haechi", s));
        Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private class HitAdapter extends BaseAdapter {
        @Override public int getCount() { return hits.size(); }
        @Override public Object getItem(int i) { return hits.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(BodyActivity.this);
            t.setTypeface(Typeface.MONOSPACE);
            t.setTextSize(12);
            t.setTextColor(Ui.TEXT);
            int p = Ui.dp(BodyActivity.this, 8);
            t.setPadding(p, p, p, p);
            int[] h = hits.get(i);
            int from = Math.max(0, h[0] - AROUND), to = Math.min(text.length(), h[1] + AROUND);
            SpannableStringBuilder b = new SpannableStringBuilder();
            b.append("#").append(String.valueOf(i + 1)).append("  ").append(String.format(java.util.Locale.KOREA, "%,d", h[0])).append("\n");
            int base = b.length();
            b.append(text.substring(from, to).replace('\n', ' '));
            b.setSpan(new BackgroundColorSpan(0xFFFFE082), base + h[0] - from, base + h[1] - from, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setText(b);
            return t;
        }
    }
}
