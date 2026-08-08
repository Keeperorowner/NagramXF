package com.exteragram.messenger.plugins.ui.components.templates;

import android.content.Context;
import android.view.View;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class UniversalFragment extends org.telegram.ui.Components.UniversalFragment {
    private UniversalFragmentDelegate delegate;

    public UniversalFragment() {
    }

    public UniversalFragment(UniversalFragmentDelegate delegate) {
        this.delegate = delegate;
    }

    public void setDelegate(UniversalFragmentDelegate delegate) {
        this.delegate = delegate;
    }

    public UniversalFragmentDelegate getDelegate() {
        return delegate;
    }

    @Override
    public View createView(Context context) {
        View view = delegate != null ? delegate.beforeCreateView() : null;
        if (view != null) {
            return view;
        }
        View createdView = super.createView(context);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (delegate != null) {
                    delegate.onMenuItemClick(id);
                }
            }
        });
        View replacedView = delegate != null ? delegate.afterCreateView(createdView) : null;
        return replacedView == null ? createdView : replacedView;
    }

    public ActionBarMenu getActionBarMenu() {
        return actionBar.createMenu();
    }

    public void setTitle(CharSequence title, boolean animated, long duration) {
        if (animated) {
            actionBar.setTitleAnimated(title, false, duration <= 0 ? 300 : duration);
        } else {
            actionBar.setTitle(title);
        }
    }

    @Override
    public CharSequence getTitle() {
        return delegate != null ? delegate.getTitle() : null;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (delegate != null) {
            delegate.fillItems(items, adapter);
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (delegate != null) {
            delegate.onClick(item, view, position, x, y);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return delegate != null && delegate.onLongClick(item, view, position, x, y);
    }

    @Override
    public boolean onFragmentCreate() {
        if (delegate != null) {
            delegate.onFragmentCreate();
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (delegate != null) {
            delegate.onFragmentDestroy();
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        Boolean handled = delegate != null ? delegate.onBackPressed() : null;
        if (handled != null) {
            return !handled;
        }
        return super.onBackPressed(invoked);
    }

    public interface UniversalFragmentDelegate {
        default View beforeCreateView() { return null; }
        default View afterCreateView(View view) { return view; }
        default void onFragmentCreate() {}
        default void onFragmentDestroy() {}
        default Boolean onBackPressed() { return null; }
        default CharSequence getTitle() { return null; }
        default void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {}
        default void onClick(UItem item, View view, int position, float x, float y) {}
        default boolean onLongClick(UItem item, View view, int position, float x, float y) { return false; }
        default void onMenuItemClick(int id) {}
    }
}
