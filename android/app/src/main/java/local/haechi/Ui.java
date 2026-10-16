package local.haechi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.graphics.drawable.StateListDrawable;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

/**
 * Tiny helpers so the screens can be built in code without layout XML, and one look for all of them:
 * light green-grey page, white rounded cards, pill buttons (tonal by default, filled for the main action).
 */
final class Ui {
    private Ui() {}

    static final int PRIMARY = 0xFF178759, ON_PRIMARY = 0xFFFFFFFF, TONAL = 0xFFDDF0E5, ON_TONAL = 0xFF0E5A3A;
    static final int TEXT = 0xFF1B1F1D, SUB = 0xFF5F6763, CARD = 0xFFFFFFFF, FIELD = 0xFFEDF1EF, DANGER = 0xFFC62828;

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, dp(c, 4), p, p);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** Body text; small sizes are secondary (grey), 16sp and up are primary. */
    static TextView text(Context c, String s, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(sp >= 16 ? TEXT : SUB);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return t;
    }

    /** Small green label above a group of settings. */
    static TextView label(Context c, String s) {
        TextView t = text(c, s, 13);
        t.setTextColor(PRIMARY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(c, 4));
        return t;
    }

    /** Card title. */
    static TextView heading(Context c, String s) {
        TextView t = text(c, s, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(c, 4));
        return t;
    }

    /** Tonal pill button (light green, dark green text). */
    static Button button(Context c, String label, View.OnClickListener l) {
        return pill(c, label, l, TONAL, ON_TONAL);
    }

    /** Filled pill button for the main action of a screen. */
    static Button primary(Context c, String label, View.OnClickListener l) {
        return pill(c, label, l, PRIMARY, ON_PRIMARY);
    }

    static Button pill(Context c, String label, View.OnClickListener l, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setOnClickListener(l);
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        tint(c, b, bg, fg);
        return b;
    }

    /** Small round tonal button for one symbol (?, ⋯, ◀ …). */
    static Button round(Context c, String symbol, View.OnClickListener l) {
        Button b = pill(c, symbol, l, TONAL, ON_TONAL);
        b.setMinWidth(dp(c, 44));
        b.setMinimumWidth(dp(c, 44));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    /** Wrap-content params with a gap after (for rows of small buttons). */
    static LinearLayout.LayoutParams after(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44));
        lp.setMarginStart(dp(c, 6));
        return lp;
    }

    /** Recolours a pill button (e.g. 켜기 filled ↔ 끄기 tonal). */
    static void tint(Context c, Button b, int bg, int fg) {
        b.setBackground(ripple(rounded(bg, dp(c, 24)), dp(c, 24)));
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{(fg & 0x00FFFFFF) | 0x61000000, fg}));
    }

    /** White rounded card; add views to it, then add it with {@link #cardParams}. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        l.setBackground(rounded(CARD, dp(c, 22)));
        return l;
    }

    static LinearLayout.LayoutParams cardParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        return lp;
    }

    /** Text field with a soft filled background instead of the underline. */
    static <T extends EditText> T field(Context c, T e) {
        e.setBackground(rounded(FIELD, dp(c, 14)));
        int h = dp(c, 14), v = dp(c, 11);
        e.setPadding(h, v, h, v);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xFF8A928E);
        return e;
    }

    /** RadioButton drawn as a pill chip (filled green when selected), for tabs and filters. */
    static RadioButton chip(Context c, RadioButton r) {
        r.setButtonDrawable(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_checked}, rounded(PRIMARY, dp(c, 18)));
        bg.addState(new int[]{}, rounded(FIELD, dp(c, 18)));
        r.setBackground(bg);
        r.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{ON_PRIMARY, TEXT}));
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        r.setTypeface(Typeface.DEFAULT_BOLD);
        r.setGravity(Gravity.CENTER);
        r.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        return r;
    }

    /** Spacing between chips in a horizontal RadioGroup. */
    static RadioGroup.LayoutParams chipParams(Context c) {
        RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT, RadioGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, 8));
        lp.topMargin = dp(c, 4);
        lp.bottomMargin = dp(c, 4);
        return lp;
    }

    static LinearLayout.LayoutParams gap(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static Drawable ripple(Drawable content, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), content, rounded(0xFF000000, radius));
    }

    /** Layout params that share a row's width equally (with a small gap between). */
    static LinearLayout.LayoutParams weight1() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(8);
        return lp;
    }
}
