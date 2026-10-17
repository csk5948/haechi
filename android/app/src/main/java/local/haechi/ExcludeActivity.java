package local.haechi;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "해치에서 뺄 앱": checked apps are kept out of the VPN (addDisallowedApplication), so their DNS is not
 * filtered and the split proxy does not apply. For payment and banking apps that refuse to work otherwise.
 * Takes effect when the VPN starts; leaving this screen restarts a running filter so it applies at once.
 */
public class ExcludeActivity extends Activity {
    private static final class App {
        final String pkg, label;
        App(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<App> all = new ArrayList<>(), shown = new ArrayList<>();
    private Set<String> chosen;
    private boolean changed;
    private EditText search;
    private TextView count;
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("해치에서 뺄 앱");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        chosen = Settings.excludedApps(this);

        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "체크한 앱은 해치를 거치지 않아요. 광고도 걸러지지 않지만, 결제·은행 앱처럼 "
                + "VPN이나 차단 때문에 안 되는 앱이 정상으로 동작해요. 삼성페이·삼성 패스는 처음부터 체크되어 있어요.", 13));
        search = Ui.field(this, new EditText(this));
        search.setHint("앱 이름 검색");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
        col.addView(search, Ui.gap(this, 6));
        count = Ui.text(this, "불러오는 중…", 13);
        col.addView(count);

        ListView list = new ListView(this);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 22)));
        list.setClipToOutline(true);
        list.setDivider(new android.graphics.drawable.ColorDrawable(0xFFEDF1EF));
        list.setDividerHeight(1);
        list.setOnItemClickListener((p, v, pos, id) -> toggle(shown.get(pos)));
        col.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(col);

        new Thread(this::loadApps, "apps").start();
    }

    /** Launcher apps (what <queries> lets us see) plus the chosen ones, labels read off the main thread. */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, App> found = new HashMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
            found.put(pkg, new App(pkg, r.loadLabel(pm).toString()));
        }
        for (String pkg : chosen) {
            if (found.containsKey(pkg)) continue;
            try {   // installed but without a launcher icon (e.g. the Samsung Pay framework)
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                found.put(pkg, new App(pkg, ai.loadLabel(pm).toString()));
            } catch (PackageManager.NameNotFoundException ignored) {}   // not installed: stays chosen, not listed
        }
        List<App> apps = new ArrayList<>(found.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        handler.post(() -> {
            if (isDestroyed()) return;
            all.addAll(apps);
            filter();
        });
    }

    /** Checked apps first, then by name; the search matches name or package. */
    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (App a : all) {
            if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q)) shown.add(a);
        }
        Collections.sort(shown, (a, b) -> Boolean.compare(!chosen.contains(a.pkg), !chosen.contains(b.pkg)));
        updateCount();
        adapter.notifyDataSetChanged();
    }

    private void updateCount() {
        int n = 0;
        for (App a : all) if (chosen.contains(a.pkg)) n++;
        count.setText("뺀 앱 " + n + "개 · 전체 " + all.size() + "개");
    }

    private void toggle(App a) {
        if (!chosen.remove(a.pkg)) chosen.add(a.pkg);
        Settings.setExcludedApps(this, chosen);
        changed = true;
        updateCount();
        adapter.notifyDataSetChanged();   // keep the order until the next search, so rows don't jump
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    /** The VPN reads the list when it starts: restart a running filter so the change applies now. */
    @Override
    protected void onPause() {
        super.onPause();
        if (!changed || !BlockerService.running) return;
        changed = false;
        startService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_STOP));
        new Handler(Looper.getMainLooper()).postDelayed(() -> startForegroundService(
                new Intent(getApplicationContext(), BlockerService.class).setAction(BlockerService.ACTION_START)), 800);
        Toast.makeText(this, "필터를 다시 시작해 적용했어요", Toast.LENGTH_SHORT).show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            Row row = v == null ? new Row() : (Row) v.getTag();
            App a = shown.get(i);
            row.label.setText(a.label);
            row.pkg.setText(a.pkg);
            row.check.setChecked(chosen.contains(a.pkg));
            try {
                row.icon.setImageDrawable(getPackageManager().getApplicationIcon(a.pkg));
            } catch (PackageManager.NameNotFoundException e) {
                row.icon.setImageDrawable(null);
            }
            return row.root;
        }
    }

    private final class Row {
        final LinearLayout root = Ui.row(ExcludeActivity.this);
        final ImageView icon = new ImageView(ExcludeActivity.this);
        final TextView label = Ui.text(ExcludeActivity.this, "", 16), pkg = Ui.text(ExcludeActivity.this, "", 12);
        final CheckBox check = new CheckBox(ExcludeActivity.this);

        Row() {
            int p = Ui.dp(ExcludeActivity.this, 12);
            root.setPadding(p, Ui.dp(ExcludeActivity.this, 8), p, Ui.dp(ExcludeActivity.this, 8));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(ExcludeActivity.this, 40), Ui.dp(ExcludeActivity.this, 40));
            ip.setMarginEnd(p);
            root.addView(icon, ip);
            LinearLayout texts = new LinearLayout(ExcludeActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(0, 0, 0, 0);
            pkg.setPadding(0, 0, 0, 0);
            texts.addView(label);
            texts.addView(pkg);
            root.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            check.setClickable(false);   // the whole row toggles
            check.setFocusable(false);
            root.addView(check);
            root.setTag(this);
        }
    }
}
