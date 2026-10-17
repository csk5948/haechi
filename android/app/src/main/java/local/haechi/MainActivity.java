package local.haechi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.widget.ImageView;
import android.view.Gravity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Button;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Main screen: on/off, counters, links to the log and list editors, short guide. */
public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;
    static final String EXTRA_AUTO_START = "auto_start";

    private Button toggle;
    private LinearLayout hero;
    private ImageView art;
    private TextView status, counts, dohFail, samsungDiag, splitStat;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { Lists.load(this); } catch (IOException ignored) {}
        if (getActionBar() != null) getActionBar().hide();   // the hero card is the header here

        LinearLayout col = Ui.column(this);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 24));

        // ---- hero: mascot, state, counters, on/off ----
        hero = Ui.card(this);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 20));
        art = new ImageView(this);
        art.setImageResource(R.drawable.haechi_art);
        art.setBackground(Ui.rounded(Ui.PRIMARY, Ui.dp(this, 32)));
        art.setClipToOutline(true);
        hero.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        status = Ui.text(this, "", 24);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(this, 14), 0, 0);
        hero.addView(status);
        counts = Ui.text(this, "", 14);
        counts.setGravity(Gravity.CENTER);
        hero.addView(counts);
        toggle = Ui.primary(this, "", v -> onToggle());
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        toggle.setMinHeight(Ui.dp(this, 56));
        hero.addView(toggle, Ui.gap(this, 12));
        col.addView(hero, Ui.cardParams(this));

        // ---- screens ----
        LinearLayout menu = Ui.card(this);
        menu.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        menu.addView(navRow("기록 보기", "어떤 앱이 어떤 도메인에 접속했나", LogActivity.class));
        menu.addView(navRow("목록 관리", "차단 · 허용 · 브라우저 규칙 · 기본 제공 목록", ListsActivity.class));
        menu.addView(navRow("광고 요소 골라 숨기기", "앱 안 브라우저에서 광고를 눌러 삼성 인터넷 규칙 만들기", PickerActivity.class));
        menu.addView(navRow("해치에서 뺄 앱", "결제·은행 앱처럼 해치를 거치면 안 되는 앱", ExcludeActivity.class));
        col.addView(menu, Ui.cardParams(this));

        // ---- Samsung Internet ----
        LinearLayout samsung = Ui.card(this);
        samsung.addView(Ui.heading(this, "삼성 인터넷 광고 차단"));
        samsung.addView(Ui.text(this, "1. 삼성 인터넷 주소창에 internet://contentblock 입력 → 개발자 모드 켜기\n"
                + "2. 삼성 인터넷 설정 › 광고 차단기 에서 '해치' 켜기\n"
                + "3. example.com 에서 제목이 사라지면 요소 숨김 동작", 14));
        samsung.addView(Ui.button(this, "광고 차단기 설정 열기", v -> openSamsungSettings()), Ui.gap(this, 6));
        samsungDiag = Ui.text(this, "", 12);
        samsung.addView(samsungDiag);
        col.addView(samsung, Ui.cardParams(this));

        // ---- DNS server setting (applies to the running filter immediately) ----
        Settings.load(this);
        LinearLayout dnsCard = Ui.card(this);
        dnsCard.addView(Ui.heading(this, "DNS 서버"));
        RadioGroup dns = new RadioGroup(this);
        String[][] options = {
                {Settings.CLOUDFLARE, "Cloudflare 암호화 (1.1.1.1)"},
                {Settings.GOOGLE, "Google 암호화 (8.8.8.8)"},
                {Settings.CARRIER, "통신사 DNS (암호화 안 함)"}};
        String current = Settings.dnsMode(this);
        for (String[] o : options) {
            RadioButton r = radio(o[1]);
            r.setOnClickListener(v -> Settings.setDnsMode(this, o[0]));
            dns.addView(r);
            if (o[0].equals(current)) dns.check(r.getId());
        }
        dnsCard.addView(dns);
        dnsCard.addView(Ui.text(this, "암호화 DNS를 쓰면 통신사가 DNS를 조작할 수 없어요(warning.or.kr 방지). "
                + "대신 어떤 도메인에 접속하는지는 선택한 회사가 보게 돼요.", 13));
        dohFail = Ui.text(this, "", 13);
        dohFail.setTextColor(Ui.DANGER);
        dnsCard.addView(dohFail);
        col.addView(dnsCard, Ui.cardParams(this));

        // ---- browser proxy that splits the TLS ClientHello (read when the VPN starts) ----
        LinearLayout splitCard = Ui.card(this);
        Switch split = new Switch(this);
        split.setText("SNI 쪼개기");
        split.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        split.setTypeface(Typeface.DEFAULT_BOLD);
        split.setTextColor(Ui.TEXT);
        split.setEnabled(Build.VERSION.SDK_INT >= 29);
        split.setChecked(Settings.splitHello(this));
        split.setOnCheckedChangeListener((v, on) -> {
            Settings.setSplitHello(this, on);
            if (BlockerService.running)
                Toast.makeText(this, "끄기 → 켜기를 한 번 해야 적용됩니다", Toast.LENGTH_LONG).show();
        });
        splitCard.addView(split);
        splitCard.addView(Ui.text(this, "크롬·삼성 인터넷의 HTTPS를 앱 안 중계기(127.0.0.1)로 보내, 사이트 이름이 든 첫 TLS 패킷을 "
                + "둘로 나눠 보냅니다. 내용은 풀어 보지 않고(인증서 불필요) 전달만 합니다. Android 10 이상, "
                + "프록시 설정을 무시하는 앱은 해당 없음.", 13));
        splitCard.addView(Ui.label(this, "쪼개는 방식"), Ui.gap(this, 8));
        RadioGroup splitMode = new RadioGroup(this);
        String[] modes = {"TCP 조각 2개", "TCP 조각 2개 + 짧은 간격 (기본)", "TLS 레코드도 둘로 + TCP 조각 + 간격"};
        int curMode = Settings.splitMode(this);
        for (int i = 0; i < modes.length; i++) {
            final int mode = i;
            RadioButton r = radio(modes[i]);
            r.setOnClickListener(v -> Settings.setSplitMode(this, mode));   // next connection on
            splitMode.addView(r);
            if (i == curMode) splitMode.check(r.getId());
        }
        splitCard.addView(splitMode);
        splitCard.addView(Ui.text(this, "방식은 바로 적용됩니다. 안 될 때 위에서부터 바꿔 보고 '무응답'이 가장 적은 방식을 쓰세요. "
                + "무응답 = 쪼개 보냈는데 사이트가 한 바이트도 안 주고 끊은 연결 수.", 13));
        splitStat = Ui.text(this, "", 13);
        splitCard.addView(splitStat);
        col.addView(splitCard, Ui.cardParams(this));

        LinearLayout guide = Ui.card(this);
        guide.addView(Ui.heading(this, "알아 두기"));
        guide.addView(Ui.text(this,
                "• 설정 › 네트워크 › 개인 DNS 는 '끄기' 또는 '자동'으로 두세요. 호스트 이름을 직접 지정하면 "
                + "DNS가 해치를 거치지 않아 차단되지 않아요.\n"
                + "• 폰을 켤 때 자동으로 시작하려면 설정 › 네트워크 › VPN 에서 해치의 '상시 사용 VPN'을 켜세요.\n"
                + "• DNS 차단은 도메인 단위예요. YouTube처럼 광고와 본문이 같은 도메인에서 오면 막을 수 없어요.\n"
                + "• 열쇠(VPN) 아이콘은 Android의 VPN 기능을 쓰기 때문에 보여요. 해치로 들어오는 것은 "
                + "DNS 질의뿐이고, 나머지 통신은 평소처럼 바로 나가요.\n"
                + "• 해치를 거치면 안 되는 앱(결제·은행 등)은 '해치에서 뺄 앱'에서 고르세요. "
                + "삼성페이·삼성 패스는 처음부터 빠져 있어요.\n"
                + "• 기록은 메모리에만 있고, 앱이 종료되면 사라져요.", 14));
        col.addView(guide, Ui.cardParams(this));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        autoStart(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        autoStart(intent);
    }

    /** Opened from the Quick Settings tile because VPN consent is needed: ask, then start. */
    private void autoStart(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_AUTO_START, false) && !BlockerService.running) {
            i.removeExtra(EXTRA_AUTO_START);   // don't repeat on rotation
            onToggle();
        }
    }

    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(ticker); }

    private void refresh() {
        boolean on = BlockerService.running;
        if (shownOn == null || on != shownOn) {   // repaint the hero only when the state flips
            shownOn = on;
            toggle.setText(on ? "끄기" : "켜기");
            if (on) Ui.tint(this, toggle, Ui.ON_PRIMARY, Ui.PRIMARY);
            else Ui.tint(this, toggle, Ui.PRIMARY, Ui.ON_PRIMARY);
            hero.setBackground(Ui.rounded(on ? Ui.PRIMARY : Ui.CARD, Ui.dp(this, 22)));
            art.setImageResource(on ? R.drawable.haechi_art_on : R.drawable.haechi_art);   // on: holding a shield
            status.setTextColor(on ? Ui.ON_PRIMARY : Ui.TEXT);
            counts.setTextColor(on ? 0xDDFFFFFF : Ui.SUB);
        }
        status.setText(on ? "광고를 막고 있어요" : "꺼져 있어요");
        counts.setText(String.format(java.util.Locale.KOREA, "질의 %,d · 차단 %,d\n내 차단 목록 %,d개 · 기본 제공 ",
                BlockerService.queries.get(), BlockerService.blocked.get(), Lists.blockSize())
                + (Bundled.isReady() ? String.format(java.util.Locale.KOREA, "%,d개", Bundled.blockCount()) : Bundled.isLoading() ? "불러오는 중" : "꺼짐 상태에선 안 읽음"));
        splitStat.setText(on && Settings.splitHello(this)
                ? BlockerService.splitStatus + "\n중계기 연결 " + SplitProxy.connections.get() + " · 쪼갠 TLS 연결 " + SplitProxy.splits.get() + " · 무응답 " + SplitProxy.splitFails.get() : "");
        int fails = BlockerService.dohFailures.get();
        String st = BlockerService.dohStatus;
        dohFail.setText((st.isEmpty() ? "" : "암호화 DNS: " + st + "\n")
                + (fails > 0 ? "암호화 DNS 실패 " + fails + "회 (그때마다 통신사 DNS 사용)\n마지막 실패 이유: "
                        + BlockerService.lastDohError : ""));
        dohFail.setTextIsSelectable(true);
        dohFail.setVisibility(fails > 0 || !st.isEmpty() ? View.VISIBLE : View.GONE);
        String diag = ContentBlockerProvider.lastRead(this);
        if (!diag.contentEquals(samsungDiag.getText())) samsungDiag.setText(diag);
    }

    private Boolean shownOn;

    /** One tappable row of the menu card: title, one-line description, chevron. */
    private View navRow(String title, String sub, Class<?> screen) {
        LinearLayout row = Ui.row(this);
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        row.setBackground(Ui.ripple(Ui.rounded(Ui.CARD, Ui.dp(this, 16)), Ui.dp(this, 16)));
        row.setOnClickListener(v -> startActivity(new Intent(this, screen)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(this, title, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, 0);
        TextView d = Ui.text(this, sub, 13);
        d.setPadding(0, Ui.dp(this, 2), 0, 0);
        texts.addView(t);
        texts.addView(d);
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = Ui.text(this, "›", 26);
        row.addView(chevron);
        return row;
    }

    private RadioButton radio(String label) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        r.setTextColor(Ui.TEXT);
        r.setId(View.generateViewId());
        r.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        return r;
    }

    private void onToggle() {
        if (BlockerService.running) {
            startService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_STOP));
            handler.postDelayed(this::refresh, 300);
            return;
        }
        Intent consent = VpnService.prepare(this);   // null = already allowed
        if (consent != null) {
            try {
                startActivityForResult(consent, REQ_VPN);
            } catch (RuntimeException e) {
                Toast.makeText(this, "VPN 권한 화면을 열 수 없습니다", Toast.LENGTH_LONG).show();
            }
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req != REQ_VPN) return;
        if (result != RESULT_OK) {
            Toast.makeText(this, "VPN 연결을 허용해야 켤 수 있습니다", Toast.LENGTH_LONG).show();
            return;
        }
        startForegroundService(new Intent(this, BlockerService.class).setAction(BlockerService.ACTION_START));
        handler.postDelayed(this::refresh, 500);
    }

    /** Opens Samsung Internet's "Ad blockers" settings (intent from Samsung's guide), if installed. */
    private void openSamsungSettings() {
        Intent i = new Intent(SamsungBlocker.ACTION_SETTING);
        if (getPackageManager().queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            Toast.makeText(this, "삼성 인터넷을 찾을 수 없습니다", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Toast.makeText(this, "열 수 없습니다", Toast.LENGTH_LONG).show();
        }
    }

}
