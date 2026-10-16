package local.haechi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "목록 관리": 도메인 확인 + tabs 차단 목록 · 허용 목록 · 브라우저 규칙 · 기본 제공 목록.
 * The user's lists stay plain text files; every change rewrites only the affected lines and
 * reloads the running filter (and notifies Samsung Internet) through Lists.save().
 */
public class ListsActivity extends Activity {
    private static final int TAB_BLOCK = 0, TAB_ALLOW = 1, TAB_BROWSER = 2, TAB_BUNDLED = 3;
    private static final String[] TAB_NAMES = {"차단 목록", "허용 목록", "브라우저 규칙", "기본 제공 목록"};
    private static final int SEARCH_LIMIT = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tab = TAB_BLOCK;

    private EditText check;
    private TextView checkResult;

    // editable-list pane
    private LinearLayout editPane;
    private Button clipboardButton;
    private EditText search;
    private TextView count;
    private ListView list;
    private final List<String> raws = new ArrayList<>();     // file lines (trimmed) shown, after search
    private ArrayAdapter<String> adapter;

    // bundled pane
    private LinearLayout bundledPane;
    private EditText bundledSearch;
    private TextView bundledStatus;
    private final List<Bundled.Hit> hits = new ArrayList<>();
    private ArrayAdapter<String> hitAdapter;
    private AtomicBoolean searchCancel = new AtomicBoolean();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("목록 관리");
        try { Lists.load(this); } catch (IOException ignored) {}
        LinearLayout col = Ui.column(this);

        // ---- 도메인 확인 ----
        check = new EditText(this);
        check.setHint("도메인 확인 (예: ads.example.com 또는 주소)");
        check.setSingleLine(true);
        check.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        check.addTextChangedListener(watcher(this::updateCheck));
        col.addView(check);
        checkResult = Ui.text(this, "", 14);
        col.addView(checkResult);

