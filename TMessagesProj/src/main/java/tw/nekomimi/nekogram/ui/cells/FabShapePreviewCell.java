package tw.nekomimi.nekogram.ui.cells;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AvatarCornerHelper;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Easings;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import xyz.nextalone.nagram.NaConfig;

public class FabShapePreviewCell extends LinearLayout {

    private final FabShapeView[] fabShapeViews = new FabShapeView[2];
    private Runnable onChanged;

    public FabShapePreviewCell(Context context, Runnable onChanged) {
        super(context);
        this.onChanged = onChanged;
        setWillNotDraw(false);
        setOrientation(HORIZONTAL);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(21));

        for (int i = 0; i < 2; i++) {
            boolean isSquare = i == 1;
            fabShapeViews[i] = new FabShapeView(context, isSquare);
            ScaleStateListAnimator.apply(fabShapeViews[i], 0.03f, 1.5f);
            addView(fabShapeViews[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, 0.5f, 8, 0, 8, 0));
            final int idx = i;
            fabShapeViews[i].setOnClickListener(v -> {
                for (int j = 0; j < 2; j++) {
                    fabShapeViews[j].setSelected(v == fabShapeViews[j], true);
                }
                NaConfig.INSTANCE.getSquareFloatingButton().setConfigBool(isSquare);
                FragmentFloatingButton.notifyShapeChanged();
                if (onChanged != null) {
                    onChanged.run();
                }
            });
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        for (int i = 0; i < 2; i++) {
            fabShapeViews[i].invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(110), MeasureSpec.EXACTLY)
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
            return -3;
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

    private static class FabShapeView extends FrameLayout {
        private final PreviewBackgroundDrawable backgroundDrawable;
        private final RectF rect = new RectF();
        private final boolean squareFab;
        private float progress;

        public FabShapeView(Context context, boolean squareFab) {
            super(context);
            this.squareFab = squareFab;
            this.backgroundDrawable = new PreviewBackgroundDrawable(12.0f);
            setWillNotDraw(false);
            setBackground(backgroundDrawable);
            setSelected((squareFab && NaConfig.INSTANCE.getSquareFloatingButton().Bool())
                    || (!squareFab && !NaConfig.INSTANCE.getSquareFloatingButton().Bool()), false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int avatarSize = AndroidUtilities.dp(22);
            int avatarRadius = avatarSize / 2;
            int startY = AndroidUtilities.dp(21);

            for (int i = 0; i < 2; i++) {
                int y = startY + AndroidUtilities.dp(i == 0 ? 0 : 32);

                Theme.dialogs_onlineCirclePaint.setColor(getMockColor(false));
                float avatarDiameter = avatarRadius * 2;
                int cornerRadius = AvatarCornerHelper.getAvatarRoundRadius(avatarDiameter / AndroidUtilities.density);
                canvas.drawRoundRect(
                        avatarSize - avatarRadius, y - avatarRadius,
                        avatarSize + avatarRadius, y + avatarRadius,
                        cornerRadius, cornerRadius,
                        Theme.dialogs_onlineCirclePaint
                );

                for (int j = 0; j < 2; j++) {
                    Theme.dialogs_onlineCirclePaint.setColor(getMockColor(j == 0));
                    int lineY = j * 10;
                    rect.set(
                            AndroidUtilities.dp(41),
                            y - AndroidUtilities.dp(7 - lineY),
                            getMeasuredWidth() - AndroidUtilities.dp(j == 0 ? 70 : 55),
                            y - AndroidUtilities.dp(3 - lineY)
                    );
                    canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2),
                            Theme.dialogs_onlineCirclePaint);
                }
            }

            Theme.dialogs_onlineCirclePaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            rect.set(
                    getMeasuredWidth() - AndroidUtilities.dp(42),
                    getMeasuredHeight() - AndroidUtilities.dp(12),
                    getMeasuredWidth() - AndroidUtilities.dp(12),
                    getMeasuredHeight() - AndroidUtilities.dp(42)
            );
            float fabRadius = squareFab ? AndroidUtilities.dp(9) : AndroidUtilities.dp(100);
            canvas.drawRoundRect(rect, fabRadius, fabRadius, Theme.dialogs_onlineCirclePaint);

            Drawable drawable = ContextCompat.getDrawable(getContext(), R.drawable.filled_fab_compose_32);
            if (drawable != null) {
                drawable.setColorFilter(new PorterDuffColorFilter(
                        Theme.getColor(Theme.key_chats_actionIcon), PorterDuff.Mode.SRC_IN));
                drawable.setBounds(
                        getMeasuredWidth() - AndroidUtilities.dp(37),
                        getMeasuredHeight() - AndroidUtilities.dp(37),
                        getMeasuredWidth() - AndroidUtilities.dp(17),
                        getMeasuredHeight() - AndroidUtilities.dp(17)
                );
                drawable.draw(canvas);
            }
        }

        private void setProgress(float f) {
            this.progress = f;
            backgroundDrawable.setSelectionProgress(f);
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

        private static int getMockColor(boolean strong) {
            return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2),
                    strong ? 0.4f : 0.2f);
        }
    }
}
