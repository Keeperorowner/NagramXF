package com.exteragram.messenger.plugins.models;

import android.text.TextUtils;

import com.chaquo.python.PyObject;

public abstract class SettingItem {
    public String type;
    public String icon;
    public PyObject onLongClickCallback;
    public String linkAlias;

    public SettingItem(String type) {
        this(type, null, null, null);
    }

    public SettingItem(String type, String icon, PyObject onLongClickCallback, String linkAlias) {
        this.type = type;
        this.icon = icon;
        this.onLongClickCallback = onLongClickCallback;
        this.linkAlias = linkAlias;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public PyObject getOnLongClickCallback() {
        return onLongClickCallback;
    }

    public void setOnLongClickCallback(PyObject onLongClickCallback) {
        this.onLongClickCallback = onLongClickCallback;
    }

    public String getLinkAlias() {
        return linkAlias;
    }

    public void setLinkAlias(String linkAlias) {
        this.linkAlias = linkAlias;
    }

    public void closeCallback(PyObject callback) {
        if (callback != null) {
            try {
                callback.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void cleanup() {
        closeCallback(onLongClickCallback);
        onLongClickCallback = null;
    }

    public String getLink(String pluginId, String prefix) {
        if (TextUtils.isEmpty(linkAlias) || TextUtils.isEmpty(pluginId)) {
            return null;
        }
        String settingPath = prefix == null ? linkAlias : prefix + ':' + linkAlias;
        return String.format("https://t.me/%s?%s=%s&%s=%s", "exteraSettings", "p", pluginId, "s", settingPath);
    }
}
