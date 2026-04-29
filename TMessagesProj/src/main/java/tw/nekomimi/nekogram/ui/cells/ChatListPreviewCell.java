package tw.nekomimi.nekogram.ui.cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.TypefaceHelper;
import xyz.nextalone.nagram.NaConfig;

public class ChatListPreviewCell extends FrameLayout {

    private final ActionBar actionBar;

    public ChatListPreviewCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        actionBar = new ActionBar(context) {
            @Override
            public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                return false;
            }
        };
        actionBar.setItemsColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setOccupyStatusBar(false);
        actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        actionBar.setBackground(new PreviewBackgroundDrawable());
        actionBar.setSupportsHolidayImage(true);
        actionBar.setTitle(TypefaceHelper.getTitleText(UserConfig.selectedAccount));

        addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 21, 21, 21, 21));
    }

    public void refresh() {
        if (actionBar == null) return;
        actionBar.setTitle(TypefaceHelper.getTitleText(UserConfig.selectedAccount));
        SimpleTextView title = actionBar.getTitleTextView();
        if (title != null) {
            boolean centered = NaConfig.INSTANCE.getCenterActionBarTitle().Bool();
            title.setGravity(centered ? Gravity.CENTER : Gravity.LEFT | Gravity.CENTER_VERTICAL);
            title.forceLayout();
        }
        actionBar.forceLayout();

        int w = actionBar.getMeasuredWidth();
        int h = actionBar.getMeasuredHeight();
        if (w > 0 && h > 0) {
            actionBar.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            );
            int left = actionBar.getLeft();
            int top = actionBar.getTop();
            actionBar.layout(left, top, left + w, top + h);
        }
        actionBar.invalidate();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refresh();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (actionBar != null) {
            actionBar.invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth(),
                getMeasuredHeight() - 1, Theme.dividerPaint);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), getMeasuredHeight());
    }

    private static class PreviewBackgroundDrawable extends Drawable {
        private final android.graphics.Paint fillPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint strokePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final float radius = AndroidUtilities.dp(12);
        private final float strokeWidth = AndroidUtilities.dp(0.5f);

        PreviewBackgroundDrawable() {
            strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
        }

        @Override
        public void draw(@androidx.annotation.NonNull Canvas canvas) {
            int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            float fillAlpha = Theme.isCurrentThemeDark() ? 0.05f : 0.035f;
            fillPaint.setColor(Theme.multAlpha(textColor, fillAlpha));
            strokePaint.setColor(Theme.multAlpha(textColor, fillAlpha + 0.085f));
            float halfStroke = strokeWidth / 2f;
            rect.set(
                    getBounds().left + halfStroke,
                    getBounds().top + halfStroke,
                    getBounds().right - halfStroke,
                    getBounds().bottom - halfStroke
            );
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            canvas.drawRoundRect(rect, radius, radius, strokePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
