package com.exteragram.messenger.pillstack.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.ArrayList;
import java.util.List;

public class PillStackView extends FrameLayout {

    private static final long LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    private final List<BasePill> pills = new ArrayList<>();
    private int currentIndex;
    private final float touchSlop;

    private boolean isSwiping;
    private boolean isSwipingUp;
    private float startX;
    private float startY;
    private float currentSwipeProgress;
    private ValueAnimator currentAnimator;

    private boolean maybeClick;
    private boolean longClickPerformed;
    private float visibilityFactor = -1f;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!maybeClick || isSwiping || pills.isEmpty()) {
                return;
            }
            longClickPerformed = pills.get(currentIndex).onPillLongClicked();
            if (longClickPerformed) {
                performHapticFeedback(0);
            }
        }
    };

    public PillStackView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
    }

    public void addPill(BasePill pill) {
        pills.add(pill);
        addView(pill);
        if (pills.size() - 1 != currentIndex) {
            pill.setAlpha(0f);
            pill.setScaleX(0.8f);
            pill.setScaleY(0.8f);
            pill.setVisibility(GONE);
        } else {
            pill.setVisibility(VISIBLE);
            pill.onPillSelected();
        }
    }

    public int getPillsCount() {
        return pills.size();
    }

    public void setCurrentIndex(int index) {
        if (index < 0 || index >= pills.size() || index == currentIndex) {
            return;
        }
        BasePill prev = pills.get(currentIndex);
        prev.setVisibility(GONE);
        prev.onPillUnselected();
        currentIndex = index;
        BasePill next = pills.get(index);
        next.setVisibility(VISIBLE);
        next.setAlpha(1f);
        next.setScaleX(1f);
        next.setScaleY(1f);
        next.setTranslationY(0f);
        next.onPillSelected();
        requestLayout();
    }

    public void clearPills() {
        if (!pills.isEmpty() && currentIndex < pills.size()) {
            pills.get(currentIndex).onPillUnselected();
        }
        pills.clear();
        removeAllViews();
        currentIndex = 0;
    }

    public void setVisibilityFactor(float factor) {
        if (visibilityFactor == factor) {
            return;
        }
        visibilityFactor = factor;
        if (factor > 0.01f) {
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
            }
            setAlpha(visibilityFactor);
            setScaleX(AndroidUtilities.lerp(0.6f, 1f, visibilityFactor));
            setScaleY(AndroidUtilities.lerp(0.6f, 1f, visibilityFactor));
        } else {
            setVisibility(GONE);
        }
    }

    public void updateColors() {
        for (BasePill pill : pills) {
            pill.updateColors();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (pills.isEmpty()) {
            return super.onInterceptTouchEvent(ev);
        }
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = ev.getRawX();
            startY = ev.getRawY();
            isSwiping = false;
        } else if (action == MotionEvent.ACTION_MOVE) {
            float dx = ev.getRawX() - startX;
            float dy = ev.getRawY() - startY;
            if ((Math.abs(dy) > touchSlop || Math.abs(dx) > touchSlop)
                && Math.abs(dy) > touchSlop
                && pills.size() > 1) {
                isSwiping = true;
                if (currentAnimator != null) {
                    currentAnimator.cancel();
                }
                startY = ev.getRawY() - (isSwipingUp ? -(currentSwipeProgress * getHeight()) : currentSwipeProgress * getHeight());
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pills.isEmpty()) {
            return super.onTouchEvent(ev);
        }
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getRawX();
                startY = ev.getRawY();
                isSwiping = false;
                maybeClick = true;
                longClickPerformed = false;
                if (currentIndex < pills.size()) {
                    pills.get(currentIndex).setPressed(true);
                    pills.get(currentIndex).drawableHotspotChanged(ev.getX(), ev.getY());
                }
                postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getRawY() - startY;
                float dx = ev.getRawX() - startX;
                if (!isSwiping && (Math.abs(dy) > touchSlop || Math.abs(dx) > touchSlop)) {
                    maybeClick = false;
                    removeCallbacks(longPressRunnable);
                    if (Math.abs(dy) > touchSlop && pills.size() > 1) {
                        isSwiping = true;
                        if (currentIndex < pills.size()) {
                            pills.get(currentIndex).setPressed(false);
                        }
                        if (currentAnimator != null) {
                            currentAnimator.cancel();
                        }
                        startY = ev.getRawY() - (isSwipingUp ? -(currentSwipeProgress * getHeight()) : currentSwipeProgress * getHeight());
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (isSwiping) {
                    handleSwipeProgress(ev.getRawY() - startY);
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressRunnable);
                if (currentIndex < pills.size()) {
                    pills.get(currentIndex).setPressed(false);
                }
                if (isSwiping) {
                    finishSwipe(ev.getRawY() - startY);
                } else if (maybeClick && !longClickPerformed) {
                    if (currentIndex < pills.size()) {
                        pills.get(currentIndex).onPillClicked();
                    }
                }
                maybeClick = false;
                isSwiping = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressRunnable);
                if (currentIndex < pills.size()) {
                    pills.get(currentIndex).setPressed(false);
                }
                if (isSwiping) {
                    cancelSwipe(isSwipingUp);
                }
                maybeClick = false;
                isSwiping = false;
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private void handleSwipeProgress(float delta) {
        if (pills.size() <= 1) {
            return;
        }
        int height = getHeight();
        if (height <= 0) {
            return;
        }
        isSwipingUp = delta < 0f;
        float normalized = Math.abs(delta) / height;
        int target = isSwipingUp ? currentIndex + 1 : currentIndex - 1;
        if (!PillStackConfig.infiniteScrolling && (target >= pills.size() || target < 0)) {
            currentSwipeProgress = normalized;
        } else {
            currentSwipeProgress = Math.min(normalized, 1f);
        }
        applyProgress(currentSwipeProgress, isSwipingUp);
    }

    private void applyProgress(float progress, boolean up) {
        BasePill current = pills.get(currentIndex);
        int targetIndex = up ? currentIndex + 1 : currentIndex - 1;
        if (PillStackConfig.infiniteScrolling) {
            if (targetIndex >= pills.size()) {
                targetIndex = 0;
            }
            if (targetIndex < 0) {
                targetIndex = pills.size() - 1;
            }
        }
        for (int i = 0; i < pills.size(); i++) {
            if (i != currentIndex && i != targetIndex && pills.get(i).getVisibility() != GONE) {
                pills.get(i).setVisibility(GONE);
            }
        }
        if (!PillStackConfig.infiniteScrolling && (targetIndex >= pills.size() || targetIndex < 0)) {
            float h = getHeight();
            float damping = (float) (1.0 - 1.0 / ((progress * 0.18f) + 1.0));
            current.setTranslationY(up ? -(h * damping) : h * damping);
            current.setAlpha(1f);
            return;
        }
        float p = Math.min(progress, 1f);
        BasePill target = pills.get(targetIndex);
        if (target.getVisibility() != VISIBLE) {
            target.setVisibility(VISIBLE);
        }
        float distance = getHeight() * p;
        if (up) {
            distance = -distance;
        }
        current.setTranslationY(distance);
        current.setAlpha(1f - p);
        float scaleDown = 1f - 0.2f * p;
        current.setScaleX(scaleDown);
        current.setScaleY(scaleDown);
        float scaleUp = 0.8f + 0.2f * p;
        target.setScaleX(scaleUp);
        target.setScaleY(scaleUp);
        target.setAlpha(p);
        float startOffset = up ? getHeight() : -getHeight();
        target.setTranslationY(startOffset - (p * startOffset));
    }

    private void finishSwipe(float delta) {
        int height = getHeight();
        if (height <= 0) {
            cancelSwipe(isSwipingUp);
            return;
        }
        float threshold = height * 0.25f;
        boolean canAdvance = true;
        if (!PillStackConfig.infiniteScrolling) {
            int target = isSwipingUp ? currentIndex + 1 : currentIndex - 1;
            if (target >= pills.size() || target < 0) {
                canAdvance = false;
            }
        }
        if (Math.abs(delta) > threshold && canAdvance) {
            animateToNextPill(isSwipingUp);
        } else {
            cancelSwipe(isSwipingUp);
        }
    }

    private void animateToNextPill(boolean up) {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(currentSwipeProgress, 1f);
        currentAnimator = animator;
        animator.setDuration(250);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue(), up));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) {
                    return;
                }
                BasePill prev = pills.get(currentIndex);
                prev.setVisibility(GONE);
                prev.setPressed(false);
                prev.setScaleX(1f);
                prev.setScaleY(1f);
                prev.onPillUnselected();
                currentIndex = up ? currentIndex + 1 : currentIndex - 1;
                if (PillStackConfig.infiniteScrolling) {
                    if (currentIndex >= pills.size()) {
                        currentIndex = 0;
                    }
                    if (currentIndex < 0) {
                        currentIndex = pills.size() - 1;
                    }
                }
                for (int i = 0; i < pills.size(); i++) {
                    if (i != currentIndex) {
                        pills.get(i).setVisibility(GONE);
                    }
                }
                BasePill next = pills.get(currentIndex);
                next.setVisibility(VISIBLE);
                next.setScaleX(1f);
                next.setScaleY(1f);
                next.setTranslationY(0f);
                next.setAlpha(1f);
                next.onPillSelected();
                currentSwipeProgress = 0f;
                PillStackConfig.saveLastActivePillId(next.getPillId());
            }
        });
        animator.start();
    }

    private void cancelSwipe(boolean up) {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        ValueAnimator animator = ValueAnimator.ofFloat(currentSwipeProgress, 0f);
        currentAnimator = animator;
        animator.setDuration(200);
        animator.addUpdateListener(a -> applyProgress((float) a.getAnimatedValue(), up));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled) {
                    return;
                }
                for (int i = 0; i < pills.size(); i++) {
                    if (i != currentIndex) {
                        BasePill pill = pills.get(i);
                        pill.setVisibility(GONE);
                        pill.setPressed(false);
                        pill.setScaleX(1f);
                        pill.setScaleY(1f);
                    }
                }
                BasePill cur = pills.get(currentIndex);
                cur.setTranslationY(0f);
                cur.setAlpha(1f);
                cur.setScaleX(1f);
                cur.setScaleY(1f);
                currentSwipeProgress = 0f;
            }
        });
        animator.start();
    }
}
