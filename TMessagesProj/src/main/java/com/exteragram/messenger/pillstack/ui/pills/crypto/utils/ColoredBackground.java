package com.exteragram.messenger.pillstack.ui.pills.crypto.utils;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class ColoredBackground extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ColoredBackground() {
        this(0xFF28A2ED, 0xFF1873E1);
    }

    public ColoredBackground(int topColor, int bottomColor) {
        Shader.TileMode tile = Shader.TileMode.CLAMP;
        paint.setShader(new LinearGradient(0f, 0f, 0f, AndroidUtilities.dp(28f),
                new int[]{topColor, bottomColor},
                new float[]{0f, 1f},
                tile));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(AndroidUtilities.dp(1f));
        strokePaint.setShader(new LinearGradient(0f, 0f, 0f, AndroidUtilities.dp(28f),
                new int[]{0x4DFFFFFF, 0x00000000, 0x1BFFFFFF},
                new float[]{0f, 0.5f, 1f},
                tile));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float radius = AndroidUtilities.dp(14f);
        RectF rect = AndroidUtilities.rectTmp;
        rect.set(bounds);
        canvas.drawRoundRect(rect, radius, radius, paint);
        if (!Theme.isCurrentThemeDark()) {
            return;
        }
        float strokeWidth = AndroidUtilities.dp(1f);
        strokePaint.setStrokeWidth(strokeWidth);
        float inset = strokeWidth / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, radius, radius, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
