package com.exteragram.messenger.plugins.ui.components;

import android.view.View;
import android.widget.LinearLayout;

import com.exteragram.messenger.plugins.hooks.MenuItemRecord;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.PopupSwipeBackLayout;

import java.util.List;
import java.util.Map;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 * Menu lists are always empty here, so the wrapper is never populated or shown.
 */
public class PluginsMenuWrapper {

    public final LinearLayout swipeBack;

    public PluginsMenuWrapper(BaseFragment fragment, PopupSwipeBackLayout popupSwipeBackLayout,
                              List<MenuItemRecord> items, String menuType, Map<String, Object> contextData,
                              Theme.ResourcesProvider resourcesProvider) {
        this.swipeBack = new LinearLayout(fragment != null ? fragment.getParentActivity() : null);
    }

    protected void closeMenu() {
    }

    public void setContextData(Map<String, Object> contextData) {
    }

    public void rebuildMenu(List<MenuItemRecord> items) {
    }

    public View getSwipeBackView() {
        return swipeBack;
    }
}
