package local.haechi;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** "브라우저 규칙 쓰는 법": the browser.txt syntax with examples; tap an example to copy it. */
public class GuideActivity extends Activity {
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("브라우저 규칙 쓰는 법");
        col = Ui.column(this);

        note("예시를 누르면 복사됩니다. 직접 쓰기 어려우면 '광고 요소 골라 숨기기'에서 광고를 누르면 자동으로 만들어지고, "
                + "그 화면의 선택자 칸을 고치면 '일치 N개'로 바로 확인할 수 있어요.");

        section("1. 규칙 한 줄의 모양");
        note("사이트##숨길것 — ## 앞은 어느 사이트에서, 뒤는 무엇을 숨길지.");
        example("namu.wiki##.ad-box", "나무위키에서 클래스가 ad-box인 것을 숨김");

        section("2. 사이트 쓰는 법 (## 앞)");
        example("namu.wiki##.ad", "이 사이트와 하위 주소 (www.namu.wiki, m.namu.wiki …)");
        example("a.com,b.com##.ad", "여러 사이트는 쉼표로");
        example("aa*.com##.ad", "aa1.com, aa2.com … 이름만 바뀌는 미러 사이트 (* = 글자·숫자 아무거나, 해치 전용 기능)");
        example("namu.wiki,~m.namu.wiki##.ad", "~ 를 붙이면 그 사이트는 빼고");
        example("##.ad-box", "비우면 모든 사이트 — 흔한 이름이면 엉뚱한 게 숨겨질 수 있어 조심");

        section("3. 숨길 것 — 기본 (## 뒤)");
        example("##.ad-box", ". = 클래스 이름이 ad-box");
        example("##.ad.top", "클래스 ad 와 top 을 둘 다 가진 것");
        example("###banner", "# = id 가 banner (## 다음에 #banner)");
        example("##div.ad", "div 중에서 클래스가 ad 인 것");
        example("##iframe", "모든 iframe (외부 광고 틀)");
        example("##.ad-box, .ad-side", "쉼표로 여러 개를 한 줄에");

        section("4. 이름의 일부로 (숫자가 매번 바뀔 때)");
        example("##[class^=\"ad-\"]", "^= 클래스가 ad- 로 시작 (ad-1234, ad-top)");
        example("##[class*=\"banner\"]", "*= 클래스에 banner 가 들어 있음 (top_banner_3)");
        example("##[class$=\"-ad\"]", "$= 클래스가 -ad 로 끝남 (side-ad)");
        example("##[id^=\"google_ads\"]", "id 에도 똑같이");
        example("##a[href*=\"coupang.com\"]", "링크 주소에 coupang.com 이 든 링크");
        example("##img[src*=\"/ads/\"]", "그림 주소에 /ads/ 가 든 그림");

        section("5. 위치·구조로 (이름이 통째로 무작위일 때)");
        example("##.list > li", "A > B = A 바로 안의 B");
        example("##.list li", "A B (띄어쓰기) = A 안 어디든 B");
        example("##.list > li:nth-of-type(3)", "같은 종류 중 3번째");
        example("##div:has(> a[href*=\"ad.\"])", ":has() = 이런 걸 품은 상자를 통째로");
        note("구조로 만든 규칙은 사이트가 화면 구조를 바꾸면 안 맞게 됩니다. 그때는 다시 고르세요.");

        section("6. 다른 종류의 줄");
        example("||ads.example.com^", "이 주소에서 오는 것 자체를 막기 (요청 차단)");
        example("@@||example.com^", "예외: 이 주소는 막지 않기");
        example("! 메모", "! 로 시작하면 메모 (삼성 인터넷에 안 보냄)");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    private void section(String title) {
        TextView t = Ui.text(this, title, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.TEXT);
        t.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 4));
        col.addView(t);
    }

    private void note(String s) {
        col.addView(Ui.text(this, s, 13));
    }

    private void example(String rule, String meaning) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        box.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        TextView code = Ui.text(this, rule, 14);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextColor(Ui.ON_TONAL);
        code.setPadding(0, Ui.dp(this, 2), 0, 0);
        box.addView(code);
        TextView what = Ui.text(this, meaning, 12);
        what.setPadding(0, 0, 0, Ui.dp(this, 2));
        box.addView(what);
        box.setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("rule", rule));
            Toast.makeText(this, "복사했습니다: " + rule, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        col.addView(box, lp);
    }
}
