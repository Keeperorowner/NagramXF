package com.exteragram.messenger.components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ActionRow extends FrameLayout {
    private static final int HORIZONTAL_PADDING_DP = 10;
    private static final int ITEM_SIZE_DP = 40;

    private final FrameLayout buttonsView;
    private final List<ActionItem> currentItems = new ArrayList<>();

    public static class ActionItem {
        public final int icon;
        public final boolean enabled;
        public final View.OnClickListener action;
        public final View.OnLongClickListener longAction;

        public ActionItem(int icon, boolean enabled, View.OnClickListener action) {
            this(icon, enabled, action, null);
        }

        public ActionItem(int icon, boolean enabled, View.OnClickListener action, View.OnLongClickListener longAction) {
            this.icon = icon;
            this.enabled = enabled;
            this.action = action;
            this.longAction = longAction;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ActionItem)) {
                return false;
            }
            return icon == ((ActionItem) obj).icon;
        }

        @Override
        public int hashCode() {
            return icon;
        }
    }

    public ActionRow(Context context, Theme.ResourcesProvider resourcesProvider, List<ActionItem> items) {
        super(context);
        buttonsView = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                int childCount = getChildCount();
                if (childCount == 0) {
                    return;
                }
                int extraSpace = (right - left) - AndroidUtilities.dp(childCount * ITEM_SIZE_DP + HORIZONTAL_PADDING_DP * 2);
                int divider = Math.max(1, childCount - 1);
                for (int i = 0; i < childCount; i++) {
                    int childLeft = AndroidUtilities.dp(i * ITEM_SIZE_DP + HORIZONTAL_PADDING_DP) + extraSpace * i / divider;
                    int childTop = AndroidUtilities.dp(8);
                    View child = getChildAt(i);
                    child.layout(childLeft, childTop, childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
                }
            }
        };
        addView(buttonsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        updateItems(items, resourcesProvider);
    }

    public void updateItems(List<ActionItem> items, Theme.ResourcesProvider resourcesProvider) {
        buttonsView.removeAllViews();
        currentItems.clear();
        ArrayList<ActionItem> disabledItems = new ArrayList<>();
        Iterator<ActionItem> iterator = items.iterator();
        int index = 0;
        int visibleCount = 0;
        while (iterator.hasNext()) {
            ActionItem actionItem = iterator.next();
            if (actionItem.enabled && visibleCount < 4) {
                addImageButton(getContext(), resourcesProvider, buttonsView, actionItem, visibleCount);
                currentItems.add(actionItem);
                visibleCount++;
            } else {
                disabledItems.add(actionItem);
            }
        }
        int size = disabledItems.size();
        while (index < size) {
            ActionItem actionItem = disabledItems.get(index++);
            if (visibleCount >= 4) {
                return;
            }
            addImageButton(getContext(), resourcesProvider, buttonsView, actionItem, visibleCount);
            currentItems.add(actionItem);
            visibleCount++;
        }
    }

    private void addImageButton(Context context, Theme.ResourcesProvider resourcesProvider, FrameLayout parent, ActionItem actionItem, int index) {
        final ImageView imageView = new ImageView(context) {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                setAlpha(enabled ? 1.0f : 0.5f);
            }
        };
        ScaleStateListAnimator.apply(imageView, 0.15f, 1.5f);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setEnabled(actionItem.enabled);
        imageView.setImageDrawable(ContextCompat.getDrawable(context, actionItem.icon).mutate());
        imageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        imageView.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider), 1, AndroidUtilities.dp(20)));
        imageView.setOnClickListener(actionItem.action);
        if (actionItem.longAction != null) {
            imageView.setOnLongClickListener(actionItem.longAction);
        }
        imageView.setTag(actionItem);
        imageView.setAlpha(0.0f);
        imageView.setTranslationX(AndroidUtilities.dp(12));
        parent.addView(imageView, LayoutHelper.createFrame(ITEM_SIZE_DP, ITEM_SIZE_DP, 51));
        imageView.post(() -> imageView.animate()
                .alpha(actionItem.enabled ? 1.0f : 0.5f)
                .translationX(0.0f)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .setStartDelay(index * 35L + 100L)
                .setDuration(400L)
                .start());
    }

    public boolean isItemPresent(int icon) {
        for (int i = 0; i < buttonsView.getChildCount(); i++) {
            View child = buttonsView.getChildAt(i);
            if (child instanceof ImageView) {
                Object tag = child.getTag();
                if (tag instanceof ActionItem && ((ActionItem) tag).icon == icon) {
                    return true;
                }
            }
        }
        return false;
    }
}
