package xyz.nextalone.nagram.nowplaying;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

public final class NowPlayingUIUtil {

    public static final NowPlayingUIUtil INSTANCE = new NowPlayingUIUtil();

    private static final float[] NOW_PLAYING_PATTERN = {
        -5.5f, 20.0f, 20.0f, 0.35f,
        -5.5f, -20.0f, 20.0f, 0.35f,
        -36.0f, -42.0f, 22.0f, 0.375f,
        -36.0f, 0.0f, 25.0f, 0.425f,
        -36.0f, 42.0f, 22.0f, 0.375f,
        -70.0f, 22.0f, 23.0f, 0.35f,
        -70.0f, -22.0f, 23.0f, 0.35f,
        -99.0f, 46.0f, 21.0f, 0.275f,
        -99.0f, 0.0f, 22.0f, 0.325f,
        -99.0f, -46.0f, 21.0f, 0.275f,
        -128.0f, -23.0f, 20.0f, 0.225f,
        -128.0f, 23.0f, 20.0f, 0.225f
    };

    private NowPlayingUIUtil() {}

    public int adjustHsl(int color, float lightnessMultiplier, float saturationMultiplier) {
        return adjustHsl(color, lightnessMultiplier, saturationMultiplier, -1.0f);
    }

    public int adjustHsl(int color, float lightnessMultiplier) {
        return adjustHsl(color, lightnessMultiplier, -1.0f);
    }

    private int adjustHsl(int color, float lightnessMultiplier, float saturationMultiplier, float defaultSat) {
        float sat = saturationMultiplier > 0.0f ? saturationMultiplier : defaultSat;
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        if (sat > 0.0f) {
            hsl[1] = Math.min(hsl[1] * sat, 1.0f);
        }
        hsl[2] = Math.min(hsl[2] * lightnessMultiplier, 1.0f);
        return ColorUtils.HSLToColor(hsl);
    }

    public void drawNowPlayingPattern(Canvas canvas, Drawable pattern, float width, float height, float alpha) {
        if (alpha <= 0.0f) {
            return;
        }
        float half = height / 2.0f;
        for (int i = 0; i < NOW_PLAYING_PATTERN.length; i += 4) {
            float x = NOW_PLAYING_PATTERN[i];
            float y = NOW_PLAYING_PATTERN[i + 1];
            float size = NOW_PLAYING_PATTERN[i + 2];
            float a = NOW_PLAYING_PATTERN[i + 3];
            int left = (int) ((AndroidUtilities.dpf2(x) + width) - (AndroidUtilities.dpf2(size) / 2.0f));
            int top = (int) ((AndroidUtilities.dpf2(y) + half) - (AndroidUtilities.dpf2(size) / 2.0f));
            int right = (int) (AndroidUtilities.dpf2(x) + width + (AndroidUtilities.dpf2(size) / 2.0f));
            int bottom = (int) (half + AndroidUtilities.dpf2(y) + (AndroidUtilities.dpf2(size) / 2.0f));
            pattern.setBounds(left, top, right, bottom);
            pattern.setAlpha((int) (255 * alpha * a));
            pattern.draw(canvas);
        }
    }
}
