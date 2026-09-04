package com.exteragram.messenger.plugins.hooks;

import android.content.Context;
import android.text.TextUtils;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.utils.PyObjectUtils;

import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MenuItemRecord {
    private static final ConcurrentHashMap<String, Serializable> MVEL_EXPRESSION_CACHE = new ConcurrentHashMap<>();

    public final String pluginId;
    public final String menuType;
    public final String text;
    public final PyObject onClickCallback;
    public final String itemId;
    public final String iconName;
    public final int iconResId;
    public final String subtext;
    public final String conditionString;
    public final int priority;

    private volatile boolean removed;

    public String getPluginId() {
        return pluginId;
    }

    public String getMenuType() {
        return menuType;
    }

    public String getText() {
        return text;
    }

    public PyObject getOnClickCallback() {
        return onClickCallback;
    }

    public String getItemId() {
        return itemId;
    }

    public String getIconName() {
        return iconName;
    }

    public int getIconResId() {
        return iconResId;
    }

    public String getSubtext() {
        return subtext;
    }

    public String getConditionString() {
        return conditionString;
    }

    public int getPriority() {
        return priority;
    }

    public MenuItemRecord(String pluginId, PyObject object) {
        this.pluginId = pluginId;
        this.menuType = PyObjectUtils.getString(object, PluginsConstants.MenuItemProperties.MENU_TYPE, null, true);
        this.text = PyObjectUtils.getString(object, PluginsConstants.MenuItemProperties.TEXT, null, true);
        this.onClickCallback = object.callAttr("get", PluginsConstants.MenuItemProperties.ON_CLICK);
        String itemIdValue = PyObjectUtils.getString(object, PluginsConstants.MenuItemProperties.ITEM_ID, null, true);
        this.itemId = TextUtils.isEmpty(itemIdValue) ? UUID.randomUUID().toString() : itemIdValue;
        this.iconName = PyObjectUtils.getString(object, PluginsConstants.MenuItemProperties.ICON, null, true);
        this.subtext = PyObjectUtils.getString(object, PluginsConstants.MenuItemProperties.SUBTEXT, null, true);
        this.conditionString = PyObjectUtils.getString(object, PluginsConstants.MenuItemProperties.CONDITION, null, true);
        this.priority = PyObjectUtils.getInt(object, PluginsConstants.MenuItemProperties.PRIORITY, 0, true);
        int iconRes = 0;
        if (!TextUtils.isEmpty(iconName)) {
            try {
                Context context = ApplicationLoader.applicationContext;
                iconRes = context.getResources().getIdentifier(iconName, "drawable", context.getPackageName());
            } catch (Exception ignored) {
            }
        }
        this.iconResId = iconRes;
        if (TextUtils.isEmpty(menuType) || TextUtils.isEmpty(text) || onClickCallback == null) {
            throw new IllegalArgumentException("MenuItemRecord missing essential fields: menuType, text, or onClickCallback.");
        }
    }

    public void markRemoved() {
        removed = true;
    }

    public void executeClick(Object contextData) {
        if (removed || !PluginsController.getInstance().isPluginActive(pluginId)) {
            return;
        }
        try {
            if (onClickCallback != null) {
                PyObject result = onClickCallback.call(contextData);
                if (result != null) {
                    result.close();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public boolean checkCondition(Map<String, Object> data) {
        if (TextUtils.isEmpty(conditionString) || data == null) {
            return true;
        }
        try {
            Serializable expression = MVEL_EXPRESSION_CACHE.computeIfAbsent(conditionString, MVEL::compileExpression);
            Boolean result = MVEL.executeExpression(expression, data, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof MenuItemRecord other && itemId.equals(other.itemId) && pluginId.equals(other.pluginId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, pluginId);
    }
}
