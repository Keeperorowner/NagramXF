package org.telegram.ui;

import android.content.Context;

import androidx.annotation.NonNull;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

public class MainTabsConfigManager {

    public enum TabType {
        CHATS,
        CONTACTS,
        SETTINGS,
        CALLS,
        PROFILE
    }

    public static class TabState {
        public TabType type;
        public boolean enabled;

        public TabState(TabType type, boolean enabled) {
            this.type = type;
            this.enabled = enabled;
        }

        public TabState(TabState other) {
            this(other.type, other.enabled);
        }
    }

    private static final String DEFAULT_ORDER = "CHATS,CONTACTS,SETTINGS,!CALLS,PROFILE";

    @NonNull
    public static ArrayList<TabState> getAllTabs() {
        String value = NaConfig.INSTANCE.getMainTabsOrder().String();
        if (value == null || value.trim().isEmpty()) {
            value = DEFAULT_ORDER;
        }

        ArrayList<TabState> result = new ArrayList<>();
        EnumSet<TabType> missing = EnumSet.allOf(TabType.class);

        String[] parts = value.split(",");
        for (String rawPart : parts) {
            String part = rawPart == null ? "" : rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            boolean enabled = !part.startsWith("!");
            String typeName = enabled ? part : part.substring(1);
            if ("CALLS_SETTINGS".equals(typeName)) {
                addTabState(result, missing, TabType.SETTINGS, enabled);
                continue;
            }
            try {
                TabType type = TabType.valueOf(typeName);
                addTabState(result, missing, type, enabled);
            } catch (Exception ignore) {
            }
        }

        for (TabType tabType : TabType.values()) {
            if (missing.contains(tabType)) {
                result.add(new TabState(tabType, getDefaultEnabled(tabType)));
            }
        }

        if (NaConfig.INSTANCE.getMainTabsHideContacts().Bool()) {
            setTabEnabled(result, TabType.CONTACTS, false);
        }

        ensureChatsEnabled(result);
        ensureChatsPinnedForDrawer(result);
        ensureAtLeastOneEnabled(result);
        return result;
    }

    @NonNull
    public static ArrayList<TabState> getEnabledTabs() {
        ArrayList<TabState> all = getAllTabs();
        ArrayList<TabState> enabled = new ArrayList<>();
        for (TabState state : all) {
            if (state.enabled) {
                enabled.add(new TabState(state));
            }
        }
        if (enabled.isEmpty()) {
            enabled.add(new TabState(TabType.CHATS, true));
        }
        return enabled;
    }

    @NonNull
    public static ArrayList<TabState> copyTabs(List<TabState> tabs) {
        ArrayList<TabState> result = new ArrayList<>();
        for (TabState state : tabs) {
            result.add(new TabState(state));
        }
        return result;
    }

    public static void saveTabs(List<TabState> tabs) {
        ArrayList<TabState> copy = copyTabs(tabs);
        ensureChatsEnabled(copy);
        ensureChatsPinnedForDrawer(copy);
        ensureAtLeastOneEnabled(copy);

        StringBuilder order = new StringBuilder();
        for (int i = 0; i < copy.size(); i++) {
            TabState state = copy.get(i);
            if (!state.enabled) {
                order.append('!');
            }
            order.append(state.type.name());
            if (i != copy.size() - 1) {
                order.append(',');
            }
        }
        NaConfig.INSTANCE.getMainTabsOrder().setConfigString(order.toString());
        NaConfig.INSTANCE.getMainTabsHideContacts().setConfigBool(!isTabEnabled(copy, TabType.CONTACTS));
    }

    public static int findTabIndex(List<TabState> tabs, TabType type) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).type == type) {
                return i;
            }
        }
        return -1;
    }

    public static int toPosition(TabType type) {
        return switch (type) {
            case CHATS -> 0;
            case CONTACTS -> 1;
            case SETTINGS -> 2;
            case CALLS -> 3;
            case PROFILE -> 4;
        };
    }

    public static boolean isTabEnabled(TabType type) {
        for (TabState state : getAllTabs()) {
            if (state.type == type) {
                return state.enabled;
            }
        }
        return false;
    }

    @NonNull
    public static GlassTabView createTabView(
        Context context,
        Theme.ResourcesProvider resourceProvider,
        int currentAccount,
        TabType type,
        boolean fromSettings
    ) {
        GlassTabView tabView = switch (type) {
            case CHATS -> GlassTabView.createMainTab(
                context,
                resourceProvider,
                GlassTabView.TabAnimation.CHATS,
                R.string.MainTabsChats
            );
            case CONTACTS -> GlassTabView.createMainTab(
                context,
                resourceProvider,
                GlassTabView.TabAnimation.CONTACTS,
                R.string.MainTabsContacts
            );
            case SETTINGS -> GlassTabView.createMainTab(
                context,
                resourceProvider,
                GlassTabView.TabAnimation.SETTINGS,
                R.string.Settings
            );
            case CALLS -> GlassTabView.createMainTab(
                context,
                resourceProvider,
                GlassTabView.TabAnimation.CALLS,
                R.string.MainTabsCalls
            );
            case PROFILE -> GlassTabView.createAvatar(
                context,
                resourceProvider,
                currentAccount,
                R.string.MainTabsProfile
            );
        };
        tabView.setTitleVisible(!NaConfig.INSTANCE.getMainTabsHideTitles().Bool());
        return tabView;
    }

    private static boolean isTabEnabled(List<TabState> tabs, TabType type) {
        for (TabState state : tabs) {
            if (state.type == type) {
                return state.enabled;
            }
        }
        return false;
    }

    private static void setTabEnabled(List<TabState> tabs, TabType type, boolean enabled) {
        for (TabState state : tabs) {
            if (state.type == type) {
                state.enabled = enabled;
                return;
            }
        }
    }

    private static void ensureAtLeastOneEnabled(List<TabState> tabs) {
        for (TabState state : tabs) {
            if (state.enabled) {
                return;
            }
        }

        for (TabState state : tabs) {
            if (state.type == TabType.CHATS) {
                state.enabled = true;
                return;
            }
        }

        if (!tabs.isEmpty()) {
            tabs.get(0).enabled = true;
        } else {
            tabs.add(new TabState(TabType.CHATS, true));
        }
    }

    private static boolean getDefaultEnabled(TabType type) {
        return type != TabType.CALLS;
    }

    private static void addTabState(ArrayList<TabState> result, EnumSet<TabType> missing, TabType type, boolean enabled) {
        if (!missing.contains(type)) {
            return;
        }
        result.add(new TabState(type, enabled));
        missing.remove(type);
    }

    private static void ensureChatsEnabled(List<TabState> tabs) {
        for (TabState state : tabs) {
            if (state.type == TabType.CHATS) {
                state.enabled = true;
                return;
            }
        }
        tabs.add(0, new TabState(TabType.CHATS, true));
    }

    private static void ensureChatsPinnedForDrawer(List<TabState> tabs) {
        if (!NekoConfig.navigationDrawerEnabled.Bool()) {
            return;
        }
        TabState chats = null;
        for (int i = 0; i < tabs.size(); i++) {
            TabState state = tabs.get(i);
            if (state.type == TabType.CHATS) {
                chats = state;
                tabs.remove(i);
                break;
            }
        }
        if (chats == null) {
            chats = new TabState(TabType.CHATS, true);
        }
        chats.enabled = true;
        tabs.add(0, chats);
    }
}
