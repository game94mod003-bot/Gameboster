package com.abolix.vpn;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Lion-and-Sun style emblem used as the connect button. */
final class ConnectView extends View {
    private int state = TunnelService.OFF;
    private float phase;
    private ValueAnimator anim;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);

    ConnectView(Context c) {
        super(c);
        t.setTextAlign(Paint.Align.CENTER);
        t.setFakeBoldText(true);
    }

    void setState(int s) { if (s != state) { state = s; invalidate(); } }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(2600);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) { phase = (Float) a.getAnimatedValue(); invalidate(); }
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float R = Math.min(cx, cy) * 0.52f;
        int col = state == TunnelService.ON ? Ui.TEAL : state == TunnelService.CONNECTING ? Ui.AMBER
                : state == TunnelService.ERROR ? Ui.RED : 0xFF5C6B8F;

        // expanding rings
        if (state == TunnelService.ON || state == TunnelService.CONNECTING) {
            for (int i = 0; i < 3; i++) {
                float f = (phase + i / 3f) % 1f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3f);
                p.setColor((col & 0x00FFFFFF) | ((int) ((1f - f) * 90) << 24));
                c.drawCircle(cx, cy, R * (1.1f + f * 0.75f), p);
            }
        }
        // rays (rotate while connecting, slow drift otherwise)
        float rot = (state == TunnelService.CONNECTING ? phase * 360f : phase * 40f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(R * 0.07f);
        p.setColor(col);
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(rot + i * 22.5);
            float r1 = R * 1.06f, r2 = R * (i % 2 == 0 ? 1.34f : 1.2f);
            c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                    cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
        }
        // disc
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - R * 0.3f, cy - R * 0.3f, R * 1.4f,
                new int[]{col, 0xFF0B1630}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, R, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(R * 0.05f);
        p.setColor(col);
        c.drawCircle(cx, cy, R, p);

        t.setColor(0xFF04120D);
        t.setTextSize(R * 0.42f);
        String s = state == TunnelService.ON ? "قطع" : state == TunnelService.CONNECTING ? "…" : "اتصال";
        c.drawText(s, cx, cy + R * 0.15f, t);
    }
}
