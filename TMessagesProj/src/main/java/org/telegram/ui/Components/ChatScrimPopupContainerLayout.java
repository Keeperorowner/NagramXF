package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;

import java.util.ArrayList;
import java.util.List;

public class ChatScrimPopupContainerLayout extends LinearLayout {
    private float bottomViewReactionsOffset;
    private float bottomViewYOffset;
    private final List<FrameLayout> bottomViews = new ArrayList<>();
    private float currentPopupAlpha = 1.0f;
    private float expandSize;
    private float lastReactionsTransitionProgress;
    private int maxHeight;
    private float popupLayoutLeftOffset;
    private ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout;
    private float progressToSwipeBack;
    private ReactionsContainerLayout reactionsLayout;

    public ChatScrimPopupContainerLayout(Context context) {
        super(context);
        setOrientation(VERTICAL);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateBottomViewPosition();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int constrainedHeightSpec = maxHeight != 0 ? MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) : heightMeasureSpec;
        int adjustedWidthSpec = widthMeasureSpec;
        super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
        if (popupWindowLayout == null) {
            return;
        }

        if (reactionsLayout != null) {
            reactionsLayout.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
            ((LinearLayout.LayoutParams) reactionsLayout.getLayoutParams()).rightMargin = 0;
        }

        int maxWidth = reactionsLayout != null ? reactionsLayout.getMeasuredWidth() : 0;
        if (popupWindowLayout.getSwipeBack() != null && popupWindowLayout.getSwipeBack().getMeasuredWidth() > maxWidth) {
            maxWidth = popupWindowLayout.getSwipeBack().getMeasuredWidth();
        }
        if (popupWindowLayout.getMeasuredWidth() > maxWidth) {
            maxWidth = popupWindowLayout.getMeasuredWidth();
        }

