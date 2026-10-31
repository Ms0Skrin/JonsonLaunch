package home.lite.tv;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Меню приложения по долгому OK: карточка рядом с плиткой, как контекстное меню в tvOS. */
final class AppMenu extends FrameLayout {

    private final float dp;
    private final View dim;
    private final LinearLayout card;
    private final List<View> items = new ArrayList<>();

    /** anchor — плитка, рядом с которой появится карточка. */
    AppMenu(MainActivity host, String title, View anchor) {
        super(host);
        dp = host.getResources().getDisplayMetrics().density;
        setClickable(true);

        dim = new View(host);
        dim.setBackgroundColor(0x66000000);
        addView(dim, new LayoutParams(-1, -1));

        card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01C1C1E);
        bg.setCornerRadius(px(22));
        card.setBackground(bg);
        card.setElevation(px(24));
        card.setPadding(px(10), px(14), px(10), px(10));
        TextView head = label(title, 13, 0x99FFFFFF, "sans-serif-medium");
        head.setLetterSpacing(0.06f);
        head.setSingleLine(true);
        head.setPadding(px(14), 0, px(14), px(8));
        card.addView(head);

        this.anchor = anchor;
        addView(card, new LayoutParams(px(280), -2));
    }

    private final View anchor;

    /** Под плиткой, если карточка там помещается, иначе над ней, а если и там тесно — сбоку от плитки. */
    private void place() {
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        card.measure(MeasureSpec.makeMeasureSpec(px(280), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED);
        int h = card.getMeasuredHeight(), gap = px(18), edge = px(24);
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        int w = px(280), below = at[1] + anchor.getHeight() + gap, above = at[1] - gap - h;
        lp.leftMargin = Math.max(edge, Math.min(at[0], screenW - w - edge));
        if (below + h + edge <= screenH) {
            lp.topMargin = below;
        } else if (above >= edge) {
            lp.topMargin = above;
        } else {
            int right = at[0] + anchor.getWidth() + gap;
            lp.leftMargin = right + w + edge <= screenW ? right : Math.max(edge, at[0] - gap - w);
            lp.topMargin = Math.max(edge, Math.min(at[1] + anchor.getHeight() / 2 - h / 2, screenH - h - edge));
        }
        card.setLayoutParams(lp);
    }

    private int px(float v) { return Math.round(v * dp); }

    void add(String name, final Runnable run) {
        final TextView t = label(name, 16, Color.WHITE, "sans-serif");
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(14), 0, px(14), 0);
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(14));
        bg.setColor(0);
        t.setBackground(bg);
        t.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? Color.WHITE : 0);
                t.setTextColor(has ? 0xFF1C1C1E : Color.WHITE);
            }
        });
        t.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(46));
        lp.topMargin = px(2);
        card.addView(t, lp);
        items.add(t);
    }

    void show() {
        place();
        dim.setAlpha(0f);
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        dim.animate().alpha(1f).setDuration(160).start();
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        if (!items.isEmpty()) items.get(0).requestFocus();
    }

    void hide(Runnable end) {
        dim.animate().alpha(0f).setDuration(120).start();
        card.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(120).withEndAction(end).start();
    }

    private TextView label(String s, float sp, int color, String family) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }
}
