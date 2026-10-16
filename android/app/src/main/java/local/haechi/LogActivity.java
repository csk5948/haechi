package local.haechi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;

/**
 * "기록" screen: every domain looked up while recording, newest first, with its current status.
 * Tap a domain to block it, allow it, or undo. Data lives only in memory (see QueryLog).
 */
public class LogActivity extends Activity {
    private static final int RED = 0xFFD32F2F, BLUE = 0xFF1565C0;
    private static final int ALL = 0, ONLY_PASSED = 1, ONLY_BLOCKED = 2, ONLY_SUSPECT = 3;
    private static final String ALL_APPS = "모든 앱";

    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    private List<QueryLog.Entry> items = new ArrayList<>();
    private List<Lists.Verdict> verdicts = new ArrayList<>();
    private List<String> tags = new ArrayList<>();
    private String appFilter;   // null = all apps
    private Spinner appSpinner;
    private ArrayAdapter<String> appAdapter;
    private int filter = ALL;
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;

    private TextView state;
    private EditText search;
    private ListView list;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("기록");
        Labels.load(this);
        LinearLayout col = Ui.column(this);

        LinearLayout buttons = Ui.row(this);
        Button rec = Ui.primary(this, "● 새로 녹화", v -> { QueryLog.startNew(); refresh(); });
        Button stop = Ui.button(this, "■ 정지", v -> { QueryLog.pause(); refresh(); });
        Button clear = Ui.button(this, "지우기", v -> { QueryLog.clear(); refresh(); });
        buttons.addView(rec, Ui.weight1());
        buttons.addView(stop, Ui.weight1());
        buttons.addView(clear, Ui.weight1());
        col.addView(buttons);

        state = Ui.text(this, "", 13);
        col.addView(state);

        search = Ui.field(this, new EditText(this));
        search.setHint("도메인 검색");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        col.addView(search, Ui.gap(this, 4));

        RadioGroup filters = new RadioGroup(this);
        filters.setOrientation(RadioGroup.HORIZONTAL);
        String[] labels = {"전체", "통과만", "차단만", "광고·추적·의심만"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton r = Ui.chip(this, new RadioButton(this));
            r.setText(labels[i]);
            r.setId(View.generateViewId());
            final int f = i;
            r.setOnClickListener(v -> { filter = f; refresh(); });
            filters.addView(r, Ui.chipParams(this));
            if (i == ALL) filters.check(r.getId());
        }
        HorizontalScrollView filterScroll = new HorizontalScrollView(this);   // 4 options may not fit
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterScroll.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        filterScroll.addView(filters);
        col.addView(filterScroll);