        if (reactionsLayout != null && reactionsLayout.showCustomEmojiReaction()) {
            adjustedWidthSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.EXACTLY);
        }

        boolean needsRemeasure = false;
        if (reactionsLayout != null) {
            reactionsLayout.measureHint();
            int totalWidth = reactionsLayout.getTotalWidth();
            View menuContainer = (popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack() : popupWindowLayout).getChildAt(0);
            int maxReactionsLayoutWidth = menuContainer.getMeasuredWidth() + AndroidUtilities.dp(16) + AndroidUtilities.dp(16) + AndroidUtilities.dp(36);
            int hintTextWidth = reactionsLayout.getHintTextWidth();
            if (hintTextWidth > maxReactionsLayoutWidth) {
                maxReactionsLayoutWidth = hintTextWidth;
            } else if (maxReactionsLayoutWidth > maxWidth) {
                maxReactionsLayoutWidth = maxWidth;
            }
            reactionsLayout.bigCircleOffset = AndroidUtilities.dp(36);
            if (reactionsLayout.showCustomEmojiReaction()) {
                if (reactionsLayout.getLayoutParams().width != totalWidth) {
                    reactionsLayout.getLayoutParams().width = totalWidth;
                    needsRemeasure = true;
                }
                reactionsLayout.bigCircleOffset = Math.max(totalWidth - menuContainer.getMeasuredWidth() - AndroidUtilities.dp(36), AndroidUtilities.dp(36));
            } else if (totalWidth > maxReactionsLayoutWidth) {
                int maxFullCount = ((maxReactionsLayoutWidth - AndroidUtilities.dp(16)) / AndroidUtilities.dp(36)) + 1;
                int newWidth = maxFullCount * AndroidUtilities.dp(36) + AndroidUtilities.dp(8);
                if (hintTextWidth + AndroidUtilities.dp(24) > newWidth) {
                    newWidth = hintTextWidth + AndroidUtilities.dp(24);
                }
                if (newWidth <= totalWidth && maxFullCount != reactionsLayout.getItemsCount()) {
                    totalWidth = newWidth;
                }
                if (reactionsLayout.getLayoutParams().width != totalWidth) {
                    reactionsLayout.getLayoutParams().width = totalWidth;
                    needsRemeasure = true;
                }
            } else if (reactionsLayout.getLayoutParams().width != LayoutHelper.WRAP_CONTENT) {
                reactionsLayout.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
                needsRemeasure = true;
            }

            if (reactionsLayout.getMeasuredWidth() != maxWidth || !reactionsLayout.showCustomEmojiReaction()) {
                int widthDiff = popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack().getMeasuredWidth() - popupWindowLayout.getSwipeBack().getChildAt(0).getMeasuredWidth() : 0;
                if (reactionsLayout.getLayoutParams().width != LayoutHelper.WRAP_CONTENT && reactionsLayout.getLayoutParams().width + widthDiff > maxWidth) {
                    widthDiff = maxWidth - reactionsLayout.getLayoutParams().width + AndroidUtilities.dp(8);
                }
                if (widthDiff < 0) {
                    widthDiff = 0;
                }
                LinearLayout.LayoutParams reactionsParams = (LinearLayout.LayoutParams) reactionsLayout.getLayoutParams();
                if (reactionsParams.rightMargin != widthDiff) {
                    reactionsParams.rightMargin = widthDiff;
                    needsRemeasure = true;
                }
                popupLayoutLeftOffset = 0.0f;
            } else {
                float offset = (maxWidth - menuContainer.getMeasuredWidth()) * 0.25f;
                popupLayoutLeftOffset = offset;
                reactionsLayout.bigCircleOffset -= (int) offset;
                if (reactionsLayout.bigCircleOffset < AndroidUtilities.dp(36)) {
                    popupLayoutLeftOffset = 0.0f;
                    reactionsLayout.bigCircleOffset = AndroidUtilities.dp(36);
                }
            }
        }

        int foregroundWidth = (popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack() : popupWindowLayout).getChildAt(0).getMeasuredWidth();
        int popupWidth = popupWindowLayout.getMeasuredWidth();
        int swipeBackWidthDiff = popupWindowLayout.getSwipeBack() != null ? popupWindowLayout.getSwipeBack().getMeasuredWidth() - foregroundWidth : 0;
        int safeSwipeBackWidthDiff = Math.max(0, swipeBackWidthDiff);
        for (FrameLayout view : bottomViews) {
            if (view == null) {
                continue;
            }
            LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) view.getLayoutParams();
            int newWidth;
            if ((reactionsLayout == null || !reactionsLayout.showCustomEmojiReaction()) && view.getTag(R.id.fit_width_tag) == null) {
                newWidth = LayoutHelper.MATCH_PARENT;
            } else {
                newWidth = foregroundWidth + AndroidUtilities.dp(16);
                if (popupWidth > 0 && newWidth > popupWidth) {
                    newWidth = popupWidth;
                }
            }
            int newRightMargin = popupWindowLayout.getSwipeBack() != null ? AndroidUtilities.dp(36) + safeSwipeBackWidthDiff : AndroidUtilities.dp(36);
            if (layoutParams.width != newWidth || layoutParams.rightMargin != newRightMargin) {
                layoutParams.width = newWidth;
                layoutParams.rightMargin = newRightMargin;
                needsRemeasure = true;
            }
            if (progressToSwipeBack > 0.0f) {
                view.setAlpha((1.0f - progressToSwipeBack) * ((reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress) * currentPopupAlpha);
            }
        }

        updatePopupTranslation();
        if (needsRemeasure) {
            super.onMeasure(adjustedWidthSpec, constrainedHeightSpec);
        }
    }

    private void updatePopupTranslation() {
        float translationX = (1.0f - progressToSwipeBack) * popupLayoutLeftOffset;
        popupWindowLayout.setTranslationX(translationX);
        float bottomAlpha = (reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress;
        for (FrameLayout view : bottomViews) {
            if (view != null) {
                view.setTranslationX(translationX);
                view.setAlpha((1.0f - progressToSwipeBack) * bottomAlpha * currentPopupAlpha);
            }
        }
    }

    public void applyViewBottom(FrameLayout bottomView) {
        if (bottomView != null) {
            bottomViews.add(bottomView);
            if (popupWindowLayout != null) {
                updateBottomOffset();
            }
        }
    }

    public void setReactionsLayout(ReactionsContainerLayout reactionsLayout) {
        this.reactionsLayout = reactionsLayout;
        if (reactionsLayout != null) {
            reactionsLayout.setChatScrimView(this);
        }
    }

    private void updateBottomOffset() {
        bottomViewYOffset = popupWindowLayout.getVisibleHeight() - popupWindowLayout.getMeasuredHeight();
        updateBottomViewPosition();
    }

    public void setPopupWindowLayout(ActionBarPopupWindow.ActionBarPopupWindowLayout popupWindowLayout) {
        this.popupWindowLayout = popupWindowLayout;
        popupWindowLayout.setOnSizeChangedListener(this::updateBottomOffset);
        if (popupWindowLayout.getSwipeBack() != null) {
            popupWindowLayout.getSwipeBack().addOnSwipeBackProgressListener((layout, toProgress, progress) -> {
                float bottomAlpha = (reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress;
                for (FrameLayout view : bottomViews) {
                    if (view != null) {
                        view.setAlpha((1.0f - progress) * bottomAlpha * currentPopupAlpha);
                    }
                }
                progressToSwipeBack = progress;
                updatePopupTranslation();
            });
        }
    }

    private void updateBottomViewPosition() {
        float bottomAlpha = (reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 || reactionsLayout.getVisibility() != VISIBLE) ? 1.0f : lastReactionsTransitionProgress;
        for (FrameLayout view : bottomViews) {
            if (view == null) {
                continue;
            }
            if (bottomAlpha < 1.0f && view.getMeasuredHeight() > 0) {
                bottomViewReactionsOffset = -view.getMeasuredHeight() * (1.0f - bottomAlpha);
            } else {
                bottomViewReactionsOffset = 0.0f;
            }
            float alpha = bottomAlpha < 1.0f ? bottomAlpha : 1.0f;
            if (progressToSwipeBack > 0.0f) {
                alpha *= 1.0f - progressToSwipeBack;
            }
            view.setAlpha(alpha * currentPopupAlpha);
            view.setTranslationY(bottomViewYOffset + expandSize + bottomViewReactionsOffset);
        }
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    public void setExpandSize(float expandSize) {
        popupWindowLayout.setTranslationY(expandSize);
        this.expandSize = expandSize;
        updateBottomViewPosition();
    }

    public void setPopupAlpha(float alpha) {
        currentPopupAlpha = alpha;
        popupWindowLayout.setAlpha(alpha);
        for (FrameLayout view : bottomViews) {
            if (view != null) {
                view.setAlpha(alpha);
            }
        }
    }

    public void setReactionsTransitionProgress(float progress) {
        lastReactionsTransitionProgress = progress;
        popupWindowLayout.setReactionsTransitionProgress(progress);
        float visibleProgress = reactionsLayout == null || reactionsLayout.getItemsCount() <= 0 ? 1.0f : progress;
        for (FrameLayout view : bottomViews) {
            if (view != null) {
                if (progressToSwipeBack == 0.0f) {
                    view.setAlpha(visibleProgress);
                }
                float scale = visibleProgress * 0.5f + 0.5f;
                view.setPivotX(view.getMeasuredWidth());
                view.setPivotY(0.0f);
                view.setScaleX(scale);
                view.setScaleY(scale);
            }
        }
        updateBottomViewPosition();
    }
}
