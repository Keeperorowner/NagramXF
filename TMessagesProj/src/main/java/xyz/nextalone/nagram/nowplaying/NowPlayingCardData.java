package xyz.nextalone.nagram.nowplaying;

import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.Utilities;

public final class NowPlayingCardData {

    public interface Callback {
        void onDataLoaded(NowPlayingCardData cardData);
    }

    public final NowPlayingDTO nowPlayingDTO;
    public final Integer backgroundColor;
    public final Integer accentColor;
    public final Bitmap coverBitmap;
    public final ImageLocation imageLocation;
    public long userEmoji;

    public NowPlayingCardData(NowPlayingDTO nowPlayingDTO, Integer backgroundColor, Integer accentColor,
                              Bitmap coverBitmap, ImageLocation imageLocation, long userEmoji) {
        this.nowPlayingDTO = nowPlayingDTO;
        this.backgroundColor = backgroundColor;
        this.accentColor = accentColor;
        this.coverBitmap = coverBitmap;
        this.imageLocation = imageLocation;
        this.userEmoji = userEmoji;
    }

    public NowPlayingDTO getNowPlayingDTO() { return nowPlayingDTO; }
    public Integer getBackgroundColor() { return backgroundColor; }
    public Integer getAccentColor() { return accentColor; }
    public Bitmap getCoverBitmap() { return coverBitmap; }
    public ImageLocation getImageLocation() { return imageLocation; }
    public long getUserEmoji() { return userEmoji; }
    public void setUserEmoji(long userEmoji) { this.userEmoji = userEmoji; }

    public static void create(final NowPlayingDTO nowPlayingDTO, final Callback callback) {
        ImageLocation imageLocation = null;
        String coverUrl = nowPlayingDTO.getCoverUrl();
        if (coverUrl != null && coverUrl.length() != 0) {
            imageLocation = ImageLocation.getForPath(coverUrl);
        }
        if (imageLocation == null) {
            AndroidUtilities.runOnUIThread(() ->
                callback.onDataLoaded(new NowPlayingCardData(nowPlayingDTO, null, null, null, null, 0L)));
            return;
        }
        final ImageLocation finalLocation = imageLocation;
        final ImageReceiver imageReceiver = new ImageReceiver(null);
        AndroidUtilities.runOnUIThread(() -> {
            imageReceiver.onAttachedToWindow();
            imageReceiver.setDelegate(new ImageReceiver.ImageReceiverDelegate() {
                @Override
                public void didSetImage(ImageReceiver receiver, boolean set, boolean thumb, boolean thumbCache) {
                    if (!set || thumb) {
                        return;
                    }
                    final Bitmap bitmap = receiver.getBitmap();
                    Utilities.themeQueue.postRunnable(() -> {
                        int[] colors = extractColors(bitmap);
                        final Integer bg = colors[0];
                        final Integer accent = colors[1];
                        AndroidUtilities.runOnUIThread(() -> {
                            callback.onDataLoaded(new NowPlayingCardData(nowPlayingDTO, bg, accent, bitmap, finalLocation, 0L));
                            imageReceiver.onDetachedFromWindow();
                        });
                    });
                }

                @Override
                public void didSetImageBitmap(int pressed, String url, Drawable drawable) {}

                @Override
                public void onAnimationReady(ImageReceiver receiver) {}
            });
            imageReceiver.setImage(finalLocation, null, null, null, null, 0);
        });
    }

    private static int[] extractColors(Bitmap bitmap) {
        if (bitmap == null) {
            return new int[]{0, 0};
        }
        Palette palette = Palette.from(bitmap).generate();
        int bg;
        Palette.Swatch swatch = palette.getDarkVibrantSwatch();
        if (swatch == null) swatch = palette.getMutedSwatch();
        if (swatch == null) swatch = palette.getDarkMutedSwatch();
        if (swatch == null) swatch = palette.getDominantSwatch();
        if (swatch != null) {
            bg = swatch.getRgb();
        } else {
            bg = AndroidUtilities.getDominantColor(bitmap);
        }

        double contrast = ColorUtils.calculateContrast(-1, bg);
        if (contrast > 15.0) {
            bg = NowPlayingUIUtil.INSTANCE.adjustHsl(bg, 2.0f);
        } else if (contrast < 10.0) {
            bg = NowPlayingUIUtil.INSTANCE.adjustHsl(bg, 0.5f);
        }
        if (ColorUtils.calculateContrast(-1, bg) < 3.0) {
            bg = ColorUtils.blendARGB(bg, -16777216, 0.3f);
        }

        float[] hsl = new float[3];
        ColorUtils.colorToHSL(bg, hsl);
        float l = hsl[2];
        float multiplier;
        if (0.0f <= l && l <= 0.25f) {
            multiplier = 2.0f;
        } else if (0.25f <= l && l <= 0.5f) {
            multiplier = 1.5f;
        } else if (0.5f <= l && l <= 0.75f) {
            multiplier = 1.0f;
        } else {
            multiplier = 0.5f;
        }
        int accent = NowPlayingUIUtil.INSTANCE.adjustHsl(bg, multiplier);
        return new int[]{bg, accent};
    }
}
