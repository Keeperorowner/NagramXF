package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.Utilities;

public class UniversalView extends View {
    private UniversalViewDelegate delegate;

    public UniversalView(Context context) {
        super(context);
    }

    public UniversalView(Context context, UniversalViewDelegate delegate) {
        super(context);
        this.delegate = delegate;
    }

    public UniversalView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setDelegate(UniversalViewDelegate delegate) {
        this.delegate = delegate;
    }

    public UniversalViewDelegate getDelegate() {
        return delegate;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (delegate != null) {
            delegate.onDraw(canvas, this::superOnDraw);
        } else {
            super.onDraw(canvas);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (delegate != null) {
            delegate.onMeasure(widthMeasureSpec, heightMeasureSpec, this::superOnMeasure);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (delegate != null) {
            return delegate.onTouchEvent(event, this::superOnTouchEvent);
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (delegate != null) {
            delegate.onAttachedToWindow();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (delegate != null) {
            delegate.onDetachedFromWindow();
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        if (delegate != null) {
            delegate.onInitializeAccessibilityNodeInfo(info, this::superOnInitializeAccessibilityNodeInfo);
        } else {
            super.onInitializeAccessibilityNodeInfo(info);
        }
    }

    private void superOnDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    private void superOnMeasure(Integer width, Integer height) {
        super.onMeasure(width, height);
    }

    private boolean superOnTouchEvent(MotionEvent event) {
        return super.onTouchEvent(event);
    }

    private void superOnInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
    }

    public interface UniversalViewDelegate {
        default void onDraw(Canvas canvas, Utilities.Callback<Canvas> originalMethod) { originalMethod.run(canvas); }
        default void onMeasure(int widthMeasureSpec, int heightMeasureSpec, Utilities.Callback2<Integer, Integer> originalMethod) { originalMethod.run(widthMeasureSpec, heightMeasureSpec); }
        default boolean onTouchEvent(MotionEvent event, Utilities.CallbackReturn<MotionEvent, Boolean> originalMethod) { return originalMethod.run(event); }
        default void onAttachedToWindow() {}
        default void onDetachedFromWindow() {}
        default void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info, Utilities.Callback<AccessibilityNodeInfo> originalMethod) { originalMethod.run(info); }
    }
}
