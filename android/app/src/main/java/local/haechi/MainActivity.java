package local.haechi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
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
    private TextView status, counts, dohFail, samsungDiag, splitStat;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { Lists.load(this); } catch (IOException ignored) {}

        LinearLayout col = Ui.column(this);
        toggle = new Button(this);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        toggle.setMinHeight(Ui.dp(this, 96));
        toggle.setOnClickListener(v -> onToggle());
        col.addView(toggle);

        status = Ui.text(this, "", 18);
        counts = Ui.text(this, "", 15);
        col.addView(status);
        col.addView(counts);

        col.addView(Ui.button(this, "기록 보기 (어떤 도메인에 접속했나)", v ->
                startActivity(new Intent(this, LogActivity.class))));
        col.addView(Ui.button(this, "목록 관리 (차단 · 허용 · 브라우저 규칙 · 기본 제공 목록)", v ->
                startActivity(new Intent(this, ListsActivity.class))));
        col.addView(Ui.button(this, "광고 요소 골라 숨기기 (앱 안 브라우저 → 삼성 인터넷 규칙)", v ->
                startActivity(new Intent(this, PickerActivity.class))));
        col.addView(Ui.text(this, "삼성 인터넷 연동 방법\n"
                + "1) 삼성 인터넷 주소창에 internet://contentblock 입력 → 개발자 모드 켜기\n"
                + "2) 삼성 인터넷 설정 › 광고 차단기 에서 '광고 차단' 켜기\n"
                + "3) example.com 에서 제목이 사라지면 요소 숨김 동작", 13));
        col.addView(Ui.button(this, "삼성 인터넷 광고 차단기 설정 열기", v -> openSamsungSettings()));
        samsungDiag = Ui.text(this, "", 12);
        col.addView(samsungDiag);

        // ---- DNS server setting (applies to the running filter immediately) ----
        Settings.load(this);
        TextView dnsTitle = Ui.text(this, "DNS 서버", 17);
        dnsTitle.setPadding(0, Ui.dp(this, 20), 0, 0);
        col.addView(dnsTitle);
        RadioGroup dns = new RadioGroup(this);
        String[][] options = {
                {Settings.CLOUDFLARE, "Cloudflare 암호화 (1.1.1.1)"},
                {Settings.GOOGLE, "Google 암호화 (8.8.8.8)"},
                {Settings.CARRIER, "통신사 DNS (암호화 안 함)"}};
        String current = Settings.dnsMode(this);
        for (String[] o : options) {
            RadioButton r = new RadioButton(this);
            r.setText(o[1]);
            r.setId(View.generateViewId());
            r.setOnClickListener(v -> Settings.setDnsMode(this, o[0]));
            dns.addView(r);
            if (o[0].equals(current)) dns.check(r.getId());
        }
        col.addView(dns);
        col.addView(Ui.text(this, "암호화 DNS를 쓰면 통신사가 DNS를 조작할 수 없어요(warning.or.kr 방지). "
                + "대신 어떤 도메인에 접속하는지는 선택한 회사가 보게 돼요.", 13));
        dohFail = Ui.text(this, "", 13);
        dohFail.setTextColor(0xFFD32F2F);
        col.addView(dohFail);

        // ---- browser proxy that splits the TLS ClientHello (read when the VPN starts) ----
        TextView splitTitle = Ui.text(this, "SNI 쪼개기 (브라우저용 로컬 프록시)", 17);
        splitTitle.setPadding(0, Ui.dp(this, 20), 0, 0);
        col.addView(splitTitle);
        Switch split = new Switch(this);
        split.setText("크롬·삼성 인터넷 HTTPS를 앱 안 중계기로 보내 첫 패킷 쪼개기");
        split.setEnabled(Build.VERSION.SDK_INT >= 29);
        split.setChecked(Settings.splitHello(this));
        split.setOnCheckedChangeListener((v, on) -> {
            Settings.setSplitHello(this, on);
            if (BlockerService.running)
                Toast.makeText(this, "끄기 → 켜기를 한 번 해야 적용됩니다", Toast.LENGTH_LONG).show();
        });
        col.addView(split);
        col.addView(Ui.text(this, "켜면 VPN이 이 기기의 HTTP 프록시를 127.0.0.1(앱 안)으로 지정합니다. 프록시 설정을 따르는 앱의 "
                + "HTTP/HTTPS 통신이 모두 이 중계기를 거치고, HTTPS는 사이트 이름이 든 첫 TLS 패킷을 둘로 나눠 보냅니다. "
                + "내용을 풀어 보지 않고(인증서 불필요) 그대로 전달만 합니다. Android 10 이상, 프록시 설정을 무시하는 앱은 해당 없음.", 13));
        RadioGroup splitMode = new RadioGroup(this);
        String[] modes = {"1) TCP 조각 2개", "2) TCP 조각 2개 + 짧은 간격 (기본)", "3) TLS 레코드도 둘로 + TCP 조각 + 간격"};
        int curMode = Settings.splitMode(this);
        for (int i = 0; i < modes.length; i++) {
            final int mode = i;
            RadioButton r = new RadioButton(this);
            r.setText(modes[i]);
            r.setId(View.generateViewId());
            r.setOnClickListener(v -> Settings.setSplitMode(this, mode));   // next connection on
            splitMode.addView(r);
            if (i == curMode) splitMode.check(r.getId());
        }
        col.addView(splitMode);
        col.addView(Ui.text(this, "방식은 바로 적용됩니다. 안 될 때 1번부터 차례로 바꿔 보고 아래 '무응답'이 가장 적은 방식을 쓰세요. "
                + "무응답 = 쪼개 보냈는데 사이트가 한 바이트도 안 주고 끊은 연결 수(막혔거나, 쪼개기를 서버가 거부한 경우).", 13));
        splitStat = Ui.text(this, "", 13);
        col.addView(splitStat);

        TextView guide = Ui.text(this,
                "안내\n"
                + "• 설정 > 네트워크 > 개인 DNS 를 '끄기' 또는 '자동'으로 두세요. 호스트 이름을 직접 지정하면 "
                + "DNS가 이 앱을 거치지 않아 차단되지 않습니다.\n"
                + "• 폰을 켤 때 자동으로 시작하려면 설정 > 네트워크 > VPN 에서 이 앱의 '상시 사용 VPN'을 켜세요.\n"
                + "• DNS 차단은 도메인 단위입니다. YouTube처럼 광고와 본문이 같은 도메인에서 오면 막을 수 없습니다.\n"
                + "• 열쇠(VPN) 아이콘은 Android의 VPN 기능을 쓰기 때문에 보입니다. 실제로 이 앱으로 들어오는 것은 "
                + "DNS 질의뿐이고, 나머지 통신은 평소처럼 바로 나갑니다.\n"
                + "• 기록은 메모리에만 있고 저장되지 않으며, 앱이 종료되면 사라집니다.", 14);
        guide.setPadding(0, Ui.dp(this, 24), 0, 0);
        col.addView(guide);

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
        toggle.setText(on ? "끄기" : "켜기");
        status.setText(on ? "켜짐 · 광고 도메인을 차단하고 있습니다" : "꺼짐");
        counts.setText("질의 " + BlockerService.queries.get() + " · 차단 " + BlockerService.blocked.get()
                + "\n내 차단 목록 " + Lists.blockSize() + "개 · 기본 제공 "
                + (Bundled.isReady() ? Bundled.blockCount() + "개" : Bundled.isLoading() ? "불러오는 중" : "꺼짐 상태에선 안 읽음"));
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
