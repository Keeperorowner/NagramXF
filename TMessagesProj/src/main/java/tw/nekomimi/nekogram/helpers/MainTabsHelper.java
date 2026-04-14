package tw.nekomimi.nekogram.helpers;

import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.MainTabsConfigManager;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

public final class MainTabsHelper {
    public static final int MAIN_TABS_HEIGHT = 56;
    public static final int MAIN_TABS_MARGIN = 16;
    public static final int MAIN_TABS_MARGIN_COMPACT = 4;
    public static final int FILTER_TABS_HEIGHT = 36;
    public static final int TAB_WIDTH = 80;
    public static final int TAB_PADDING = 4;
    public static final int BOTTOM_BAR_MODE_SHOW = 0;
    public static final int BOTTOM_BAR_MODE_HIDE = 1;
    public static final int BOTTOM_BAR_MODE_FLOATING = 2;

    private MainTabsHelper() {
    }

    public static boolean isMainTabsHideTitleStyle() {
        return NaConfig.INSTANCE.getMainTabsHideTitles().Bool();
    }

    public static int getMainTabsHeight() {
        return MAIN_TABS_HEIGHT;
    }

    public static int getMainTabsMargin() {
        return MAIN_TABS_MARGIN;
    }

    public static int getMainTabsHeightWithMargins() {
        return getMainTabsHeight() + getMainTabsMargin() * 2;
    }

    public static boolean isContactsTabHidden() {
        return getContactsPosition() < 0;
    }

    public static int getChatsPosition() {
        return getTabPosition(MainTabsConfigManager.TabType.CHATS, 0);
    }

    public static int getContactsPosition() {
        return getTabPosition(MainTabsConfigManager.TabType.CONTACTS, -1);
    }

    public static int getCallsOrSettingsPosition() {
        int fallback = Math.min(1, Math.max(0, getFragmentsCount() - 1));
        int settingsPosition = getTabPosition(MainTabsConfigManager.TabType.SETTINGS, -1);
        if (settingsPosition >= 0) {
            return settingsPosition;
        }
        return getTabPosition(MainTabsConfigManager.TabType.CALLS, fallback);
    }

    public static int getProfilePosition() {
        return getTabPosition(MainTabsConfigManager.TabType.PROFILE, Math.max(0, getFragmentsCount() - 1));
    }

    public static int getFragmentsCount() {
        ArrayList<MainTabsConfigManager.TabState> enabledTabs = MainTabsConfigManager.getEnabledTabs();
        return Math.max(1, enabledTabs.size());
    }

    public static int getTabsViewWidth() {
        return TAB_WIDTH * getFragmentsCount() + (getMainTabsMargin() + TAB_PADDING) * 2;
    }

    public static int getBottomBarDisplayMode() {
        return NaConfig.INSTANCE.getMainTabsDisplayMode().Int();
    }

    public static void setBottomBarDisplayMode(int mode) {
        NaConfig.INSTANCE.getMainTabsDisplayMode().setConfigInt(mode);
    }

    private static int getTabPosition(MainTabsConfigManager.TabType type, int fallback) {
        int index = MainTabsConfigManager.findTabIndex(MainTabsConfigManager.getEnabledTabs(), type);
        return index >= 0 ? index : fallback;
    }
}
