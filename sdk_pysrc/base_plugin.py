"""base_plugin — BasePlugin entry point + hook/menu/settings primitives.

The Java host (``PythonPluginsEngine``) finds a class inheriting ``BasePlugin``,
instantiates it with no args, then injects ``id``/``name``/``description``/...
via ``instance.put(...)`` and calls ``on_plugin_load``/``on_plugin_unload``.
Do not rely on ``self.id`` in ``__init__``.
"""
from __future__ import annotations

from java import jclass

from android_utils import log as _log

PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")
PluginsConstants = jclass("com.exteragram.messenger.plugins.PluginsConstants")
JavaHookFilter = jclass("com.exteragram.messenger.plugins.hooks.HookFilter")
PyMethodHook = jclass("com.exteragram.messenger.plugins.xposed.PyMethodHook")
PyMethodReplacement = jclass("com.exteragram.messenger.plugins.xposed.PyMethodReplacement")
XposedBridge = jclass("de.robv.android.xposed.XposedBridge")
ArrayList = jclass("java.util.ArrayList")

import plugin_settings


# ---- errors ----
class PluginError(Exception):
    def __init__(self, message, plugin_id):
        super().__init__("[%s] %s" % (plugin_id, message))
        self.message = message
        self.plugin_id = plugin_id


# ---- enums (string-backed so the Java host can read them) ----
class HookStrategy:
    DEFAULT = "DEFAULT"
    CANCEL = "CANCEL"
    MODIFY = "MODIFY"
    MODIFY_FINAL = "MODIFY_FINAL"


class AppEvent(str):
    def __new__(cls, value):
        return str.__new__(cls, value)

    START = "app_start"
    STOP = "app_stop"
    PAUSE = "app_pause"
    RESUME = "app_resume"


class MenuItemType:
    MESSAGE_CONTEXT_MENU = "message_context_menu"
    DRAWER_MENU = "drawer_menu"
    MAIN_MENU = "main_menu"
    CHAT_ACTION_MENU = "chat_action_menu"
    PROFILE_ACTION_MENU = "profile_action_menu"


class MenuItemData(dict):
    def __init__(self, menu_type, text, on_click, item_id=None, icon=None,
                 subtext=None, condition=None, priority=0):
        super().__init__()
        self["menu_type"] = menu_type
        self["text"] = text
        self["on_click"] = on_click
        self["item_id"] = item_id
        self["icon"] = icon
        self["subtext"] = subtext
        self["condition"] = condition
        self["priority"] = priority


class HookResult:
    """Returned by ``*_hook`` methods; consumed by the Java host via ``.get()``."""

    def __init__(self, strategy=HookStrategy.DEFAULT, request=None, response=None,
                 update=None, updates=None, params=None, error=None):
        self.strategy = strategy
        self.request = request
        self.response = response
        self.update = update
        self.updates = updates
        self.params = params
        self.error = error

    def get(self, key, default=None):
        return getattr(self, key, default)


# ---- HookFilter factory ----
def _make_filter(filter_type, arg_index=None, value=None, instance_of=None,
                 or_filters=None, condition=None, object_=None):
    f = JavaHookFilter(filter_type)
    if arg_index is not None:
        f.argIndex = int(arg_index)
    if value is not None:
        f.object = value
    if object_ is not None:
        f.object = object_
    if instance_of is not None:
        f.instanceOf = instance_of
    if condition is not None:
        f.mvelExpression = condition
    if or_filters is not None:
        arr = ArrayList()
        for item in or_filters:
            arr.add(item)
        f.orFilters = arr
    return f


class HookFilter:
    RESULT_IS_NULL = _make_filter("result_is_null")
    RESULT_IS_TRUE = _make_filter("result_is_true")
    RESULT_IS_FALSE = _make_filter("result_is_false")
    RESULT_NOT_NULL = _make_filter("result_not_null")

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return _make_filter("result_is_instance_of", instance_of=clazz)

    @staticmethod
    def ResultEqual(value):
        return _make_filter("result_equal", value=value)

    @staticmethod
    def ResultNotEqual(value):
        return _make_filter("result_not_equal", value=value)

    @staticmethod
    def ArgumentIsNull(index):
        return _make_filter("argument_is_null", arg_index=index)

    @staticmethod
    def ArgumentNotNull(index):
        return _make_filter("argument_not_null", arg_index=index)

    @staticmethod
    def ArgumentIsFalse(index):
        return _make_filter("argument_is_false", arg_index=index)

    @staticmethod
    def ArgumentIsTrue(index):
        return _make_filter("argument_is_true", arg_index=index)

    @staticmethod
    def ArgumentIsInstanceOf(index, clazz):
        return _make_filter("argument_is_instance_of", arg_index=index, instance_of=clazz)

    @staticmethod
    def ArgumentEqual(index, value):
        return _make_filter("argument_equal", arg_index=index, value=value)

    @staticmethod
    def ArgumentNotEqual(index, value):
        return _make_filter("argument_not_equal", arg_index=index, value=value)

    @staticmethod
    def Condition(condition, object=None):
        return _make_filter("condition", condition=condition, object_=object)

    @staticmethod
    def Or(*filters):
        return _make_filter("or", or_filters=list(filters))