        // ---- tabs ----
        RadioGroup tabs = new RadioGroup(this);
        tabs.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(TAB_NAMES[i]);
            r.setId(View.generateViewId());
            final int t = i;
            r.setOnClickListener(v -> showTab(t));
            tabs.addView(r);
            if (i == tab) tabs.check(r.getId());
        }
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.addView(tabs);
        col.addView(tabScroll);

        col.addView(buildEditPane(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(buildBundledPane(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
        showTab(tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadEntries();   // e.g. after "원본 텍스트로 편집"
        updateCheck();
    }

    @Override
    protected void onDestroy() {
        searchCancel.set(true);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showTab(int t) {
        tab = t;
        boolean bundled = t == TAB_BUNDLED;
        editPane.setVisibility(bundled ? View.GONE : View.VISIBLE);
        bundledPane.setVisibility(bundled ? View.VISIBLE : View.GONE);
        clipboardButton.setVisibility(t == TAB_BLOCK ? View.VISIBLE : View.GONE);
        if (!bundled) {
            search.setText("");
            reloadEntries();
        }
    }

    private String file() {
        return tab == TAB_ALLOW ? Lists.ALLOW : tab == TAB_BROWSER ? Lists.BROWSER : Lists.BLOCK;
    }

    // ---------------------------------------------------------------- 도메인 확인

    private void updateCheck() {
        handler.removeCallbacks(recheck);
        String d = Domains.normalize(check.getText().toString());
        if (check.getText().toString().trim().isEmpty()) { checkResult.setText(""); return; }
        if (d == null) { checkResult.setText("도메인을 알아볼 수 없습니다"); return; }
        Bundled.ensureLoaded(this);   // only loads if nothing loaded it yet (e.g. the filter is off)
        Lists.Verdict v = Lists.classify(d);
        String s = v.status == Lists.BLOCKED ? "차단" : v.status == Lists.ALLOWED ? "허용" : "통과";
        String text = d + " → " + s + (v.source == null ? " (어느 목록에도 없음)" : " · " + v.source + " (" + v.rule + " 규칙)");
        if (!Bundled.isReady()) {
            text += "\n(기본 제공 목록 불러오는 중…)";
            handler.postDelayed(recheck, 1000);
        }
        checkResult.setText(text);
    }

    private final Runnable recheck = this::updateCheck;

    // ---------------------------------------------------------------- editable lists

    private View buildEditPane() {
        editPane = new LinearLayout(this);
        editPane.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.button(this, "+ 추가", v -> showAdd()), Ui.weight1());
        clipboardButton = Ui.button(this, "클립보드 주소로 차단", v -> blockFromClipboard());
        bar.addView(clipboardButton, Ui.weight1());
        Button more = Ui.button(this, "⋯", null);
        more.setOnClickListener(v -> showMore(more));
        bar.addView(more);
        editPane.addView(bar);

        search = new EditText(this);
        search.setHint("검색");
        search.setSingleLine(true);
        search.addTextChangedListener(watcher(this::reloadEntries));
        editPane.addView(search);
        count = Ui.text(this, "", 13);
        editPane.addView(count);

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> showEntry(raws.get(pos)));
        // Long-press: select several entries, then delete them together.
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        list.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override public void onItemCheckedStateChanged(ActionMode m, int pos, long id, boolean on) {
                m.setTitle(list.getCheckedItemCount() + "개 선택");
            }
            @Override public boolean onCreateActionMode(ActionMode m, Menu menu) {
                menu.add(0, 1, 0, "삭제").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode m, MenuItem item) {
                Set<String> picked = new HashSet<>();
                for (int i = 0; i < raws.size(); i++) if (list.isItemChecked(i)) picked.add(raws.get(i));
                m.finish();
                confirm(picked.size() + "개를 삭제할까요?", () -> edit(() -> Lists.removeLines(ListsActivity.this, file(), picked),
                        picked.size() + "개 삭제함"));
                return true;
            }
            @Override public void onDestroyActionMode(ActionMode m) {}
        });
        editPane.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return editPane;
    }

    /** Re-reads the file: one row per rule line (comments and blank lines hidden). */
    private void reloadEntries() {
        if (adapter == null || tab == TAB_BUNDLED) return;
        boolean domains = tab != TAB_BROWSER;
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<String> shown = new ArrayList<>();
        raws.clear();
        int total = 0;
        String text;
        try { text = Lists.read(this, file()); } catch (IOException e) { text = ""; }
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("!") || t.startsWith("[")) continue;
            if (t.startsWith("#") && (domains || !t.startsWith("##"))) continue;
            String label = t;
            if (domains) {
                String d = Lists.parseLine(t);
                label = d != null ? d : t + "   (인식 안 됨)";
            }
            total++;
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
            raws.add(t);
            shown.add(label);
        }
        count.setText(q.isEmpty() ? total + "개" : shown.size() + "개 / 전체 " + total + "개");
        adapter.setNotifyOnChange(false);
        adapter.clear();
        adapter.addAll(shown);
        adapter.notifyDataSetChanged();
    }

    /** Tap: 수정 / 삭제 / 복사. */
    private void showEntry(String raw) {
        boolean domains = tab != TAB_BROWSER;
        String shown = domains && Lists.parseLine(raw) != null ? Lists.parseLine(raw) : raw;
        new AlertDialog.Builder(this)
                .setTitle(shown)
                .setItems(new String[]{"수정", "삭제", "복사"}, (d, which) -> {
                    if (which == 0) showModify(raw, shown);
                    else if (which == 1) edit(() -> Lists.removeLines(this, file(), java.util.Collections.singleton(raw)), "삭제함");
                    else copy(shown);
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showModify(String raw, String shown) {
        EditText e = new EditText(this);
        e.setText(shown);
        e.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("수정")
                .setView(pad(e))
                .setPositiveButton("저장", (d, w) -> {
                    String input = e.getText().toString().trim();
                    String line = tab == TAB_BROWSER ? input : Domains.normalize(input);
                    if (line == null || line.isEmpty()) { toast("도메인을 알아볼 수 없습니다"); return; }
                    edit(() -> Lists.replaceLine(this, file(), raw, line), "수정함");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** "+ 추가": many lines at once; for a single block entry, ask how wide to block. */
    private void showAdd() {
        EditText e = new EditText(this);
        e.setHint(tab == TAB_BROWSER ? "예) example.com##.ad-box\n여러 줄 붙여넣기 가능"
                : "도메인이나 주소, 한 줄에 하나\n(https://ad.example.com/x.jpg → ad.example.com)");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(4);
        e.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this)
                .setTitle(TAB_NAMES[tab] + "에 추가")
                .setView(pad(e))
                .setPositiveButton("추가", (d, w) -> addLines(e.getText().toString()))
                .setNegativeButton("취소", null)
                .show();
    }

    private void addLines(String text) {
        List<String> add = new ArrayList<>();
        int bad = 0, dup = 0;
        Set<String> existing = tab == TAB_ALLOW ? Lists.allowSet() : Lists.blockSet();
        for (String line : text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            if (tab == TAB_BROWSER) { add.add(line.trim()); continue; }
            String d = Domains.normalize(line);
            if (d == null) bad++;
            else if (existing.contains(d) || add.contains(d)) dup++;
            else add.add(d);
        }
        String note = (bad > 0 ? " · 인식 못한 줄 " + bad + "개" : "") + (dup > 0 ? " · 이미 있음 " + dup + "개" : "");
        if (add.isEmpty()) { toast("추가할 항목이 없습니다" + note); return; }
        if (tab == TAB_BLOCK && add.size() == 1) {
            Domains.chooseScopeAndBlock(this, add.get(0), () -> { reloadEntries(); updateCheck(); });
            return;
        }
        edit(() -> Lists.appendAll(this, file(), add), add.size() + "개 추가" + note);
    }

    private void blockFromClipboard() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        String host = Domains.extractHost(text);
        if (host == null) { toast("클립보드에서 주소를 찾지 못했습니다"); return; }
        Domains.chooseScopeAndBlock(this, host, () -> { reloadEntries(); updateCheck(); });
    }

    /** "⋯": 원본 텍스트로 편집 (old raw editor), 기본 목록으로 되돌리기 (block list only). */
    private void showMore(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "원본 텍스트로 편집");
        if (tab == TAB_BLOCK) m.getMenu().add(0, 2, 0, "기본 목록으로 되돌리기");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                startActivity(new Intent(this, EditActivity.class).putExtra(EditActivity.EXTRA_FILE, file()));
            } else {
                confirm("차단 목록을 기본 목록으로 되돌릴까요? 직접 추가한 항목은 사라집니다. (허용 목록은 그대로)",
                        () -> edit(() -> Lists.resetDefault(this), "기본 목록으로 되돌렸습니다"));
            }
            return true;
        });
        m.show();
    }

    // ---------------------------------------------------------------- bundled lists

    private View buildBundledPane() {
        bundledPane = new LinearLayout(this);
        bundledPane.setOrientation(LinearLayout.VERTICAL);
        for (Bundled.Info info : Bundled.LISTS) {
            Switch sw = new Switch(this);
            sw.setText(info.title);
            sw.setTextSize(16);
            sw.setChecked(Bundled.isOn(this, info));
            sw.setOnCheckedChangeListener((b, on) -> {
                Bundled.setOn(this, info, on);   // reloads the filter in the background + Samsung update
                toast(info.title + (on ? " 켬" : " 끔"));
                handler.postDelayed(this::updateCheck, 300);
                if (bundledSearch.length() > 0) startSearch();
            });
            bundledPane.addView(sw);
            TextView desc = Ui.text(this, Bundled.header(this, info) + (info.dns ? "" : "\n(삼성 인터넷 전용 · DNS 차단에는 쓰지 않음)"), 12);
            desc.setPadding(0, 0, 0, Ui.dp(this, 8));
            bundledPane.addView(desc);
        }
        bundledSearch = new EditText(this);
        bundledSearch.setHint("켜진 DNS 목록에서 검색 (3글자 이상)");
        bundledSearch.setSingleLine(true);
        bundledSearch.addTextChangedListener(watcher(() -> {
            handler.removeCallbacks(searchLater);
            handler.postDelayed(searchLater, 300);
        }));
        bundledPane.addView(bundledSearch);
        bundledStatus = Ui.text(this, "", 13);
        bundledPane.addView(bundledStatus);

        ListView results = new ListView(this);
        hitAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        results.setAdapter(hitAdapter);
        results.setOnItemClickListener((p, v, pos, id) -> {
            Bundled.Hit h = hits.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle(h.domain + " · " + h.list + (h.exception ? " 예외" : ""))
                    .setItems(new String[]{"허용하기", "복사"}, (d, which) -> {
                        if (which == 0) edit(() -> Lists.append(this, Lists.ALLOW, h.domain), h.domain + " 허용");
                        else copy(h.domain);
                    })
                    .setNegativeButton("닫기", null)
                    .show();
        });
        bundledPane.addView(results, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return bundledPane;
    }

    private final Runnable searchLater = this::startSearch;

    /** Streams the enabled asset files on a background thread (the big table is not needed). */
    private void startSearch() {
        searchCancel.set(true);
        String q = bundledSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        hits.clear();
        hitAdapter.clear();
        if (q.length() < 3) { bundledStatus.setText(q.isEmpty() ? "" : "3글자 이상 입력하세요"); return; }
        bundledStatus.setText("검색 중…");
        AtomicBoolean cancel = new AtomicBoolean();
        searchCancel = cancel;
        new Thread(() -> {
            List<Bundled.Hit> found = Bundled.search(getApplicationContext(), q, SEARCH_LIMIT, cancel);
            handler.post(() -> {
                if (cancel.get() || isDestroyed()) return;
                hits.addAll(found);
                List<String> rows = new ArrayList<>();
                for (Bundled.Hit h : found) rows.add((h.exception ? "@@" : "") + h.domain + "   · " + h.list);
                hitAdapter.addAll(rows);
                bundledStatus.setText(found.isEmpty() ? "없음"
                        : found.size() >= SEARCH_LIMIT ? "처음 " + SEARCH_LIMIT + "개만 표시" : found.size() + "개");
            });
        }, "bundled-search").start();
    }

    // ---------------------------------------------------------------- helpers

    private interface Change { void run() throws IOException; }

    private void edit(Change c, String done) {
        try {
            c.run();   // Lists.save(): rewrites the file, reloads the running filter, notifies Samsung
            toast(done);
        } catch (IOException e) {
            toast("실패: " + e.getMessage());
        }
        reloadEntries();
        updateCheck();
    }

    private void confirm(String msg, Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("확인", (d, w) -> yes.run())
                .setNegativeButton("취소", null).show();
    }

    private void copy(String s) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("domain", s));
        toast("복사했습니다");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private View pad(View v) {
        LinearLayout l = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        l.setPadding(p, Ui.dp(this, 8), p, 0);
        l.addView(v, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }

    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { r.run(); }
        };
    }
}
