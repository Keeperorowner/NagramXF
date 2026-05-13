package com.exteragram.messenger.pillstack.ui.pills.system;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CacheControlActivity;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.atomic.AtomicLong;

public class CachePill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private static final AtomicLong lastKnownCacheSize = new AtomicLong(-1L);
    private static float lastKnownProgress = -1f;

    private final ImageView iconView;
    private final LinearLayout layout;
    private final StorageProgressDrawable progressDrawable;
    private final AnimatedTextView textView;

    @Override
    public long getRefreshInterval() {
        return 180_000L;
    }

    public CachePill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48f));
        layout.setPadding(AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(8f), 0);
        addView(layout, LayoutHelper.createFrame(-2, 28, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        progressDrawable = new StorageProgressDrawable(iconView);
        iconView.setImageDrawable(progressDrawable);

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13f));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        if (lastKnownCacheSize.get() != -1L && !isRefreshDue()) {
            setData(lastKnownCacheSize.get(), lastKnownProgress, false);
        } else {
            iconView.setVisibility(GONE);
            textView.setVisibility(GONE);
        }
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.CACHE.id;
    }

    @Override
    public void onUpdateData(boolean force) {
        boolean unknown = lastKnownCacheSize.get() == -1L;
        if (!force && !unknown && !isRefreshDue()) {
            return;
        }
        if (force || unknown) {
            CacheControlActivity.resetCalculatedTotalSIze();
        }
        startLoading();
        ImageLoader.getInstance().checkMediaPaths(() ->
            CacheControlActivity.calculateTotalSize(size -> {
                lastKnownCacheSize.set(size);
                CacheControlActivity.getDeviceTotalSize((total, free) -> {
                    float progress = total > 0 ? (total - free) / (float) total : 0f;
                    lastKnownProgress = progress;
                    setData(size, progress, true);
                });
            }));
    }

    private void setData(long size, float progress, boolean animate) {
        stopLoading();
        String fileSize = AndroidUtilities.formatFileSize(size);
        if (animate && (textView.getText() == null
                || !TextUtils.equals(textView.getText(), fileSize)
                || textView.getVisibility() == GONE)) {
            animateSizeChange();
        }
        textView.setText(fileSize, animate);
        progressDrawable.setProgress(progress, animate);
        iconView.setVisibility(VISIBLE);
        textView.setVisibility(VISIBLE);
        markDataUpdated();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onUpdateData(PillStackConfig.checkAndClearPendingUpdate(getPillId()));
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pillStackSettingsChanged
                && PillStackConfig.shouldUpdatePill(args, getPillId())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public void onPillClicked() {
        openCacheSettings();
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) return false;
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg2_data, LocaleController.getString(R.string.StorageUsage), this::openCacheSettings)
                .addGap()
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new PillStackPreferencesActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();
        return true;
    }

    private void openCacheSettings() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new CacheControlActivity());
        }
    }

    @Override
    public void updateColors() {
        int color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14f),
                Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f),
                Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        progressDrawable.setColor(color);
        updateLoadingColors();
    }

    private static class StorageProgressDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        private final AnimatedFloat animatedProgress;
        private float progress;
        private int color;

        StorageProgressDrawable(View view) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            animatedProgress = new AnimatedFloat(view, 650, CubicBezierInterpolator.EASE_OUT_QUINT);
        }

        void setProgress(float value, boolean animate) {
            float clamped = Math.max(0.05f, Math.min(value, 1f));
            this.progress = clamped;
            if (!animate) {
                animatedProgress.force(clamped);
            }
            invalidateSelf();
        }

        void setColor(int color) {
            this.color = color;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            int width = getBounds().width();
            int height = getBounds().height();
            float size = Math.min(width, height) - AndroidUtilities.dp(2f);
            float cx = (width - size) / 2f;
            float cy = (height - size) / 2f;
            rectF.set(cx, cy, cx + size, cy + size);
            float fraction = animatedProgress.set(progress);
            paint.setStrokeWidth(AndroidUtilities.dp(2f));
            paint.setColor(color);
            paint.setAlpha(50);
            canvas.drawCircle(width / 2f, height / 2f, size / 2f, paint);
            paint.setAlpha(255);
            canvas.drawArc(rectF, -90f, fraction * 360f, false, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
