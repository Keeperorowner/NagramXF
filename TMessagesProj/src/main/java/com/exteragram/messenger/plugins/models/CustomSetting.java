package com.exteragram.messenger.plugins.models;

import android.view.View;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;

import org.telegram.ui.Components.UItem;

public class CustomSetting extends SettingItem {
    public UItem item;
    public Factory<?> factory;
    public PyObject factoryArgs;
    public PyObject onClickCallback;
    public PyObject createSubFragmentCallback;

    private CustomSetting(PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        super("custom", null, onLongClickCallback, linkAlias);
        this.onClickCallback = onClickCallback;
        this.createSubFragmentCallback = createSubFragmentCallback;
    }

    public CustomSetting(UItem item, PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.item = item;
    }

    public CustomSetting(Factory<?> factory, PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        this(onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.factory = factory;
    }

    public CustomSetting(Factory<?> factory, PyObject factoryArgs, PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        this(factory, onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
        this.factoryArgs = factoryArgs;
    }

    public CustomSetting(View view, PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        this(UItem.asCustom(view), onClickCallback, createSubFragmentCallback, onLongClickCallback, linkAlias);
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(onClickCallback);
        closeCallback(createSubFragmentCallback);
        closeCallback(factoryArgs);
        onClickCallback = null;
        createSubFragmentCallback = null;
        factoryArgs = null;
    }

    public static abstract class Factory<V extends View> extends UItem.UItemFactory<V> {
        public boolean isShadowValue;
        public boolean isClickableValue = true;

        public UItem create(Plugin plugin, CustomSetting customSetting, PyObject factoryArgs) {
            return null;
        }

        public void onClick(Plugin plugin, UItem item, View view) {
        }

        public void onLongClick(Plugin plugin, UItem item, View view) {
        }

        @Override
        public boolean isShadow() {
            return isShadowValue;
        }

        @Override
        public boolean isClickable() {
            return isClickableValue;
        }
    }
}
