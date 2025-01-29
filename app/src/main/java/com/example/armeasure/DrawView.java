package com.example.armeasure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class DrawView extends View {
    private float startX = -1, startY = -1;
    private float endX = -1, endY = -1;
    private boolean firstPointSelected = false;
    private Paint paint;
    private Paint textPaint;
    private DistanceCallback distanceCallback;
    private boolean useMetric = true; // true for cm, false for inches
    private float pixelsPerUnit = 1.0f; // Conversion factor from pixels to real units
    private String currentDistance = "";

    // 测量状态
    private boolean isCalibrating = true;
    private float baselinePixels = -1; // 基准距离（像素）
    private static final float BASELINE_MM = 20.0f; // 基准距离（毫米，可调整）

    public interface DistanceCallback {
        void onDistanceChanged(float startX, float startY, float endX, float endY, float distance);
    }

    public DrawView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStrokeWidth(5);
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        
        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48);
        textPaint.setAntiAlias(true);
    }

    public void setDistanceCallback(DistanceCallback callback) {
        this.distanceCallback = callback;
    }

    public void handleTouch(float x, float y) {
        if (!firstPointSelected) {
            startX = x;
            startY = y;
            firstPointSelected = true;
        } else {
            endX = x;
            endY = y;
            calculateDistance();
        }
        invalidate();
    }

    private void calculateDistance() {
        if (startX < 0 || endX < 0) return;
        
        // Calculate pixel distance
        float dx = endX - startX;
        float dy = endY - startY;
        float pixelDistance = (float)Math.sqrt(dx*dx + dy*dy);
        
        // Convert to real units
        float distance = pixelDistance / pixelsPerUnit;
        
        // Format based on units
        if (useMetric) {
            currentDistance = String.format("%.1f cm", distance);
        } else {
            currentDistance = String.format("%.1f in", distance * 0.393701);
        }
        
        if (distanceCallback != null) {
            distanceCallback.onDistanceChanged(startX, startY, endX, endY, distance);
        }
        invalidate();
    }

    public void reset() {
        startX = -1;
        startY = -1;
        endX = -1;
        endY = -1;
        firstPointSelected = false;
        isCalibrating = true;
        baselinePixels = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        // 绘制点
        paint.setStyle(Paint.Style.FILL);
        if (startX >= 0) {
            paint.setColor(isCalibrating ? Color.BLUE : Color.RED);
            canvas.drawCircle(startX, startY, 10, paint);
        }
        
        if (endX >= 0) {
            canvas.drawCircle(endX, endY, 10, paint);
            // 绘制连线
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(startX, startY, endX, endY, paint);
            
            // 绘制距离文本
            float textX = (startX + endX)/2;
            float textY = Math.min(startY, endY) - 20;
            canvas.drawText(currentDistance, textX, textY, textPaint);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public boolean isFirstPointSelected() {
        return firstPointSelected;
    }

    public float[] getStartPoint() {
        return new float[]{startX, startY};
    }

    public float[] getEndPoint() {
        return new float[]{endX, endY};
    }
}
