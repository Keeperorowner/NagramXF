package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.Utilities;

public class UniversalFrameLayout extends FrameLayout {
    private UniversalFrameLayoutListener listener;

    public UniversalFrameLayout(Context context) {
        super(context);
    }

    public UniversalFrameLayout(Context context, UniversalFrameLayoutListener listener) {
        super(context);
        this.listener = listener;
    }

    public UniversalFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setUniversalFrameLayoutListener(UniversalFrameLayoutListener listener) {
        this.listener = listener;
    }

    public UniversalFrameLayoutListener getUniversalFrameLayoutListener() {
        return listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (listener != null) {
            listener.onMeasure(widthMeasureSpec, heightMeasureSpec, this::superOnMeasure);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (listener != null) {
            listener.onLayout(changed, left, top, right, bottom, this::superOnLayout);
        } else {
            super.onLayout(changed, left, top, right, bottom);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (listener != null) {
            listener.dispatchDraw(canvas, this::superDispatchDraw);
        } else {
            super.dispatchDraw(canvas);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (listener != null) {
            listener.onDraw(canvas, this::superOnDraw);
        } else {
            super.onDraw(canvas);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (listener != null) {
            return listener.onInterceptTouchEvent(ev, this::superOnInterceptTouchEvent);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener != null) {
            return listener.onTouchEvent(event, this::superOnTouchEvent);
        }
        return super.onTouchEvent(event);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        if (listener != null) {
            listener.onInitializeAccessibilityNodeInfo(info, this::superOnInitializeAccessibilityNodeInfo);
        } else {
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }

    @Override
    public void setTranslationX(float translationX) {
        if (listener != null) {
            listener.setTranslationX(translationX, this::superSetTranslationX);
        } else {
            super.setTranslationX(translationX);
        }
    }

    @Override
    public void setTranslationY(float translationY) {
        if (listener != null) {
            listener.setTranslationY(translationY, this::superSetTranslationY);
        } else {
            super.setTranslationY(translationY);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        if (listener != null) {
            listener.onAttachedToWindow(this::superOnAttachedToWindow);
        } else {
            super.onAttachedToWindow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (listener != null) {
            listener.onDetachedFromWindow(this::superOnDetachedFromWindow);
        } else {
            super.onDetachedFromWindow();
        }
    }

    @Override
    public void requestLayout() {
        if (listener != null) {
            listener.requestLayout(this::superRequestLayout);
        } else {
            super.requestLayout();
        }
    }

    @Override
    public void invalidate() {
        if (listener != null) {
            listener.invalidate(this::superInvalidate);
        } else {
            super.invalidate();
        }
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        if (listener != null) {
            listener.invalidate(l, t, r, b, this::superInvalidate);
        } else {
            super.invalidate(l, t, r, b);
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (listener != null) {
            return listener.drawChild(canvas, child, drawingTime, this::superDrawChild);
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    public void setVisibility(int visibility) {
        if (listener != null) {
            listener.setVisibility(visibility, this::superSetVisibility);
        } else {
            super.setVisibility(visibility);
        }
    }

    private void superOnMeasure(Integer width, Integer height) {
        super.onMeasure(width, height);
    }

    private void superOnLayout(Boolean changed, Integer left, Integer top, Integer right, Integer bottom) {
        super.onLayout(changed, left, top, right, bottom);
    }

    private void superDispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    private void superOnDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    private boolean superOnInterceptTouchEvent(MotionEvent ev) {
        return super.onInterceptTouchEvent(ev);
    }

    private boolean superOnTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    private void superOnInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
    }

    private void superSetTranslationX(Float translationX) {
        super.setTranslationX(translationX);
    }

    private void superSetTranslationY(Float translationY) {
        super.setTranslationY(translationY);
    }

    private void superOnAttachedToWindow() {
        super.onAttachedToWindow();
    }

    private void superOnDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    private void superRequestLayout() {
        super.requestLayout();
    }

    private void superInvalidate() {
        super.invalidate();
    }

    private boolean superDrawChild(Canvas canvas, View child, Long drawingTime) {
        return super.drawChild(canvas, child, drawingTime);
    }

    private void superSetVisibility(Integer visibility) {
        super.setVisibility(visibility);
    }

    public interface UniversalFrameLayoutListener {
        default void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> originalMethod) { originalMethod.run(widthMeasureSpec, heightMeasureSpec); }
        default void onLayout(boolean changed, int left, int top, int right, int bottom, Utilities.Callback5<Boolean, Integer, Integer, Integer, Integer> originalMethod) { originalMethod.run(changed, left, top, right, bottom); }
        default void dispatchDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) { originalMethod.run(canvas); }
        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) { originalMethod.run(canvas); }
        default boolean onInterceptTouchEvent(MotionEvent ev, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) { return originalMethod.run(ev); }
        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) { return originalMethod.run(event); }
        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> originalMethod) { originalMethod.run(info); }
        default void setTranslationX(float translationX, Utilities.Callback<Float> originalMethod) { originalMethod.run(translationX); }
        default void setTranslationY(float translationY, Utilities.Callback<Float> originalMethod) { originalMethod.run(translationY); }
        default void onAttachedToWindow(Runnable originalMethod) { originalMethod.run(); }
        default void onDetachedFromWindow(Runnable originalMethod) { originalMethod.run(); }
        default void requestLayout(Runnable originalMethod) { originalMethod.run(); }
        default void invalidate(Runnable originalMethod) { originalMethod.run(); }
        default void invalidate(int l, int t, int r, int b, Runnable originalMethod) { originalMethod.run(); }
        default boolean drawChild(Canvas canvas, View child, long drawingTime, Utilities.Callback3Return<Canvas, View, Long, Boolean> originalMethod) { return originalMethod.run(canvas, child, drawingTime); }
        default void setVisibility(int visibility, Utilities.Callback<Integer> originalMethod) { originalMethod.run(visibility); }
    }
}