def hook_filters(*filters):
    """Class-based filter decorator: ``@hook_filters(HookFilter.ArgumentIsNull(0))``."""

    def decorator(fn):
        fn.__hook_filters__ = list(filters)
        return fn

    return decorator


def fn_hook_filters(*filters):
    return hook_filters(*filters)


# ---- method hook bases ----
class MethodHook:
    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class XposedHook(MethodHook):
    """Convenience hook from plain callables: XposedHook(before=fn, after=fn)."""

    def __init__(self, before=None, after=None):
        self._before = before
        self._after = after

    def before_hooked_method(self, param):
        if self._before is not None:
            return self._before(param)
        return None

    def after_hooked_method(self, param):
        if self._after is not None:
            return self._after(param)
        return None


class MethodReplacement:
    def replace_hooked_method(self, param):
        return None


class BaseHook(MethodHook):
    def __init__(self, plugin, *, before=None, after=None, before_filters=None, after_filters=None):
        self.plugin = plugin
        self.before = before
        self.after = after
        self.before_filters = before_filters or []
        self.after_filters = after_filters or []

    def before_hooked_method(self, param):
        if self.before is not None:
            return self.before(param)
        return None

    def after_hooked_method(self, param):
        if self.after is not None:
            return self.after(param)
        return None


def _to_java_filters(filters):
    if not filters:
        return None
    arr = ArrayList()
    for f in filters:
        arr.add(f)
    return arr


def _method_hook_filters(callback, name):
    if callback is None:
        return None
    try:
        attr = getattr(callback, name)
    except Exception:
        return None
    func = getattr(attr, "__func__", attr)
    return getattr(func, "__hook_filters__", None)


def _build_py_hook(plugin_id, callback, priority):
    """Build the Java-side PyMethodHook/PyMethodReplacement for a callback."""
    if callback is None:
        return None
    if isinstance(callback, MethodReplacement):
        return PyMethodReplacement(plugin_id, callback, int(priority))
    has_before = callable(getattr(type(callback), "before_hooked_method", None))
    has_after = callable(getattr(type(callback), "after_hooked_method", None))
    hook = PyMethodHook(plugin_id, callback, int(priority), has_before, has_after)
    before_filters = list(getattr(callback, "before_filters", None) or [])
    after_filters = list(getattr(callback, "after_filters", None) or [])
    before_filters += list(_method_hook_filters(callback, "before_hooked_method") or [])
    after_filters += list(_method_hook_filters(callback, "after_hooked_method") or [])
    if before_filters:
        hook.setBeforeHookedFilters(_to_java_filters(before_filters))
    if after_filters:
        hook.setAfterHookedFilters(_to_java_filters(after_filters))
    return hook


def _install_hook(plugin_id, member, callback, priority):
    if callback is None or member is None:
        return None
    try:
        hook = _build_py_hook(plugin_id, callback, priority)
        if hook is None:
            return None
        import time
        _t0 = time.perf_counter()
        unhook = XposedBridge.hookMethod(member, hook)
        _t1 = (time.perf_counter() - _t0) * 1000.0
        if _t1 > 50.0:
            _name = getattr(member, "getName", lambda: str(member))()
            _log("[%s] hook_method(%s) took %.1fms" % (plugin_id, _name, _t1))
        PluginsController.getInstance().addXposedHook(plugin_id, unhook)
        return unhook
    except Exception as e:
        _log("[%s] hook_method failed: %s" % (plugin_id, e))
        return None


