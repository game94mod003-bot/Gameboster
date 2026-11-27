package com.abolix.vpn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Live download (teal, filled) / upload (blue) line chart. */
final class ChartView extends View {
    private float[] d = new float[TunnelService.HIST], u = new float[TunnelService.HIST];
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            grid = new Paint(), txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), area = new Path();

    ChartView(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(Ui.dp(c, 2));
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(1f);
        txt.setColor(Ui.DIM);
        txt.setTextSize(Ui.dp(c, 10));
    }

    void set(float[] down, float[] up) { d = down; u = up; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float max = 1024;
        for (int i = 0; i < d.length; i++) { if (d[i] > max) max = d[i]; if (u[i] > max) max = u[i]; }
        max *= 1.15f;
        for (int i = 1; i < 4; i++) c.drawLine(0, h * i / 4f, w, h * i / 4f, grid);
        c.drawText(Ui.speed((long) max), 4, txt.getTextSize() + 2, txt);

        draw(c, d, max, w, h, Ui.TEAL, true);
        draw(c, u, max, w, h, Ui.BLUE, false);
    }

    private void draw(Canvas c, float[] s, float max, int w, int h, int color, boolean filled) {
        int n = s.length;
        path.reset();
        area.reset();
        for (int i = 0; i < n; i++) {
            float x = w * i / (float) (n - 1);
            float y = h - 4 - (h - 8) * (s[i] / max);
            if (i == 0) { path.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); }
            else { path.lineTo(x, y); area.lineTo(x, y); }
        }
        if (filled) {
            area.lineTo(w, h);
            area.close();
            fill.setShader(new LinearGradient(0, 0, 0, h, (color & 0x00FFFFFF) | 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawPath(area, fill);
        }
        line.setColor(color);
        c.drawPath(path, line);
    }
}
