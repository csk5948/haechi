package local.haechi;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.IOException;

/** Plain-text editor for block.txt or allow.txt. Saving reloads the running filter at once. */
public class EditActivity extends Activity {
    static final String EXTRA_FILE = "file";

    private String file;
    private EditText edit;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String want = getIntent().getStringExtra(EXTRA_FILE);
        file = Lists.ALLOW.equals(want) ? Lists.ALLOW : Lists.BROWSER.equals(want) ? Lists.BROWSER : Lists.BLOCK;
        String help;
        if (Lists.BLOCK.equals(file)) {
            setTitle("차단 목록 편집");
            help = "한 줄에 도메인 하나. 하위 도메인까지 모두 차단됩니다. # 또는 ! 로 시작하면 주석.";
        } else if (Lists.ALLOW.equals(file)) {
            setTitle("허용 목록 편집");
            help = "여기 적은 도메인(과 하위 도메인)은 차단 목록에 있어도 항상 허용됩니다.";
        } else {
            setTitle("브라우저 규칙 (삼성 인터넷)");
            help = "삼성 인터넷에만 적용되는 Adblock Plus 형식 규칙.\n"
                    + "도메인##CSS선택자 = 그 사이트에서 요소 숨김, ||도메인^ = 요청 차단, ! = 주석.\n"
                    + "aa*.com##선택자 = aa1.com, aa2.com … 같은 미러 사이트 모두 (해치가 접속을 보고 채워 넣음).\n"
                    + "저장하면 삼성 인터넷에 새 규칙을 알립니다.";
        }

        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, help, 13));

        edit = Ui.field(this, new EditText(this));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextSize(14);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setHorizontallyScrolling(false);
        try {
            edit.setText(Lists.read(this, file));
        } catch (IOException e) {
            Toast.makeText(this, "읽기 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        col.addView(edit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (Lists.BROWSER.equals(file)) {
            col.addView(Ui.button(this, "쓰는 법 (예시 보기)", v ->
                    startActivity(new android.content.Intent(this, GuideActivity.class))));
        }
        col.addView(Ui.primary(this, "저장", v -> save()), Ui.gap(this, 8));
        setContentView(col);
    }

    private void save() {
        try {
            Lists.save(this, file, edit.getText().toString());
            Toast.makeText(this, "저장했습니다 · 바로 적용됩니다", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "저장 실패: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
