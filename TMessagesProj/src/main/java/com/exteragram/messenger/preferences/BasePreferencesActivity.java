package com.exteragram.messenger.preferences;

import android.content.Context;
import android.view.View;

import org.telegram.ui.Components.UniversalFragment;

public abstract class BasePreferencesActivity extends UniversalFragment {

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setAdaptiveBackground(listView);
        listView.setSections();
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        return view;
    }
}
