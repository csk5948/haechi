package local.adblock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;

/**
 * Share target "광고 차단에 추가": share a link (or any text containing one) from another app,
 * pick how wide to block, and it is added to block.txt. No window of its own besides the dialog.
 */
public class ShareActivity extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { Lists.load(this); } catch (IOException ignored) {}
        Intent i = getIntent();
        CharSequence text = i == null ? null : i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text == null && i != null) text = i.getStringExtra(Intent.EXTRA_SUBJECT);
        String host = Domains.extractHost(text);
        if (host == null) {
            Toast.makeText(this, "공유한 내용에서 주소를 찾지 못했습니다", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Domains.chooseScopeAndBlock(this, host, this::finish);
    }
}
