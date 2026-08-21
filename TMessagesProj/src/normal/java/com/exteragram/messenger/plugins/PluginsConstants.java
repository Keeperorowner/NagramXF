package com.exteragram.messenger.plugins;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public final class PluginsConstants {

    public static final String APP_PAUSE = "app_pause";
    public static final String APP_RESUME = "app_resume";
    public static final String APP_START = "app_start";
    public static final String APP_STOP = "app_stop";

    public static final class MenuItemTypes {
        public static final String CHAT_ACTION_MENU = "chat_action_menu";
        public static final String MAIN_MENU = "main_menu";
        public static final String MESSAGE_CONTEXT_MENU = "message_context_menu";
        public static final String PROFILE_ACTION_MENU = "profile_action_menu";

        private MenuItemTypes() {
        }
    }

    private PluginsConstants() {
    }
}
