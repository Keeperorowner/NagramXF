package tw.nekomimi.nekogram.ui.cells;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Easings;
import org.telegram.ui.Components.LayoutHelper;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.helper.DoubleTap;

public class DoubleTapPreviewCell extends LinearLayout {

    private static final int[] ICON_WIDTH = {AndroidUtilities.dp(12), AndroidUtilities.dp(12)};
    private final int[] actionIcon = new int[2];
    private final ValueAnimator[] animator = new ValueAnimator[2];
    private final ValueAnimator[] circleAnimator = new ValueAnimator[2];
    private final Paint[] circleOutlinePaint = new Paint[2];
    private final float[] circleProgress = new float[4];
    private final ValueAnimator[] circleSizeAnimator = new ValueAnimator[2];
    private final float[] circleSizeProgress = new float[4];
    private final float[] iconChangingProgress = new float[2];
    private final MessageDrawable[] messages = new MessageDrawable[]{
            new MessageDrawable(0, false, false),
            new MessageDrawable(0, true, false)
    };
    private final Paint outlinePaint;
    private final FrameLayout preview;
    private final RectF rect = new RectF();

    public DoubleTapPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setPadding(AndroidUtilities.dp(13), 0, AndroidUtilities.dp(13), AndroidUtilities.dp(10));

        outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(AndroidUtilities.dp(1) / 2.0f);
        outlinePaint.setColor(getOutlineColor());