        appSpinner = new Spinner(this);
        appAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        appAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        appAdapter.add(ALL_APPS);
        appSpinner.setAdapter(appAdapter);
        appSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String a = pos == 0 ? null : appAdapter.getItem(pos);
                if (a == null ? appFilter != null : !a.equals(appFilter)) { appFilter = a; refresh(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        if (Build.VERSION.SDK_INT >= 29) col.addView(appSpinner);   // app names need Android 10+

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
        state.setText((QueryLog.isRecording() ? "● 녹화 중" : "■ 정지됨 (차단은 계속 동작)")
                + (BlockerService.running ? "" : " · 차단 꺼짐"));
        // Don't move the list under the user's finger; the next tick will catch up.
        if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) return;

        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<QueryLog.Entry> next = new ArrayList<>();
        List<Lists.Verdict> vs = new ArrayList<>();
        List<String> tg = new ArrayList<>();
        TreeSet<String> seenApps = new TreeSet<>();
        for (QueryLog.Entry e : QueryLog.snapshot()) {
            seenApps.addAll(e.apps);
            if (!q.isEmpty() && !e.domain.contains(q)) continue;
            if (appFilter != null && !e.apps.contains(appFilter)) continue;
            Lists.Verdict v = Lists.classify(e.domain);
            if (filter == ONLY_PASSED && v.status != Lists.PASSED) continue;
            if (filter == ONLY_BLOCKED && v.status != Lists.BLOCKED) continue;
            String tag = Labels.of(e.domain);
            if (filter == ONLY_SUSPECT && !Labels.isAdOrTrack(tag)) continue;
            next.add(e);
            vs.add(v);
            tg.add(tag);
        }
        updateAppChoices(seenApps);

        // Keep the row the user is looking at in place when new rows appear above it.
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int offset = top == null ? 0 : top.getTop();
        String anchor = (first > 0 || offset < 0) && first < items.size() ? items.get(first).domain : null;

        items = next;
        verdicts = vs;
        tags = tg;
        adapter.notifyDataSetChanged();

        if (anchor != null) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).domain.equals(anchor)) { list.setSelectionFromTop(i, offset); break; }
            }
        }
    }

    /** Spinner = "모든 앱" + every app seen so far (sorted). Only rebuilt when that set changes. */
    private void updateAppChoices(TreeSet<String> seen) {
        if (appFilter != null) seen.add(appFilter);   // keep the current choice even after 지우기
        List<String> want = new ArrayList<>(seen);
        List<String> have = new ArrayList<>();
        for (int i = 1; i < appAdapter.getCount(); i++) have.add(appAdapter.getItem(i));
        if (want.equals(have)) return;
        appAdapter.setNotifyOnChange(false);
        appAdapter.clear();
        appAdapter.add(ALL_APPS);
        appAdapter.addAll(want);
        appAdapter.notifyDataSetChanged();
        appSpinner.setSelection(appFilter == null ? 0 : want.indexOf(appFilter) + 1, false);
    }

    // ---------------------------------------------------------------- tap actions

    private void showActions(QueryLog.Entry entry) {
        String domain = entry.domain;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        Lists.Verdict v = Lists.classify(domain);
        int s = v.status;
        if (s == Lists.PASSED) {
            for (String scope : Domains.scopes(domain)) {
                labels.add("차단: " + scope + (scope.equals(domain) ? "" : " (하위 전체)"));
                actions.add(() -> change(() -> Lists.append(this, Lists.BLOCK, scope), scope + " 차단"));
            }
        } else if (s == Lists.BLOCKED) {
            labels.add("허용하기");
            actions.add(() -> change(() -> Lists.append(this, Lists.ALLOW, domain), domain + " 허용"));
            if (Lists.blockedExactly(domain)) {
                labels.add("차단 목록에서 삭제");
                actions.add(() -> change(() -> Lists.remove(this, Lists.BLOCK, domain, false), "차단 목록에서 삭제함"));
            }
        } else {
            labels.add("허용 취소");
            actions.add(() -> change(() -> Lists.remove(this, Lists.ALLOW, domain, true), "허용 취소함"));
        }
        labels.add("도메인 복사");
        actions.add(() -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("domain", domain));
            Toast.makeText(this, "복사했습니다", Toast.LENGTH_SHORT).show();
        });

        // Header: domain, status, 분류 and every app seen asking for it.
        String tag = Labels.of(domain);
        StringBuilder info = new StringBuilder(domain)
                .append("\n상태: ").append(statusName(s))
                .append(" · 분류: ").append(tag == null ? "-" : tag)
                .append("\n출처: ").append(v.source == null ? "어느 목록에도 없음" : v.source + " (" + v.rule + " 규칙)");
        if (Build.VERSION.SDK_INT >= 29) {
            List<String> apps = new ArrayList<>(entry.apps);
            Collections.reverse(apps);   // newest first
            info.append("\n앱: ").append(apps.isEmpty() ? "알 수 없음" : String.join(", ", apps));
        }
        TextView header = Ui.text(this, info.toString(), 15);
        int p = Ui.dp(this, 20);
        header.setPadding(p, p, p, Ui.dp(this, 8));

        new AlertDialog.Builder(this)
                .setCustomTitle(header)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("닫기", null)
                .show();
    }

    private interface Edit { void run() throws IOException; }

    private void change(Edit edit, String done) {
        try {
            edit.run();   // Lists reloads itself, so the running filter uses the change at once
            Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private static void color(SpannableStringBuilder b, int start, int color) {
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 광고 red-ish, 추적 orange, CDN gray; guesses ("광고?" / "추적?") in a lighter shade. */
    private static int tagColor(String tag) {
        switch (tag) {
            case Labels.AD: return 0xFFC62828;
            case Labels.AD_GUESS: return 0xFFE57373;
            case Labels.TRACK: return 0xFFE65100;
            case Labels.TRACK_GUESS: return 0xFFFFA040;
            default: return 0xFF888888;   // CDN / anything else
        }
    }

    private static String statusName(int s) {
        return s == Lists.BLOCKED ? "차단" : s == Lists.ALLOWED ? "허용" : "통과";
    }

    // ---------------------------------------------------------------- list rows

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : newRow();
            QueryLog.Entry e = items.get(i);
            Lists.Verdict v = verdicts.get(i);
            int s = v.status;
            SpannableStringBuilder b = new SpannableStringBuilder();
            b.append(e.domain);
            b.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.append("\n").append(time.format(new Date(e.lastSeen))).append("  ×").append(String.valueOf(e.count)).append("  ");
            int start = b.length();
            b.append(statusName(s));
            if (s != Lists.PASSED) color(b, start, s == Lists.BLOCKED ? RED : BLUE);
            if (v.source != null) {
                start = b.length();
                b.append(" (").append(v.source).append(")");
                color(b, start, 0xFF888888);
            }
            String tag = tags.get(i);
            if (tag != null) {
                b.append("  ");
                start = b.length();
                b.append("[").append(tag).append("]");
                color(b, start, tagColor(tag));
            }
            if (e.lastApp != null) {
                b.append("  ").append(e.lastApp);
                if (e.apps.size() > 1) b.append(" +").append(String.valueOf(e.apps.size() - 1));
            }
            t.setText(b);
            return t;
        }

        private TextView newRow() {
            TextView t = new TextView(LogActivity.this);
            t.setTextSize(14);
            int p = Ui.dp(LogActivity.this, 8);
            t.setPadding(p, p, p, p);
            return t;
        }
    }
}
