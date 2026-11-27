package com.abolix.gamebooster;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;

/** Lightweight traffic sparkline. Values are measured bytes/second, not estimates. */
public final class AbolixGraphView extends View {
    private final Deque<Float> download = new ArrayDeque<>();
    private final Deque<Float> upload = new ArrayDeque<>();
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint downPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint upPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint downFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint upFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int accent = 0xff55e6c1;
    private int secondary = 0xff9b8cff;

    public AbolixGraphView(Context context) { super(context); init(); }
    public AbolixGraphView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        grid.setColor(0x24ffffff); grid.setStrokeWidth(dp(1)); grid.setStyle(Paint.Style.STROKE);
        downPaint.setColor(accent); downPaint.setStrokeWidth(dp(2.4f)); downPaint.setStyle(Paint.Style.STROKE); downPaint.setStrokeCap(Paint.Cap.ROUND); downPaint.setStrokeJoin(Paint.Join.ROUND);
        upPaint.setColor(secondary); upPaint.setStrokeWidth(dp(2.0f)); upPaint.setStyle(Paint.Style.STROKE); upPaint.setStrokeCap(Paint.Cap.ROUND); upPaint.setStrokeJoin(Paint.Join.ROUND);
        downFill.setColor(0x2255e6c1); downFill.setStyle(Paint.Style.FILL);
        upFill.setColor(0x1d9b8cff); upFill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 36; i++) { download.addLast(0f); upload.addLast(0f); }
    }

    public void setAccent(int color) {
        accent = color;
        downPaint.setColor(color);
        downFill.setColor((color & 0x00ffffff) | 0x22000000);
        invalidate();
    }

    public void addSample(float downloadBytesPerSecond, float uploadBytesPerSecond) {
        if (download.size() >= 48) download.removeFirst();
        if (upload.size() >= 48) upload.removeFirst();
        download.addLast(Math.max(0, downloadBytesPerSecond));
        upload.addLast(Math.max(0, uploadBytesPerSecond));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(4), right = getWidth() - dp(4), top = dp(8), bottom = getHeight() - dp(8);
        if (right <= left || bottom <= top) return;
        for (int i = 1; i < 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, grid);
        }
        float max = 1024f;
        for (Float value : download) max = Math.max(max, value);
        for (Float value : upload) max = Math.max(max, value);
        max *= 1.18f;
        drawSeries(canvas, download, max, left, right, top, bottom, downPaint, downFill);
        drawSeries(canvas, upload, max, left, right, top, bottom, upPaint, upFill);
    }

    private void drawSeries(Canvas canvas, Deque<Float> series, float max, float left, float right,
                            float top, float bottom, Paint line, Paint fill) {
        if (series.size() < 2) return;
        Path path = new Path();
        Path area = new Path();
        int index = 0;
        float step = (right - left) / Math.max(1, series.size() - 1);
        for (Float value : series) {
            float x = left + index * step;
            float y = bottom - (Math.min(max, value) / max) * (bottom - top);
            if (index == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y); }
            else { path.lineTo(x, y); area.lineTo(x, y); }
            index++;
        }
        area.lineTo(right, bottom); area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
