package com.exteragram.messenger.plugins;

public final class PluginsConstants {
    public static final String APP_PAUSE = "app_pause";
    public static final String APP_RESUME = "app_resume";
    public static final String APP_START = "app_start";
    public static final String APP_STOP = "app_stop";
    public static final String CREATE_SETTINGS = "create_settings";
    public static final String ERROR = "error";
    public static final String ON_APP_EVENT = "on_app_event";
    public static final String ON_PLUGIN_LOAD = "on_plugin_load";
    public static final String ON_PLUGIN_UNLOAD = "on_plugin_unload";
    public static final String PARAMS = "params";
    public static final String PLUGINS = "plugins";
    public static final String PLUGINS_EXT = ".plugin";
    public static final String PLUGINS_SDK = "plugins_sdk";
    public static final String PYSDK_IMY = "pyPluginsSdk.imy";
    public static final String PYTHON = "python";
    public static final String REQUEST = "request";
    public static final String RESPONSE = "response";
    public static final String SEND_MESSAGE_HOOK = "send_message_hook";
    public static final String STRATEGY = "strategy";
    public static final String UPDATE = "update";
    public static final String UPDATES = "updates";

    private PluginsConstants() {
    }

    public static final class DevServer {
        public static final String CLASS = "DevServer";
        public static final String MODULE = "dev_server";
        public static final String START_SERVER = "start_server";
        public static final String STOP_SERVER = "stop_server";
    }

    public static final class HookFilterTypes {
        public static final String ARGUMENT_EQUAL = "argument_equal";
        public static final String ARGUMENT_IS_FALSE = "argument_is_false";
        public static final String ARGUMENT_IS_INSTANCE_OF = "argument_is_instance_of";
        public static final String ARGUMENT_IS_NULL = "argument_is_null";
        public static final String ARGUMENT_IS_TRUE = "argument_is_true";
        public static final String ARGUMENT_NOT_EQUAL = "argument_not_equal";
        public static final String ARGUMENT_NOT_NULL = "argument_not_null";
        public static final String CONDITION = "condition";
        public static final String OR = "or";
        public static final String RESULT_EQUAL = "result_equal";
        public static final String RESULT_IS_FALSE = "result_is_false";
        public static final String RESULT_IS_INSTANCE_OF = "result_is_instance_of";
        public static final String RESULT_IS_NULL = "result_is_null";
        public static final String RESULT_IS_TRUE = "result_is_true";
        public static final String RESULT_NOT_EQUAL = "result_not_equal";
        public static final String RESULT_NOT_NULL = "result_not_null";
    }

    public static final class MenuItemProperties {
        public static final String CONDITION = "condition";
        public static final String ICON = "icon";
        public static final String ITEM_ID = "item_id";
        public static final String MENU_TYPE = "menu_type";
        public static final String ON_CLICK = "on_click";
        public static final String PRIORITY = "priority";
        public static final String SUBTEXT = "subtext";
        public static final String TEXT = "text";
    }

    public static final class MenuItemTypes {
        public static final String CHAT_ACTION_MENU = "chat_action_menu";
        public static final String DRAWER_MENU = "drawer_menu";
        public static final String MAIN_MENU = "main_menu";
        public static final String MESSAGE_CONTEXT_MENU = "message_context_menu";
        public static final String PROFILE_ACTION_MENU = "profile_action_menu";
    }

    public static final class Settings {
        public static final String ACCENT = "accent";
        public static final String CREATE_SUB_FRAGMENT = "create_sub_fragment";
        public static final String DEFAULT = "default";
        public static final String FACTORY = "factory";
        public static final String FACTORY_ARGS = "factory_args";
        public static final String HINT = "hint";
        public static final String ICON = "icon";
        public static final String ITEM = "item";
        public static final String ITEMS = "items";
        public static final String KEY = "key";
        public static final String LINK_ALIAS = "link_alias";
        public static final String MASK = "mask";
        public static final String MAX_LENGTH = "max_length";
        public static final String MULTILINE = "multiline";
        public static final String ON_CHANGE = "on_change";
        public static final String ON_CLICK = "on_click";
        public static final String ON_LONG_CLICK = "on_long_click";
        public static final String RED = "red";
        public static final String SUBTEXT = "subtext";
        public static final String TEXT = "text";
        public static final String TYPE = "type";
        public static final String TYPE_CUSTOM = "custom";
        public static final String TYPE_DIVIDER = "divider";
        public static final String TYPE_EDIT_TEXT = "edit_text";
        public static final String TYPE_HEADER = "header";
        public static final String TYPE_INPUT = "input";
        public static final String TYPE_SELECTOR = "selector";
        public static final String TYPE_SWITCH = "switch";
        public static final String TYPE_TEXT = "text";
        public static final String VIEW = "view";
    }

    public static final class Strategy {
        public static final String CANCEL = "CANCEL";
        public static final String DEFAULT = "DEFAULT";
        public static final String MODIFY = "MODIFY";
        public static final String MODIFY_FINAL = "MODIFY_FINAL";
    }

    public static final class Xposed {
        public static final String AFTER_HOOKED_METHOD = "after_hooked_method";
        public static final String BEFORE_HOOKED_METHOD = "before_hooked_method";
        public static final String HOOK_FILTERS = "__hook_filters__";
        public static final String REPLACE_HOOKED_METHOD = "replace_hooked_method";
    }
}