# ---- plugin base ----
class BasePlugin:
    def __init__(self):
        self.id = None
        self.name = None
        self.enabled = False
        self.initialized = False

    # lifecycle
    def on_plugin_load(self):
        pass

    def on_plugin_unload(self):
        pass

    def on_app_event(self, event_type):
        pass

    # settings
    def get_setting(self, key, default=None):
        return plugin_settings.get_setting(self.id, key, default)

    def set_setting(self, key, value, reload_settings=False):
        result = plugin_settings.set_setting(self.id, key, value)
        if reload_settings:
            self.reload_settings()
        return result

    def export_settings(self):
        return plugin_settings.get_all_settings(self.id)

    def import_settings(self, settings, reload_settings=True):
        result = plugin_settings.set_all_settings(self.id, settings)
        if reload_settings:
            self.reload_settings()
        return result

    def reload_settings(self):
        try:
            PluginsController.getInstance().loadPluginSettings(self.id)
        except Exception:
            pass

    def create_settings(self):
        return []

    # logging
    def log(self, message):
        _log("[%s] %s" % (self.id, message))

    # client
    def client(self, account=None):
        from client_utils import AccountClient, get_hook_account
        if account is None:
            account = get_hook_account()
        return AccountClient(account)

    # event hooks (plugins override; return HookResult)
    def pre_request_hook(self, request_name, account, request):
        return HookResult()

    def post_request_hook(self, request_name, account, response, error):
        return HookResult()

    def on_update_hook(self, update_name, account, update):
        return HookResult()

    def on_updates_hook(self, container_name, account, updates):
        return HookResult()

    def on_send_message_hook(self, account, params):
        return HookResult()

    # hook registration
    def add_hook(self, name, match_substring=False, priority=0):
        try:
            PluginsController.getInstance().addEventHook(self.id, name, bool(match_substring), int(priority))
        except Exception:
            pass

    def add_on_send_message_hook(self, priority=0):
        self.add_hook("send_message_hook", False, priority)

    def remove_hook(self, name):
        try:
            PluginsController.getInstance().removeEventHook(self.id, name)
        except Exception:
            pass

    # xposed method hooks
    def hook_method(self, method_or_constructor, xposed_hook=None, priority=0, *,
                    before=None, after=None, before_filters=None, after_filters=None):
        callback = xposed_hook
        if callback is None:
            callback = BaseHook(self, before=before, after=after,
                                before_filters=before_filters, after_filters=after_filters)
        return _install_hook(self.id, method_or_constructor, callback, priority)

    def hook_all_methods(self, hook_class, method_name, xposed_hook=None, priority=0, *,
                         before=None, after=None, before_filters=None, after_filters=None):
        callback = xposed_hook
        if callback is None:
            callback = BaseHook(self, before=before, after=after,
                                before_filters=before_filters, after_filters=after_filters)
        unhooks = []
        try:
            methods = hook_class.getDeclaredMethods()
            for i in range(len(methods)):
                method = methods[i]
                if method.getName() == method_name:
                    try:
                        method.setAccessible(True)
                    except Exception:
                        pass
                    unhook = self.hook_method(method, callback, priority,
                                              before=before, after=after,
                                              before_filters=before_filters, after_filters=after_filters)
                    if unhook is not None:
                        unhooks.append(unhook)
        except Exception as e:
            self.log("hook_all_methods(%s) failed: %s" % (method_name, e))
        return unhooks

    def hook_all_constructors(self, hook_class, xposed_hook=None, priority=0, *,
                              before=None, after=None, before_filters=None, after_filters=None):
        callback = xposed_hook
        if callback is None:
            callback = BaseHook(self, before=before, after=after,
                                before_filters=before_filters, after_filters=after_filters)
        unhooks = []
        try:
            constructors = hook_class.getConstructors()
            for i in range(len(constructors)):
                constructor = constructors[i]
                try:
                    constructor.setAccessible(True)
                except Exception:
                    pass
                unhook = self.hook_method(constructor, callback, priority,
                                          before=before, after=after,
                                          before_filters=before_filters, after_filters=after_filters)
                if unhook is not None:
                    unhooks.append(unhook)
        except Exception as e:
            self.log("hook_all_constructors failed: %s" % e)
        return unhooks

    def unhook_method(self, unhook):
        if unhook is None:
            return
        try:
            PluginsController.getInstance().removeXposedHook(self.id, unhook)
        except Exception:
            pass

    # menu items
    def add_menu_item(self, menu_item_data):
        try:
            return PluginsController.getInstance().addMenuItem(self.id, menu_item_data)
        except Exception:
            return None

    def remove_menu_item(self, item_id):
        try:
            return PluginsController.getInstance().removeMenuItem(self.id, item_id)
        except Exception:
            return False

    # file hooks (delegate to file_utils.FilesController)
    def add_file_hook(self, file_info):
        from file_utils import FilesController
        try:
            return FilesController.register(file_info)
        except Exception as e:
            self.log("add_file_hook failed: %s" % e)
            return None

    def remove_file_hook(self, ext, secret=None):
        from file_utils import FilesController
        try:
            FilesController.unregister(ext, secret)
            return True
        except Exception as e:
            self.log("remove_file_hook failed: %s" % e)
            return False

    # intent hooks (delegate to intents.IntentsManager)
    def add_intent_hook(self, info, type):
        from intents import IntentsManager
        try:
            hook_type = str(type).lower()
            kwargs = {}
            for key in ("scheme", "host", "path", "required_path_args_names",
                        "action", "whitelist_flags", "blacklist_flags", "type",
                        "categories", "priority"):
                if isinstance(info, dict) and key in info:
                    kwargs[key] = info[key]
            callback = info.get("callback") if isinstance(info, dict) else getattr(info, "callback", None)
            priority = kwargs.pop("priority", 0) or 0
            if hook_type == "before":
                return IntentsManager.new_global_before_handler(callback, priority=priority, **kwargs)
            if hook_type == "after":
                return IntentsManager.new_global_after_handler(callback, priority=priority, **kwargs)
            self.log("Could not determine hook type for %s" % type)
            return None
        except Exception as e:
            self.log("add_intent_hook failed: %s" % e)
            return None

    def remove_intent_hook(self, handler_id):
        from intents import IntentsManager
        try:
            IntentsManager.unhandle(handler_id)
            return True
        except Exception as e:
            self.log("remove_intent_hook failed: %s" % e)
            return False
