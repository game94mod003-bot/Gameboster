package com.abolix.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import java.util.Random;

/** Cheap ambient background: gradient + ~18 soft drifting dots at ~20 fps (negligible CPU, no network impact). */
final class FxView extends View {
    private final int N = 18;
    private final float[] x = new float[N], y = new float[N], r = new float[N], vx = new float[N], vy = new float[N];
    private final Paint bg = new Paint(), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long last;

    FxView(Context c) {
        super(c);
        Random rnd = new Random(7);
        for (int i = 0; i < N; i++) {
            x[i] = rnd.nextFloat();
            y[i] = rnd.nextFloat();
            r[i] = 0.01f + rnd.nextFloat() * 0.03f;
            vx[i] = (rnd.nextFloat() - 0.5f) * 0.02f;
            vy[i] = -0.005f - rnd.nextFloat() * 0.02f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        bg.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF070B16, 0xFF0B1630, 0xFF081A22}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        long now = System.nanoTime();
        float dt = last == 0 ? 0.05f : Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        for (int i = 0; i < N; i++) {
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            if (y[i] < -0.05f) { y[i] = 1.05f; x[i] = (float) Math.random(); }
            if (x[i] < -0.05f) x[i] = 1.05f;
            if (x[i] > 1.05f) x[i] = -0.05f;
            dot.setColor(i % 3 == 0 ? 0x2200E5A0 : i % 3 == 1 ? 0x2200B7FF : 0x22FFB300);
            c.drawCircle(x[i] * w, y[i] * h, r[i] * w, dot);
        }
        postInvalidateDelayed(50);
    }
}
