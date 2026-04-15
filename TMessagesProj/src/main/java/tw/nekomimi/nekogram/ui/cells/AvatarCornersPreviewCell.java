package tw.nekomimi.nekogram.ui.cells;

import android.content.Context;
import android.os.Build;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AvatarCornerHelper;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import xyz.nextalone.nagram.NaConfig;

public class AvatarCornersPreviewCell extends FrameLayout {

    private final RectF rect = new RectF();
    private final Path onlineCutoutPath = new Path();
    private final Paint previewBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint previewStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AltSeekbar seekBar;
    private final FrameLayout preview;
    private float committedAvatarCorners;
    private float previewAvatarCorners;

    public AvatarCornersPreviewCell(Context context, Runnable onValueChanged) {
        super(context);

        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        committedAvatarCorners = NaConfig.INSTANCE.getAvatarCorners().Float();
        previewAvatarCorners = committedAvatarCorners;

        seekBar = new AltSeekbar(
                context,
                (value, stop) -> {
                    previewAvatarCorners = value;
                    invalidate();
                    boolean valueChanged = Float.compare(committedAvatarCorners, value) != 0;
                    if (stop && valueChanged) {
                        committedAvatarCorners = value;
                        NaConfig.INSTANCE.getAvatarCorners().setConfigFloat(value);
                        if (onValueChanged != null) {
                            onValueChanged.run();
                        }
                    }
                },
                0,
                28,
                LocaleController.getString(R.string.AvatarCorners),
                LocaleController.getString(R.string.AvatarCornersLeft),
                LocaleController.getString(R.string.AvatarCornersRight)
        );
        seekBar.setProgress(previewAvatarCorners / 28.0f);
        addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_switchTrack), 63));
        outlinePaint.setStrokeWidth(Math.max(2, AndroidUtilities.dp(1.0f)));

        previewStrokePaint.setStyle(Paint.Style.STROKE);

        preview = new FrameLayout(context) {
            @SuppressWarnings("deprecation")
            @Override
            protected void onDraw(Canvas canvas) {
                int color = Theme.getColor(Theme.key_switchTrack);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                float width = getMeasuredWidth();
                float height = getMeasuredHeight();
                float centerY = height / 2.0f;
                float round = AndroidUtilities.dp(10.0f);
                float previewRound = AndroidUtilities.dp(12.0f);
                float rectRound = width / 2.0f;
                float previewAlpha = Theme.isCurrentThemeDark() ? 0.05f : 0.035f;
                float previewStroke = AndroidUtilities.dpf2(0.5f);

                previewBackgroundPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), previewAlpha));
                previewStrokePaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), previewAlpha + 0.085f));
                previewStrokePaint.setStrokeWidth(previewStroke);

                rect.set(0.0f, 0.0f, width, height);
                canvas.drawRoundRect(rect, previewRound, previewRound, previewBackgroundPaint);
                rect.set(
                        previewStroke / 2.0f,
                        previewStroke / 2.0f,
                        width - previewStroke / 2.0f,
                        height - previewStroke / 2.0f
                );
                canvas.drawRoundRect(rect, previewRound, previewRound, previewStrokePaint);

                rect.set(0.0f, 0.0f, width, height);
                Theme.dialogs_onlineCirclePaint.setColor(Color.argb(20, red, green, blue));
                canvas.drawRoundRect(rect, round, round, Theme.dialogs_onlineCirclePaint);

                float strokeWidth = outlinePaint.getStrokeWidth() / 2.0f;
                rect.set(strokeWidth, strokeWidth, width - strokeWidth, height - strokeWidth);
                canvas.drawRoundRect(rect, round, round, outlinePaint);

                float onlineCenterX = AndroidUtilities.dp(68.0f);
                float onlineCenterY = AndroidUtilities.dpf2(20.5f) + centerY;

                Theme.dialogs_onlineCirclePaint.setColor(Theme.getColor(Theme.key_chats_onlineCircle));
                canvas.drawCircle(onlineCenterX, onlineCenterY, AndroidUtilities.dp(7.0f), Theme.dialogs_onlineCirclePaint);

                Theme.dialogs_onlineCirclePaint.setColor(Color.argb(204, red, green, blue));
                canvas.drawRoundRect(
                        AndroidUtilities.dp(92.0f),
                        centerY - AndroidUtilities.dpf2(15.5f),
                        width - AndroidUtilities.dp(90.0f),
                        centerY - AndroidUtilities.dpf2(7.5f),
                        rectRound,
                        rectRound,
                        Theme.dialogs_onlineCirclePaint
                );

                onlineCutoutPath.reset();
                onlineCutoutPath.addCircle(onlineCenterX, onlineCenterY, AndroidUtilities.dp(12.0f), Path.Direction.CCW);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas.clipOutPath(onlineCutoutPath);
                } else {
                    canvas.clipPath(onlineCutoutPath, Region.Op.DIFFERENCE);
                }
                Theme.dialogs_onlineCirclePaint.setColor(Color.argb(90, red, green, blue));
                canvas.drawRoundRect(
                        AndroidUtilities.dp(92.0f),
                        centerY + AndroidUtilities.dpf2(7.5f),
                        width - AndroidUtilities.dp(50.0f),
                        centerY + AndroidUtilities.dp(15.5f),
                        rectRound,
                        rectRound,
                        Theme.dialogs_onlineCirclePaint
                );

                canvas.drawRoundRect(
                        AndroidUtilities.dp(20.0f),
                        centerY - AndroidUtilities.dp(28.0f),
                        AndroidUtilities.dp(76.0f),
                        centerY + AndroidUtilities.dp(28.0f),
                        AvatarCornerHelper.getAvatarRoundRadius(56.0f, previewAvatarCorners, false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool()),
                        AvatarCornerHelper.getAvatarRoundRadius(56.0f, previewAvatarCorners, false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool()),
                        Theme.dialogs_onlineCirclePaint
                );
            }
        };
        preview.setWillNotDraw(false);
        addView(preview, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP,
                21,
                112,
                21,
                21
        ));
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (preview != null) {
            preview.invalidate();
        }
        if (seekBar != null) {
            seekBar.invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(
                LocaleController.isRTL ? 0.0f : AndroidUtilities.dp(21.0f),
                getMeasuredHeight() - 1,
                getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(21.0f) : 0),
                getMeasuredHeight() - 1,
                Theme.dividerPaint
        );
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(222.0f), View.MeasureSpec.EXACTLY)
        );
    }
}
