package org.telegram.ui;

import android.content.Context;

import androidx.annotation.NonNull;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.glass.GlassTabView;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

public class MainTabsConfigManager {

    public enum TabType {
        CHATS,
        CONTACTS,
        CALLS_SETTINGS,
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

    private static final String DEFAULT_ORDER = "CHATS,CONTACTS,CALLS_SETTINGS,PROFILE";

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
            try {
                TabType type = TabType.valueOf(typeName);
                result.add(new TabState(type, enabled));
                missing.remove(type);
            } catch (Exception ignore) {
            }
        }

        for (TabType tabType : TabType.values()) {
            if (missing.contains(tabType)) {
                result.add(new TabState(tabType, true));
            }
        }

        if (NaConfig.INSTANCE.getMainTabsHideContacts().Bool()) {
            setTabEnabled(result, TabType.CONTACTS, false);
        }

        ensureChatsEnabled(result);
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
            case CALLS_SETTINGS -> 2;
            case PROFILE -> 3;
        };
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
            case CALLS_SETTINGS -> {
                if (UserConfig.getInstance(currentAccount).showCallsTab) {
                    yield GlassTabView.createMainTab(
                        context,
                        resourceProvider,
                        GlassTabView.TabAnimation.CALLS,
                        R.string.MainTabsCalls
                    );
                } else {
                    yield GlassTabView.createMainTab(
                        context,
                        resourceProvider,
                        GlassTabView.TabAnimation.SETTINGS,
                        R.string.Settings
                    );
                }
            }
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

    private static void ensureChatsEnabled(List<TabState> tabs) {
        for (TabState state : tabs) {
            if (state.type == TabType.CHATS) {
                state.enabled = true;
                return;
            }
        }
        tabs.add(0, new TabState(TabType.CHATS, true));
    }
}