        preview = new FrameLayout(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                Rect bounds = new Rect();
                float halfStroke = outlinePaint.getStrokeWidth() / 2.0f;

                for (int i = 0; i < 2; i++) {
                    if (i == 0) {
                        rect.set(
                                AndroidUtilities.dp(8) + halfStroke,
                                AndroidUtilities.dp(10) + halfStroke,
                                (getMeasuredWidth() / 2.0f) - AndroidUtilities.dp(8) - halfStroke,
                                AndroidUtilities.dp(75) - halfStroke
                        );
                    } else {
                        canvas.translate(0, AndroidUtilities.dp(80));
                        rect.set(
                                (getMeasuredWidth() / 2.0f) + halfStroke + AndroidUtilities.dp(8),
                                AndroidUtilities.dp(5) + halfStroke,
                                getMeasuredWidth() - AndroidUtilities.dp(8) - halfStroke,
                                AndroidUtilities.dp(70) - halfStroke
                        );
                    }

                    rect.round(bounds);
                    messages[i].setBounds(bounds);

                    Theme.dialogs_onlineCirclePaint.setColor(getBackgroundColor());
                    messages[i].draw(canvas, Theme.dialogs_onlineCirclePaint);
                    messages[i].draw(canvas, outlinePaint);

                    for (int j = 0; j < 2; j++) {
                        circleOutlinePaint[j] = new Paint(Paint.ANTI_ALIAS_FLAG);
                        circleOutlinePaint[j].setStyle(Paint.Style.STROKE);
                        int idx = i + (j * 2);
                        circleOutlinePaint[j].setColor(ColorUtils.blendARGB(0, getMockColor(true), circleProgress[idx]));
                        circleOutlinePaint[j].setStrokeWidth(AndroidUtilities.dp(1.5f) * circleProgress[idx] * circleProgress[idx]);

                        float cx = ((i == 0 ? 1 : 3) * getMeasuredWidth()) / 4.0f;
                        float cy = getMeasuredHeight() / 4.0f;
                        float offsetY = i == 0 ? 3.0f : -2.0f;

                        canvas.drawCircle(cx, cy + AndroidUtilities.dpf2(offsetY),
                                AndroidUtilities.dp(25 - (j * 6)) * circleSizeProgress[idx],
                                circleOutlinePaint[j]);
                    }

                    Integer iconRes = DoubleTap.doubleTapActionIconMap.get(actionIcon[i]);
                    if (iconRes != null) {
                        Drawable drawable = ContextCompat.getDrawable(context, iconRes);
                        if (drawable != null) {
                            if (i == 0) {
                                drawable.setBounds(
                                        (getMeasuredWidth() / 4) - ICON_WIDTH[i],
                                        (int) (((getMeasuredHeight() / 4) - ICON_WIDTH[i]) + AndroidUtilities.dpf2(3)),
                                        (getMeasuredWidth() / 4) + ICON_WIDTH[i],
                                        (int) ((getMeasuredHeight() / 4) + ICON_WIDTH[i] + AndroidUtilities.dpf2(3))
                                );
                            } else {
                                drawable.setBounds(
                                        ((getMeasuredWidth() * 3) / 4) - ICON_WIDTH[i],
                                        (int) (((getMeasuredHeight() / 4) - ICON_WIDTH[i]) - AndroidUtilities.dpf2(2)),
                                        ((getMeasuredWidth() * 3) / 4) + ICON_WIDTH[i],
                                        (int) (((getMeasuredHeight() / 4) + ICON_WIDTH[i]) - AndroidUtilities.dpf2(2))
                                );
                            }

                            float scale = iconChangingProgress[i];
                            int shrink = AndroidUtilities.dp(4 - (scale * 4));
                            drawable.setBounds(
                                    drawable.getBounds().left - shrink,
                                    drawable.getBounds().top - shrink,
                                    drawable.getBounds().right + shrink,
                                    drawable.getBounds().bottom + shrink
                            );

                            drawable.setColorFilter(new PorterDuffColorFilter(
                                    ColorUtils.blendARGB(0, Theme.getColor(Theme.key_chats_menuItemIcon), scale),
                                    PorterDuff.Mode.MULTIPLY
                            ));
                            drawable.draw(canvas);
                        }
                    }
                }
            }
        };

        preview.setWillNotDraw(false);
        addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateIcons(0, false);
    }

    public void updateIcons(int changed, boolean animate) {
        for (int i = 0; i < 2; i++) {
            if ((i == 0 && changed == 2) || (i == 1 && changed == 1)) {
                continue;
            }

            int actionId = i == 0
                    ? NaConfig.INSTANCE.getDoubleTapAction().Int()
                    : NaConfig.INSTANCE.getDoubleTapActionOut().Int();

            if (animate) {
                for (int j = 0; j < 2; j++) {
                    int idx = i + (j * 2);
                    circleSizeAnimator[j] = ValueAnimator.ofFloat(0, 1).setDuration(1300);
                    circleSizeAnimator[j].setStartDelay(60L * j);
                    circleSizeAnimator[j].setInterpolator(Easings.easeInOutQuad);
                    final int fIdx = idx;
                    circleSizeAnimator[j].addUpdateListener(va -> {
                        circleSizeProgress[fIdx] = (float) va.getAnimatedValue();
                        invalidate();
                    });

                    circleAnimator[j] = ValueAnimator.ofFloat(1, 0).setDuration(500);
                    circleAnimator[j].setStartDelay(60L * j);
                    circleAnimator[j].setInterpolator(Easings.easeInOutQuad);
                    final int fIdx2 = idx;
                    final int fJ = j;
                    circleAnimator[j].addUpdateListener(va -> {
                        circleProgress[fIdx2] = (float) va.getAnimatedValue();
                        invalidate();
                    });
                    circleAnimator[j].addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            super.onAnimationEnd(animation);
                            circleAnimator[fJ].setFloatValues(1, 0);
                            circleAnimator[fJ].setDuration(700);
                            circleAnimator[fJ].removeAllListeners();
                            circleAnimator[fJ].start();
                        }
                    });

                    circleSizeAnimator[j].start();
                    circleAnimator[j].start();
                }

                final int fI = i;
                final int fActionId = actionId;
                animator[i] = ValueAnimator.ofFloat(1, 0).setDuration(250);
                animator[i].setInterpolator(Easings.easeInOutQuad);
                animator[i].addUpdateListener(va -> {
                    iconChangingProgress[fI] = (float) va.getAnimatedValue();
                    invalidate();
                });
                animator[i].addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        actionIcon[fI] = fActionId;
                        animator[fI].setFloatValues(0, 1);
                        animator[fI].removeAllListeners();
                        animator[fI].addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation2) {
                                super.onAnimationEnd(animation2);
                                DoubleTapPreviewCell.this.performHapticFeedback(3, 2);
                            }
                        });
                        animator[fI].start();
                    }
                });
                animator[i].start();
            } else {
                circleSizeProgress[i] = 0;
                circleSizeProgress[i + 2] = 0;
                circleProgress[i] = 0;
                circleProgress[i + 2] = 0;
                iconChangingProgress[i] = 1;
                actionIcon[i] = actionId;
                invalidate();
            }
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (preview != null) {
            preview.invalidate();
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
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(170), MeasureSpec.EXACTLY)
        );
    }

    private static int getBackgroundColor() {
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
                Theme.isCurrentThemeDark() ? 0.05f : 0.035f);
    }

    private static int getOutlineColor() {
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText),
                (Theme.isCurrentThemeDark() ? 0.05f : 0.035f) + 0.085f);
    }

    private static int getMockColor(boolean strong) {
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2),
                strong ? 0.4f : 0.2f);
    }
}
