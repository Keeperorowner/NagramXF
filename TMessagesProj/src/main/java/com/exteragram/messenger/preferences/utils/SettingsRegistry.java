package com.exteragram.messenger.preferences.utils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

public final class SettingsRegistry {
    private static final SettingsRegistry INSTANCE = new SettingsRegistry();

    private SettingsRegistry() {
    }

    public static SettingsRegistry getInstance() {
        return INSTANCE;
    }

    public void onSettingNotFound() {
        onSettingNotFound(LaunchActivity.getSafeLastFragment());
    }

    public void onSettingNotFound(BaseFragment fragment) {
        if (fragment != null) {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.NoSuchSetting))
                    .show();
        }
    }
}
