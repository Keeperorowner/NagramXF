package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;

import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.hooks.MenuItemRecord;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PopupSwipeBackLayout;

import java.util.List;
import java.util.Map;

public class PluginsMenuWrapper {
    public static final int GAP_ITEM_HEIGHT = 8;
    public static final int ITEM_HEIGHT = 48;
    public static final int SUBTITLE_ITEM_HEIGHT = 56;

    private Map<String, Object> contextData;
    private final BaseFragment fragment;
    private final LinearLayout menuItemsContainer;
    private final String menuType;
    private final Theme.ResourcesProvider resourcesProvider;
    public final LinearLayout swipeBack;

    protected void closeMenu() {
    }

    public PluginsMenuWrapper(BaseFragment fragment, PopupSwipeBackLayout popupSwipeBackLayout, String menuType, Map<String, Object> contextData, Theme.ResourcesProvider resourcesProvider) {
        this(fragment, popupSwipeBackLayout, null, menuType, contextData, resourcesProvider);
    }

    public PluginsMenuWrapper(BaseFragment fragment, final PopupSwipeBackLayout popupSwipeBackLayout, List<MenuItemRecord> items, String menuType, Map<String, Object> contextData, Theme.ResourcesProvider resourcesProvider) {
        this.fragment = fragment;
        this.resourcesProvider = resourcesProvider;
        this.menuType = menuType;
        this.contextData = contextData;

        Activity parentActivity = fragment.getParentActivity();
        swipeBack = new LinearLayout(parentActivity);
        swipeBack.setOrientation(LinearLayout.VERTICAL);

        ActionBarMenuSubItem backItem = new ActionBarMenuSubItem(parentActivity, true, false, resourcesProvider);
        backItem.setItemHeight(44);
        backItem.setTextAndIcon(LocaleController.getString(R.string.Back), R.drawable.msg_arrow_back);
        backItem.getTextView().setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(40), 0, LocaleController.isRTL ? AndroidUtilities.dp(40) : 0, 0);
        backItem.setOnClickListener(v -> popupSwipeBackLayout.closeForeground());
        swipeBack.addView(backItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = createScrollView(parentActivity);
        menuItemsContainer = new LinearLayout(parentActivity);
        menuItemsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(menuItemsContainer);
        swipeBack.addView(scrollView);

        rebuildMenu(items);
    }

    public View getSwipeBackView() {
        return swipeBack;
    }

    public void setContextData(Map<String, Object> contextData) {
        this.contextData = contextData;
    }

    public void rebuildMenu(List<MenuItemRecord> items) {
        menuItemsContainer.removeAllViews();
        if (items == null) {
            items = PluginsController.getInstance().getMenuItemsForLocation(menuType, contextData);
        }

        menuItemsContainer.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, GAP_ITEM_HEIGHT));
        int totalHeight = 0;
        for (final MenuItemRecord item : items) {
            if (item == null) {
                menuItemsContainer.addView(createGap(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, GAP_ITEM_HEIGHT));
                totalHeight += GAP_ITEM_HEIGHT;
                continue;
            }
            if (TextUtils.isEmpty(item.text)) {
                continue;
            }

            ActionBarMenuSubItem subItem = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false, resourcesProvider);
            subItem.setTextAndIcon(item.text, item.iconResId != 0 ? item.iconResId : R.drawable.msg_plugins);
            subItem.setMinimumWidth(AndroidUtilities.dp(196));
            subItem.setOnClickListener(v -> {
                closeMenu();
                item.executeClick(contextData);
            });

            int itemHeight = ITEM_HEIGHT;
            if (!TextUtils.isEmpty(item.subtext)) {
                subItem.setSubtext(item.subtext);
                subItem.setItemHeight(SUBTITLE_ITEM_HEIGHT);
                itemHeight = SUBTITLE_ITEM_HEIGHT;
            }
            menuItemsContainer.addView(subItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, itemHeight));
            totalHeight += itemHeight;
            subItem.setTag(item);
        }

        int maxHeight = AndroidUtilities.dp(436);
        View scrollView = (View) menuItemsContainer.getParent();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) scrollView.getLayoutParams();
        if (params == null) {
            params = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        }
        if (totalHeight > maxHeight && Math.abs(totalHeight - maxHeight) > 112) {
            params.height = maxHeight;
        } else {
            params.height = LayoutHelper.WRAP_CONTENT;
        }
        scrollView.setLayoutParams(params);
    }

    private ScrollView createScrollView(Context context) {
        return new ScrollView(context) {
            final AnimatedFloat alphaFloat = new AnimatedFloat(this, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
            Drawable topShadowDrawable;
            private boolean wasCanScrollVertically;

            @Override
            public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
                super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed);
                boolean canScroll = canScrollVertically(-1);
                if (wasCanScrollVertically != canScroll) {
                    invalidate();
                    wasCanScrollVertically = canScroll;
                }
            }

            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                float alpha = alphaFloat.set(canScrollVertically(-1) ? 1.0f : 0.0f) * 0.5f;
                if (alpha <= 0.0f) {
                    return;
                }
                if (topShadowDrawable == null) {
                    topShadowDrawable = ContextCompat.getDrawable(getContext(), R.drawable.header_shadow);
                }
                if (topShadowDrawable == null) {
                    return;
                }
                topShadowDrawable.setBounds(0, getScrollY(), getWidth(), getScrollY() + topShadowDrawable.getIntrinsicHeight());
                topShadowDrawable.setAlpha((int) (alpha * 255));
                topShadowDrawable.draw(canvas);
            }
        };
    }

    private View createGap() {
        FrameLayout gap = new FrameLayout(fragment.getContext());
        gap.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuSeparator, resourcesProvider));
        return gap;
    }
}
