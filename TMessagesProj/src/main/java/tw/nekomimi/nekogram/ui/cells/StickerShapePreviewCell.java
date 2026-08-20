package tw.nekomimi.nekogram.ui.cells;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.StickerShapeHelper;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Easings;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import xyz.nextalone.nagram.NaConfig;

public class StickerShapePreviewCell extends LinearLayout {

    private static final int SHAPE_COUNT = 3;

    private final StickerShapeView[] shapeViews = new StickerShapeView[SHAPE_COUNT];
    private final Runnable onChanged;

    public StickerShapePreviewCell(Context context, Runnable onChanged) {
        super(context);
        this.onChanged = onChanged;
        setWillNotDraw(false);
        setOrientation(HORIZONTAL);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(10), AndroidUtilities.dp(13), 0);

        for (int i = 0; i < SHAPE_COUNT; i++) {
            final int shape = i;
            shapeViews[i] = new StickerShapeView(context, shape);
            ScaleStateListAnimator.apply(shapeViews[i], 0.03f, 1.5f);
            addView(shapeViews[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0.5f, 8, 0, 8, 0));
            shapeViews[i].setOnClickListener(v -> {
                if (NaConfig.INSTANCE.getStickerShape().Int() == shape) {
                    return;
                }
                for (StickerShapeView shapeView : shapeViews) {
                    shapeView.setSelected(v == shapeView, true);
                }
                NaConfig.INSTANCE.getStickerShape().setConfigInt(shape);
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        for (StickerShapeView shapeView : shapeViews) {
            if (shapeView != null) {
                shapeView.invalidate();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(130), MeasureSpec.EXACTLY)
        );
    }

    private static class PreviewBackgroundDrawable extends Drawable {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint;
        private final RectF rectF = new RectF();
        private final float radius;
        private float selectionProgress;

        public PreviewBackgroundDrawable(float radiusDp) {
            this.radius = AndroidUtilities.dp(radiusDp);
            strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
        }

        public void setSelectionProgress(float progress) {
            if (this.selectionProgress == progress) return;
            this.selectionProgress = progress;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            backgroundPaint.setColor(getBackgroundColor());
            strokePaint.setColor(ColorUtils.blendARGB(getOutlineColor(),
                    Theme.getColor(Theme.key_windowBackgroundWhiteValueText), selectionProgress));
            strokePaint.setStrokeWidth(AndroidUtilities.dp(AndroidUtilities.lerp(0.5f, 2.0f, selectionProgress)));
            float halfStroke = strokePaint.getStrokeWidth() / 2.0f;
            rectF.set(getBounds().left + halfStroke, getBounds().top + halfStroke,
                    getBounds().right - halfStroke, getBounds().bottom - halfStroke);
            canvas.drawRoundRect(rectF, radius, radius, backgroundPaint);
            canvas.drawRoundRect(rectF, radius, radius, strokePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            backgroundPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            backgroundPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        private static int getBackgroundColor() {
            return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
                    Theme.isCurrentThemeDark() ? 0.05f : 0.035f);
        }

        private static int getOutlineColor() {
            return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
                    (Theme.isCurrentThemeDark() ? 0.05f : 0.035f) + 0.085f);
        }
    }

    private static class StickerShapeView extends FrameLayout {
        private final PreviewBackgroundDrawable backgroundDrawable = new PreviewBackgroundDrawable(10.0f);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path bubblePath = new Path();
        private final int shape;
        private float progress;

        public StickerShapeView(Context context, int shape) {
            super(context);
            this.shape = shape;
            setWillNotDraw(false);
            textPaint.setTextSize(AndroidUtilities.dp(13));
            setSelected(NaConfig.INSTANCE.getStickerShape().Int() == shape, false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            backgroundDrawable.setBounds(0, 0, getMeasuredWidth(), AndroidUtilities.dp(80));
            backgroundDrawable.draw(canvas);

            String title = LocaleController.getString(getTitleResId());
            float titleX = (getMeasuredWidth() - (int) Math.ceil(textPaint.measureText(title))) >> 1;
            canvas.drawText(title, titleX, AndroidUtilities.dp(102), textPaint);

            rect.set(AndroidUtilities.dp(10), AndroidUtilities.dp(10),
                    getMeasuredWidth() - AndroidUtilities.dp(10), AndroidUtilities.dp(70));
            Theme.dialogs_onlineCirclePaint.setColor(getMockColor());

            if (shape == StickerShapeHelper.SHAPE_ROUNDED_AS_MESSAGE) {
                float rad = AndroidUtilities.dp(SharedConfig.bubbleRadius);
                canvas.drawPath(roundedAsMessagePath(rad, rad / 3.0f), Theme.dialogs_onlineCirclePaint);
            } else {
                float rad = shape == StickerShapeHelper.SHAPE_ROUNDED
                        ? AndroidUtilities.dp(StickerShapeHelper.ROUNDED_RADIUS_DP)
                        : 0;
                canvas.drawRoundRect(rect, rad, rad, Theme.dialogs_onlineCirclePaint);
            }
        }

        // 右下角（尾巴一侧）小圆角
        private Path roundedAsMessagePath(float rad, float nearRad) {
            bubblePath.rewind();
            bubblePath.addRoundRect(rect, new float[]{rad, rad, rad, rad, nearRad, nearRad, rad, rad},
                    Path.Direction.CW);
            return bubblePath;
        }

        private int getTitleResId() {
            if (shape == StickerShapeHelper.SHAPE_ROUNDED) {
                return R.string.StickerShapeRounded;
            }
            if (shape == StickerShapeHelper.SHAPE_ROUNDED_AS_MESSAGE) {
                return R.string.StickerShapeRoundedMsg;
            }
            return R.string.Default;
        }

        private void setProgress(float f) {
            this.progress = f;
            textPaint.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
                    Theme.getColor(Theme.key_windowBackgroundWhiteValueText), f));
            textPaint.setTypeface(f >= 0.5f ? AndroidUtilities.bold() : null);
            backgroundDrawable.setSelectionProgress(f);
            invalidate();
        }

        public void setSelected(boolean selected, boolean animate) {
            float target = selected ? 1.0f : 0.0f;
            if (target == progress && animate) return;
            if (animate) {
                ValueAnimator anim = ValueAnimator.ofFloat(progress, target).setDuration(250);
                anim.setInterpolator(Easings.easeInOutQuad);
                anim.addUpdateListener(va -> setProgress((float) va.getAnimatedValue()));
                anim.start();
            } else {
                setProgress(target);
            }
        }

        private static int getMockColor() {
            return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 0.4f);
        }
    }
}
