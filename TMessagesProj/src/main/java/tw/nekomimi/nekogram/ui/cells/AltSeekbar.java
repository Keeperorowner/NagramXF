package tw.nekomimi.nekogram.ui.cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

public class AltSeekbar extends FrameLayout {

    public interface OnDrag {
        void run(float value, boolean stop);
    }

    private final int min;
    private final int max;
    private final AnimatedTextView headerValue;
    private final TextView leftTextView;
    private final TextView rightTextView;

    public final SeekBarView seekBarView;

    private float currentValue;
    private int roundedValue;
    private int vibro = -1;

    public AltSeekbar(Context context, OnDrag onDrag, int min, int max, String header, String leftText, String rightText) {
        super(context);
        this.min = min;
        this.max = max;

        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setGravity(Gravity.LEFT);

        TextView headerTextView = new TextView(context);
        headerTextView.setTextSize(1, 15.0f);
        headerTextView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        headerTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        headerTextView.setGravity(Gravity.LEFT);
        headerTextView.setText(header);
        headerLayout.addView(headerTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        headerValue = new AnimatedTextView(context, false, true, true) {
            private final Drawable backgroundDrawable = Theme.createRoundRectDrawable(AndroidUtilities.dp(4.0f), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader), 0.15f));

            @Override
            protected void onDraw(Canvas canvas) {
                backgroundDrawable.setBounds(0, 0, (int) (getPaddingLeft() + getDrawable().getCurrentWidth() + getPaddingRight()), getMeasuredHeight());
                backgroundDrawable.draw(canvas);
                super.onDraw(canvas);
            }
        };
        headerValue.setAnimationProperties(0.45f, 0L, 240L, CubicBezierInterpolator.EASE_OUT_QUINT);
        headerValue.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        headerValue.setPadding(AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2.0f), AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2.0f));
        headerValue.setTextSize(AndroidUtilities.dp(12.0f));
        headerValue.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        headerLayout.addView(headerValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));

        addView(headerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 17, 21, 0));

        seekBarView = new SeekBarView(context, true, null);
        seekBarView.setReportChanges(true);
        seekBarView.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                float value = min + ((max - min) * progress);
                onDrag.run(value, stop);
                if (Math.round(value) != roundedValue) {
                    setProgress(progress);
                } else {
                    currentValue = value;
                    seekBarView.setProgress(progress);
                }
            }
        });
        addView(seekBarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT | Gravity.BOTTOM, 6, 68, 6, 0));

        FrameLayout labelsLayout = new FrameLayout(context);

        leftTextView = new TextView(context);
        leftTextView.setTextSize(1, 13.0f);
        leftTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        leftTextView.setGravity(Gravity.LEFT);
        leftTextView.setText(leftText);
        labelsLayout.addView(leftTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));

        rightTextView = new TextView(context);
        rightTextView.setTextSize(1, 13.0f);
        rightTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        rightTextView.setGravity(Gravity.RIGHT);
        rightTextView.setText(rightText);
        labelsLayout.addView(rightTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP));

        addView(labelsLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 21, 52, 21, 0));
    }

    public void setProgress(float progress) {
        float value = min + ((max - min) * progress);
        currentValue = value;
        roundedValue = Math.round(value);
        seekBarView.setProgress(progress);
        headerValue.cancelAnimation();
        headerValue.setText(getTextForHeader(), true);
        if ((roundedValue == min || roundedValue == max) && roundedValue != vibro) {
            vibro = roundedValue;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } else if (roundedValue > min && roundedValue < max) {
            vibro = -1;
        }
        updateValues();
    }

    private void updateValues() {
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
        float center = ((max - min) / 2.0f) + min;
        float leftThreshold = (center + min) * 0.5f;
        float rightThreshold = (center + max) * 0.5f;
        if (currentValue >= rightThreshold) {
            float progress = clamp01((currentValue - rightThreshold) / (max - rightThreshold));
            rightTextView.setTextColor(ColorUtils.blendARGB(gray, blue, progress));
            leftTextView.setTextColor(gray);
        } else if (currentValue <= leftThreshold) {
            float progress = clamp01((leftThreshold - currentValue) / (leftThreshold - min));
            leftTextView.setTextColor(ColorUtils.blendARGB(gray, blue, progress));
            rightTextView.setTextColor(gray);
        } else {
            leftTextView.setTextColor(gray);
            rightTextView.setTextColor(gray);
        }
    }

    public CharSequence getTextForHeader() {
        CharSequence text;
        if (roundedValue == min) {
            text = leftTextView.getText();
        } else if (roundedValue == max) {
            text = rightTextView.getText();
        } else {
            text = String.valueOf(roundedValue);
        }
        return text.toString().toUpperCase();
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(112.0f), View.MeasureSpec.EXACTLY)
        );
    }
}
