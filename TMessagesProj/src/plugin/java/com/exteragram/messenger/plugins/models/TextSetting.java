package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class TextSetting extends SettingItem {
    public String text;
    public String subtext;
    public boolean accent;
    public boolean red;
    public PyObject onClickCallback;
    public PyObject createSubFragmentCallback;

    public TextSetting(String text, String subtext, String icon, boolean accent, boolean red, PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        super("text", icon, onLongClickCallback, linkAlias);
        this.text = text;
        this.subtext = subtext;
        this.accent = accent;
        this.red = red;
        this.onClickCallback = onClickCallback;
        this.createSubFragmentCallback = createSubFragmentCallback;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(onClickCallback);
        closeCallback(createSubFragmentCallback);
        onClickCallback = null;
        createSubFragmentCallback = null;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getSubtext() {
        return subtext;
    }

    public void setSubtext(String subtext) {
        this.subtext = subtext;
    }

    public boolean getAccent() {
        return accent;
    }

    public void setAccent(boolean accent) {
        this.accent = accent;
    }

    public boolean getRed() {
        return red;
    }

    public void setRed(boolean red) {
        this.red = red;
    }

    public PyObject getOnClickCallback() {
        return onClickCallback;
    }

    public void setOnClickCallback(PyObject onClickCallback) {
        this.onClickCallback = onClickCallback;
    }

    public PyObject getCreateSubFragmentCallback() {
        return createSubFragmentCallback;
    }

    public void setCreateSubFragmentCallback(PyObject createSubFragmentCallback) {
        this.createSubFragmentCallback = createSubFragmentCallback;
    }
}
