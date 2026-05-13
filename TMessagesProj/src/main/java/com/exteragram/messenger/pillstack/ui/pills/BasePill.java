package com.exteragram.messenger.pillstack.ui.pills;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LoadingDrawable;

public abstract class BasePill extends FrameLayout {

    private static final SparseArray<Long> globalLastUpdateTimes = new SparseArray<>();

    protected boolean loading;
    protected LoadingDrawable loadingDrawable;
    protected View loadingTargetView;
    private final RectF rectF = new RectF();
    protected Theme.ResourcesProvider resourcesProvider;

    private final Runnable autoRefreshRunnable = () -> {
        onUpdateData(false);
        scheduleNextUpdate();
    };

    public abstract int getPillId();

    public abstract long getRefreshInterval();

    public abstract void onPillClicked();

    public abstract boolean onPillLongClicked();

    public void onPillSelected() {
    }

    public void onPillUnselected() {
    }

    public abstract void onUpdateData(boolean force);

    public abstract void updateColors();

    public BasePill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setLayoutParams(new FrameLayout.LayoutParams(-2, -2, (LocaleController.isRTL ? 3 : 5) | 16));
        setClipChildren(false);
        setClipToPadding(false);
    }

    private void scheduleNextUpdate() {
        removeCallbacks(autoRefreshRunnable);
        long interval = getRefreshInterval();
        if (interval > 0) {
            postDelayed(autoRefreshRunnable, interval);
        }
    }

    protected boolean isRefreshDue() {
        long interval = getRefreshInterval();
        if (interval <= 0) {
            return true;
        }
        Long last = globalLastUpdateTimes.get(getPillId(), 0L);
        return last == 0L || SystemClock.elapsedRealtime() - last >= interval;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        long interval = getRefreshInterval();
        if (interval > 0) {
            long now = SystemClock.elapsedRealtime();
            Long last = globalLastUpdateTimes.get(getPillId(), 0L);
            if (last != 0L) {
                long diff = now - last;
                if (diff < interval) {
                    postDelayed(autoRefreshRunnable, interval - diff);
                    return;
                }
            }
            autoRefreshRunnable.run();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(autoRefreshRunnable);
    }

    protected void markDataUpdated() {
        globalLastUpdateTimes.put(getPillId(), SystemClock.elapsedRealtime());
        scheduleNextUpdate();
    }

    protected void setLoadingTargetView(View view) {
        this.loadingTargetView = view;
    }

    public void startLoading() {
        this.loading = true;
        if (loadingDrawable == null) {
            loadingDrawable = new LoadingDrawable(resourcesProvider);
            loadingDrawable.setCallback(this);
            loadingDrawable.setGradientScale(2.0f);
            loadingDrawable.setRadiiDp(14.0f);
            updateLoadingColors();
        }
        loadingDrawable.reset();
        loadingDrawable.resetDisappear();
        loadingDrawable.setAlpha(255);
        invalidate();
    }

    protected void animateSizeChange() {
        if (isLaidOut() && getVisibility() == VISIBLE && getParent() != null && getParent().getParent() instanceof ViewGroup) {
            TransitionManager.beginDelayedTransition((ViewGroup) getParent().getParent(),
                new TransitionSet().addTransition(new ChangeBounds()).setDuration(300).setInterpolator((TimeInterpolator) CubicBezierInterpolator.EASE_OUT_QUINT));
        }
    }

    public void stopLoading() {
        this.loading = false;
        if (loadingDrawable != null) {
            loadingDrawable.disappear();
        }
    }

    protected void updateLoadingColors() {
        if (loadingDrawable != null) {
            int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            loadingDrawable.setColors(Theme.multAlpha(color, 0.05f), Theme.multAlpha(color, 0.15f));
        }
    }

    protected int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    protected int getThemedColor(int key, float alpha) {
        return Theme.multAlpha(getThemedColor(key), alpha);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (loadingDrawable != null) {
            if (loadingDrawable.getAlpha() > 0 || !loadingDrawable.isDisappearing()) {
                View view = loadingTargetView != null ? loadingTargetView : this;
                rectF.set(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
                loadingDrawable.setBounds(rectF);
                loadingDrawable.draw(canvas);
                invalidate();
            }
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == loadingDrawable || super.verifyDrawable(who);
    }
}
