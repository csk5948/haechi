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
    private static final int TAB_BLOCK = 0, TAB_ALLOW = 1, TAB_BROWSER = 2, TAB_PACKS = 3, TAB_BUNDLED = 4;
    private static final String[] TAB_NAMES = {"차단 목록", "허용 목록", "브라우저 규칙", "팩", "기본 제공 목록"};
    private static final int SEARCH_LIMIT = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int tab = TAB_BLOCK;

    private EditText check;
    private TextView checkResult;

    // editable-list pane
    private LinearLayout editPane;
    private Button clipboardButton, pickButton, guideButton;
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
        check = Ui.field(this, new EditText(this));
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
            RadioButton r = Ui.chip(this, new RadioButton(this));
            r.setText(TAB_NAMES[i]);
            r.setId(View.generateViewId());
            final int t = i;
            r.setOnClickListener(v -> { openPack = null; showTab(t); });   // the 팩 chip always shows the pack list
            tabs.addView(r, Ui.chipParams(this));
            if (i == tab) tabs.check(r.getId());
        }
        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setPadding(0, 0, 0, Ui.dp(this, 8));
        tabScroll.addView(tabs);
        col.addView(tabScroll);

        col.addView(buildEditPane(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(buildPackPane(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(buildBundledPane(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);
        showTab(tab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadEntries();   // e.g. after "원본 텍스트로 편집"
        if (tab == TAB_PACKS) showTab(TAB_PACKS);   // pack names/counts may have changed
        updateCheck();
    }

    @Override
    public void onBackPressed() {
        if (tab == TAB_PACKS && openPack != null) { openPack = null; showTab(TAB_PACKS); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        searchCancel.set(true);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void showTab(int t) {
        tab = t;
        if (t != TAB_PACKS) openPack = null;
        boolean bundled = t == TAB_BUNDLED, packList = t == TAB_PACKS && openPack == null;
        editPane.setVisibility(bundled || packList ? View.GONE : View.VISIBLE);
        packPane.setVisibility(packList ? View.VISIBLE : View.GONE);
        bundledPane.setVisibility(bundled ? View.VISIBLE : View.GONE);
        packHeader.setVisibility(openPack != null ? View.VISIBLE : View.GONE);
        clipboardButton.setVisibility(t == TAB_BLOCK ? View.VISIBLE : View.GONE);
        pickButton.setVisibility(rules() ? View.VISIBLE : View.GONE);
        guideButton.setVisibility(rules() ? View.VISIBLE : View.GONE);
        if (packList) reloadPacks();
        if (openPack != null) {
            packTitle.setText(Packs.name(this, openPack));
            packSwitch.setOnCheckedChangeListener(null);
            packSwitch.setChecked(Packs.isOn(this, openPack));
            packSwitch.setOnCheckedChangeListener((b, on) -> setPackOn(openPack, on));
        }
        if (!bundled && !packList) {
            search.setText("");
            reloadEntries();
        }
    }

    private String file() {
        if (tab == TAB_PACKS && openPack != null) return Packs.file(openPack);
        return tab == TAB_ALLOW ? Lists.ALLOW : tab == TAB_BROWSER ? Lists.BROWSER : Lists.BLOCK;
    }

    /** Browser rules or a pack (rule lines as written), as opposed to the domain lists. */
    private boolean rules() {
        return tab == TAB_BROWSER || (tab == TAB_PACKS && openPack != null);
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

        // shown while one pack is open: back to the list, its name, its on/off switch
        packHeader = Ui.row(this);
        packHeader.setPadding(0, 0, 0, Ui.dp(this, 8));
        packHeader.addView(Ui.round(this, "‹", v -> { openPack = null; showTab(TAB_PACKS); }));
        packTitle = Ui.heading(this, "");
        packTitle.setPadding(Ui.dp(this, 10), 0, 0, 0);
        packHeader.addView(packTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        packSwitch = Ui.toggle(this);
        packHeader.addView(packSwitch);
        editPane.addView(packHeader);

        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.primary(this, "+ 추가", v -> showAdd()), Ui.weight1());
        clipboardButton = Ui.button(this, "클립보드로 차단", v -> blockFromClipboard());
        bar.addView(clipboardButton, Ui.weight1());
        pickButton = Ui.button(this, "골라 숨기기", v -> startActivity(new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_TARGET, file())));   // picked rules go to this list / pack
        bar.addView(pickButton, Ui.weight1());
        guideButton = Ui.round(this, "?", v -> startActivity(new Intent(this, GuideActivity.class)));
        bar.addView(guideButton, Ui.after(this));
        Button more = Ui.round(this, "⋯", null);
        more.setOnClickListener(v -> showMore(more));
        bar.addView(more, Ui.after(this));
        editPane.addView(bar);

        search = Ui.field(this, new EditText(this));
        search.setHint("검색");
        search.setSingleLine(true);
        search.addTextChangedListener(watcher(this::reloadEntries));
        editPane.addView(search, Ui.gap(this, 10));
        count = Ui.text(this, "", 13);
        editPane.addView(count);

        list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 22)));
        list.setClipToOutline(true);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFFEDF1EF));
        list.setDividerHeight(1);
        list.setOnItemClickListener((p, v, pos, id) -> showEntry(raws.get(pos)));
        // Long-press: select several entries, then delete them together.
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        list.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override public void onItemCheckedStateChanged(ActionMode m, int pos, long id, boolean on) {
                m.setTitle(list.getCheckedItemCount() + "개 선택");
            }
            @Override public boolean onCreateActionMode(ActionMode m, Menu menu) {
                if (rules()) menu.add(0, 4, 0, "팩으로").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0, 2, 0, "일시정지").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0, 3, 0, "재개").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                menu.add(0, 1, 0, "삭제").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }
            @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode m, MenuItem item) {
                Set<String> picked = new HashSet<>();
                for (int i = 0; i < raws.size(); i++) if (list.isItemChecked(i)) picked.add(raws.get(i));
                m.finish();
                if (item.getItemId() == 4) {
                    List<String> ordered = new ArrayList<>();   // keep file order
                    for (String r : raws) if (picked.contains(r)) ordered.add(r);
                    moveToPack(ordered);
                    return true;
                }
                if (item.getItemId() != 1) {
                    boolean pause = item.getItemId() == 2;
                    edit(() -> Lists.setPaused(ListsActivity.this, file(), picked, pause),
                            picked.size() + "개 " + (pause ? "일시정지" : "재개"));
                    return true;
                }
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
        if (adapter == null || tab == TAB_BUNDLED || (tab == TAB_PACKS && openPack == null)) return;
        boolean domains = !rules();
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<String> shown = new ArrayList<>();
        raws.clear();
        int total = 0, pausedCount = 0;
        String text;
        try { text = Lists.read(this, file()); } catch (IOException e) { text = ""; }
        for (String line : text.split("\n")) {
            String t = line.trim();
            boolean paused = Lists.isPaused(t);
            String e = Lists.unpaused(t);
            if (e.isEmpty() || (!paused && t.startsWith("!")) || e.startsWith("!") || e.startsWith("[")) continue;
            if (e.startsWith("#") && (domains || !e.startsWith("##"))) continue;
            String label = e;
            if (domains) {
                String d = Lists.parseLine(e);
                label = d != null ? d : e + "   (인식 안 됨)";
            }
            if (paused) pausedCount++;
            total++;
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
            raws.add(t);
            shown.add(label);
        }
        count.setText((q.isEmpty() ? total + "개" : shown.size() + "개 / 전체 " + total + "개")
                + (pausedCount > 0 ? " · 일시정지 " + pausedCount + "개" : ""));
        adapter.setNotifyOnChange(false);
        adapter.clear();
        adapter.addAll(shown);
        adapter.notifyDataSetChanged();
    }

    /** Tap: 수정 / 삭제 / 복사. */
    private void showEntry(String raw) {
        boolean domains = !rules(), paused = Lists.isPaused(raw);
        String entry = Lists.unpaused(raw);
        String shown = domains && Lists.parseLine(entry) != null ? Lists.parseLine(entry) : entry;
        java.util.Set<String> one = java.util.Collections.singleton(raw);
        new AlertDialog.Builder(this)
                .setTitle((paused ? "⏸ " : "") + shown)
                .setItems(new String[]{paused ? "재개" : "일시정지", "수정", "삭제", "복사"}, (d, which) -> {
                    if (which == 0) edit(() -> Lists.setPaused(this, file(), one, !paused), paused ? "재개함" : "일시정지함");
                    else if (which == 1) showModify(raw, shown);
                    else if (which == 2) edit(() -> Lists.removeLines(this, file(), one), "삭제함");
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
                    String line = rules() ? input : Domains.normalize(input);
                    if (line == null || line.isEmpty()) { toast("도메인을 알아볼 수 없습니다"); return; }
                    String keep = Lists.isPaused(raw) ? Lists.PAUSED + line : line;
                    edit(() -> Lists.replaceLine(this, file(), raw, keep), "수정함");
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** "+ 추가": many lines at once; for a single block entry, ask how wide to block. */
    private void showAdd() {
        EditText e = new EditText(this);
        e.setHint(rules() ? "예) example.com##.ad-box\n여러 줄 붙여넣기 가능"
                : "도메인이나 주소, 한 줄에 하나\n(https://ad.example.com/x.jpg → ad.example.com)");
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setMinLines(4);
        e.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this)
                .setTitle((openPack != null ? Packs.name(this, openPack) : TAB_NAMES[tab]) + "에 추가")
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
            if (rules()) { add.add(line.trim()); continue; }
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
        if (rules()) m.getMenu().add(0, 3, 0, "와일드카드(*)로 찾은 도메인");
        if (openPack != null) {
            m.getMenu().add(0, 5, 0, "팩 이름 바꾸기");
            m.getMenu().add(0, 6, 0, "팩 공유하기 (텍스트)");
            m.getMenu().add(0, 7, 0, "팩 삭제");
        }
        m.setOnMenuItemClickListener(item -> {
            String id = openPack;
            if (item.getItemId() == 5) {
                askName("팩 이름 바꾸기", Packs.name(this, id), name -> {
                    edit(() -> Packs.rename(this, id, name), "이름을 바꿨어요");
                    packTitle.setText(Packs.name(this, id));
                });
            } else if (item.getItemId() == 6) {
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, Lists.read(this, Packs.file(id))), "팩 공유하기"));
                } catch (IOException e) { toast("읽기 실패: " + e.getMessage()); }
            } else if (item.getItemId() == 7) {
                confirm("'" + Packs.name(this, id) + "' 팩을 지울까요? 안의 규칙도 같이 지워져요.", () -> {
                    try { Packs.delete(this, id); toast("팩을 지웠어요"); } catch (IOException e) { toast("실패: " + e.getMessage()); }
                    openPack = null;
                    showTab(TAB_PACKS);
                });
            } else if (item.getItemId() == 3) {
                showWildcardSeen();
            } else if (item.getItemId() == 1) {
                startActivity(new Intent(this, EditActivity.class).putExtra(EditActivity.EXTRA_FILE, file()));
            } else {
                confirm("차단 목록을 기본 목록으로 되돌릴까요? 직접 추가한 항목은 사라집니다. (허용 목록은 그대로)",
                        () -> edit(() -> Lists.resetDefault(this), "기본 목록으로 되돌렸습니다"));
            }
            return true;
        });
        m.show();
    }

    // ---------------------------------------------------------------- packs

    private LinearLayout packPane, packHeader;
    private TextView packTitle;
    private Switch packSwitch;
    private String openPack;   // id of the pack shown in the edit pane, or null for the pack list
    private final List<Packs.Pack> packs = new ArrayList<>();
    private android.widget.BaseAdapter packAdapter;

    private View buildPackPane() {
        packPane = new LinearLayout(this);
        packPane.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = Ui.row(this);
        bar.addView(Ui.primary(this, "+ 새 팩", v -> askName("새 팩", "", name -> {
            try {
                String id = Packs.create(this, name);
                openPack = id;
                showTab(TAB_PACKS);
            } catch (IOException e) { toast("실패: " + e.getMessage()); }
        })), Ui.weight1());
        bar.addView(Ui.round(this, "?", v -> startActivity(new Intent(this, GuideActivity.class))), Ui.after(this));
        packPane.addView(bar);
        packPane.addView(Ui.text(this, "팩 = 이름 붙은 브라우저 규칙 묶음이에요. 스위치로 통째로 켜고 끄고, "
                + "누르면 안의 규칙을 보고 하나씩 일시정지하거나 고칠 수 있어요. 브라우저 규칙에서 여러 개를 길게 눌러 골라 '팩으로' 옮길 수도 있어요.", 13));

        ListView lv = new ListView(this);
        packAdapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return packs.size(); }
            @Override public Object getItem(int i) { return packs.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, android.view.ViewGroup parent) { return packRow(packs.get(i), (LinearLayout) v); }
        };
        lv.setAdapter(packAdapter);
        lv.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 22)));
        lv.setClipToOutline(true);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0xFFEDF1EF));
        lv.setDividerHeight(1);
        lv.setOnItemClickListener((p, v, pos, id) -> { openPack = packs.get(pos).id; showTab(TAB_PACKS); });
        packPane.addView(lv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return packPane;
    }

    /** Name, "규칙 N개 · 일시정지 M개", and the pack's switch at the right end. */
    private View packRow(Packs.Pack p, LinearLayout row) {
        if (row == null) {
            row = Ui.row(this);
            int pad = Ui.dp(this, 16);
            row.setPadding(pad, Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(this, "", 16);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setPadding(0, 0, 0, 0);
            texts.addView(name);
            TextView sub = Ui.text(this, "", 13);
            sub.setPadding(0, Ui.dp(this, 2), 0, 0);
            texts.addView(sub);
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            Switch sw = Ui.toggle(this);
            sw.setFocusable(false);   // row taps still open the pack
            row.addView(sw);
        }
        LinearLayout texts = (LinearLayout) row.getChildAt(0);
        ((TextView) texts.getChildAt(0)).setText(p.name);
        ((TextView) texts.getChildAt(1)).setText("규칙 " + p.rules + "개" + (p.paused > 0 ? " · 일시정지 " + p.paused + "개" : "")
                + (p.on ? " · 켜짐" : " · 꺼짐"));
        Switch sw = (Switch) row.getChildAt(1);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(p.on);
        sw.setOnCheckedChangeListener((b, on) -> setPackOn(p.id, on));
        return row;
    }

    private void reloadPacks() {
        packs.clear();
        packs.addAll(Packs.all(this));
        packAdapter.notifyDataSetChanged();
    }

    private void setPackOn(String id, boolean on) {
        try {
            Packs.setOn(this, id, on);
            toast(Packs.name(this, id) + (on ? " 켬" : " 끔") + " · 삼성 인터넷에서 새로고침하세요");
        } catch (IOException e) { toast("실패: " + e.getMessage()); }
        if (openPack == null && tab == TAB_PACKS) reloadPacks();
    }

    /** Moves the chosen rule lines (pause marks kept) to a pack, or from a pack back to 브라우저 규칙. */
    private void moveToPack(List<String> lines) {
        List<String> targets = new ArrayList<>(), labels = new ArrayList<>();
        if (openPack != null) { targets.add(Lists.BROWSER); labels.add("브라우저 규칙"); }
        for (Packs.Pack p : Packs.all(this)) {
            if (p.id.equals(openPack)) continue;
            targets.add(Packs.file(p.id));
            labels.add(p.name + (p.on ? "" : "  (꺼짐)"));
        }
        targets.add(null);
        labels.add("+ 새 팩 만들기");
        String from = file();
        new AlertDialog.Builder(this)
                .setTitle(lines.size() + "개를 어디로 옮길까요?")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String to = targets.get(which);
                    if (to != null) { move(lines, from, to); return; }
                    askName("새 팩", "", name -> {
                        try { move(lines, from, Packs.file(Packs.create(this, name))); }
                        catch (IOException e) { toast("실패: " + e.getMessage()); }
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void move(List<String> lines, String from, String to) {
        edit(() -> {
            Lists.appendAll(this, to, lines);
            Lists.removeLines(this, from, new HashSet<>(lines));
        }, lines.size() + "개 옮김");
    }

    private void askName(String title, String current, java.util.function.Consumer<String> then) {
        EditText e = Ui.field(this, new EditText(this));
        e.setText(current);
        e.setHint("팩 이름 (예: 유튜브 집중 모드)");
        e.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(pad(e))
                .setPositiveButton("확인", (d, w) -> then.accept(e.getText().toString()))
                .setNegativeButton("취소", null)
                .show();
    }

    /** Domains that matched an "aa*.com##..." rule so far (they are what Samsung Internet gets for the '*'). */
    private void showWildcardSeen() {
        List<String> seen = Wildcards.all(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("와일드카드로 찾은 도메인 " + seen.size() + "개")
                .setNegativeButton("닫기", null);
        if (seen.isEmpty()) {
            b.setMessage("아직 없습니다. 브라우저 규칙에 aa*.com##선택자 처럼 * 를 넣으면, "
                    + "필터가 켜진 동안 맞는 도메인에 접속할 때 여기에 쌓이고 그 규칙에 더해집니다.");
        } else {
            b.setItems(seen.toArray(new String[0]), null)
                    .setNeutralButton("모두 지우기", (d, w) -> confirm("찾은 도메인을 모두 지울까요? 다시 접속하면 다시 쌓입니다.",
                            () -> { Wildcards.clear(this); toast("지웠습니다"); }));
        }
        b.show();
    }

    /** List row: the entry, and at the right end an on/off switch (off = this entry is paused). */
    private final class EntryAdapter extends ArrayAdapter<String> {
        EntryAdapter() { super(ListsActivity.this, 0, new ArrayList<>()); }

        @Override
        public View getView(int pos, View v, android.view.ViewGroup parent) {
            LinearLayout row = (LinearLayout) v;
            if (row == null) {
                row = Ui.row(ListsActivity.this);
                row.setPadding(Ui.dp(ListsActivity.this, 16), Ui.dp(ListsActivity.this, 6), Ui.dp(ListsActivity.this, 8), Ui.dp(ListsActivity.this, 6));
                android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
                bg.addState(new int[]{android.R.attr.state_activated}, new android.graphics.drawable.ColorDrawable(Ui.TONAL));
                row.setBackground(bg);   // selected while long-press selecting
                TextView label = Ui.text(ListsActivity.this, "", 16);
                row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                Switch toggle = Ui.toggle(ListsActivity.this);
                toggle.setFocusable(false);   // keep row taps going to the list
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tp.setMarginStart(Ui.dp(ListsActivity.this, 10));
                row.addView(toggle, tp);
            }
            String raw = raws.get(pos);
            boolean paused = Lists.isPaused(raw);
            TextView label = (TextView) row.getChildAt(0);
            label.setText(getItem(pos));
            label.setTextColor(paused ? 0xFFA0A7A3 : Ui.TEXT);
            label.setPaintFlags(paused ? label.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    : label.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            Switch toggle = (Switch) row.getChildAt(1);   // on = in use, off = paused
            toggle.setOnCheckedChangeListener(null);
            toggle.setChecked(!paused);
            toggle.setContentDescription(paused ? "꺼짐 · 켜면 다시 적용" : "켜짐 · 끄면 일시정지");
            toggle.setOnCheckedChangeListener((b, on) -> edit(() -> Lists.setPaused(ListsActivity.this, file(),
                    java.util.Collections.singleton(raw), !on), on ? "켬" : "끔 (일시정지)"));
            return row;
        }
    }

    // ---------------------------------------------------------------- bundled lists

    private View buildBundledPane() {
        bundledPane = new LinearLayout(this);
        bundledPane.setOrientation(LinearLayout.VERTICAL);
        for (Bundled.Info info : Bundled.LISTS) {
            Switch sw = Ui.toggle(this);
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
        bundledSearch = Ui.field(this, new EditText(this));
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
        results.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 22)));
        results.setClipToOutline(true);
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
