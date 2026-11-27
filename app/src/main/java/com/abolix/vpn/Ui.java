package com.abolix.vpn;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.parseColor("#070B16");
    static final int CARD = Color.parseColor("#111A2E");
    static final int CARD2 = Color.parseColor("#17233D");
    static final int TEAL = Color.parseColor("#00E5A0");
    static final int BLUE = Color.parseColor("#00B7FF");
    static final int AMBER = Color.parseColor("#FFB300");
    static final int RED = Color.parseColor("#FF4D6D");
    static final int TEXT = Color.parseColor("#EAF0FF");
    static final int DIM = Color.parseColor("#8A97B5");

    private Ui() { }

    static int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(CARD, 18, c));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h, Context c, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static Button btn(Context c, String s, int color, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color == TEAL || color == AMBER ? Color.parseColor("#04120D") : TEXT);
        b.setTextSize(14);
        b.setBackground(round(color, 14, c));
        b.setOnClickListener(l);
        b.setStateListAnimator(null);
        return b;
    }

    static EditText edit(Context c, String hint, String text) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(text);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setTextSize(13);
        e.setBackground(round(CARD2, 12, c));
        int p = dp(c, 10);
        e.setPadding(p, p, p, p);
        return e;
    }

    static String speed(long bps) {
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB/s", bps / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB/s", bps / 1048576.0);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
