"""intents — IntentsManager: global before/after handlers for Telegram links,
deeplinks and custom intents.

    from intents import IntentsManager as IM


    def on_chat(id):
        print("Chat id:", id)


    handle = IM.new_global_before_handler(
        on_chat,
        scheme="tg",
        host="chat",
        required_path_args_names=["id"],
    )

Handlers are matched by filters (scheme/host/path/action/flags/type/categories)
and invoked with a context derived from the callback signature.  ``before``
handlers may stop the chain (and the original Java intent handling) by
returning ``True``.
"""
from __future__ import annotations

import inspect
import re
import secrets
import sys
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qsl, urlparse

from java import jclass
from traceback import format_exc

# The host dispatches intents explicitly via a Java bridge
# (com.exteragram.messenger.plugins.IntentsController). Resolve lazily so
# `import intents` never fails.
try:
    IntentsController = jclass("com.exteragram.messenger.plugins.IntentsController")
except Exception:
    IntentsController = None
XposedBridge = jclass("de.robv.android.xposed.XposedBridge")
XC_MethodHook = jclass("de.robv.android.xposed.XC_MethodHook")

_PATH_ARG_RE = re.compile(r"\{(\w+)\}")

# Standard names always available in the callback context.
_STANDARD_CONTEXT = ("intent", "scheme", "host", "path", "query_args",
                     "action", "flags", "type", "categories")

# App version gate: the intent engine needs Extera >= 12.6.4 (66999)
_INTENTS_MIN_VERSION = 66999

# Module-level gating.
_enabled = True


def enable():
    global _enabled
    _enabled = True


def disable():
    global _enabled
    _enabled = False


def isenabled() -> bool:
    return _enabled


class HandlerNotRegistered(Exception):
    """Raised when unhandling an unknown handler ID."""

    def __init__(self, hook_id: str):
        super().__init__("Handler '%s' not registered" % hook_id)
        self.hook_id = hook_id


class HandlerHandle:
    """Handle returned by the registration methods for unregistration."""

    def __init__(self, manager: "IntentsManager", handler_id: str):
        self._manager = manager
        self.handler_id = handler_id

    def unhandle(self):
        self._manager.unhandle(self.handler_id)


@dataclass
class _HandlerInfo:
    """Internal handler record."""

    handler_id: str
    callback: Callable
    filters: Dict[str, Any] = field(default_factory=dict)
    priority: int = 0
    hook_type: str = "before"  # "before" | "after"

    def build_context(self, intent, scheme, host, path, query_args,
                      action, flags, type_, categories) -> Dict[str, Any]:
        ctx = {
            "intent": intent,
            "scheme": scheme,
            "host": host,
            "path": path,
            "query_args": query_args,
            "action": action,
            "flags": flags,
            "type": type_,
            "categories": categories,
        }
        ctx.update(query_args or {})
        # path template variables, e.g. /user/{user_id}
        path_template = self.filters.get("path") or ""
        path_regex, param_names = _build_path_regex(path_template)
        if path and param_names:
            match = path_regex.match(path)
            if match:
                ctx.update({k: v for k, v in match.groupdict().items() if v is not None})
        return ctx


class _IntentsManagerMeta(type):
    """Routes class-level calls (``IM.new_global_before_handler(...)``) to the
    singleton instance, matching the documented usage."""

    _bound = {"new_global_before_handler", "new_global_after_handler",
              "unhandle", "dispatch_intent"}

    def __getattribute__(cls, name):
        if name in _IntentsManagerMeta._bound:
            instance = cls.getInstance()
            return getattr(instance, name)
        return super().__getattribute__(name)


class IntentsManager(metaclass=_IntentsManagerMeta):
    """Global intent handler registry for plugins.

    Plugins register ``before``/``after`` handlers with filter arguments;
    matching intents are dispatched to callbacks whose arguments are selected
    from the standard context + query args + path template variables.
    """

    HandlerNotRegistered = HandlerNotRegistered

    _instance: Optional["IntentsManager"] = None

    def __init_subclass__(cls, **kwargs):
        super().__init_subclass__(**kwargs)
        # route class-level handler registration to the singleton, matching
        # docs usage ``IM.new_global_before_handler(...)`` / ``IM.unhandle(...)``

    def __class_getitem__(cls, item):
        return cls.getInstance()

    def __init__(self):
        self._handlers: Dict[str, _HandlerInfo] = {}
        self._next_id = 0
        self._installed = False
        self._hooks: List[Any] = []

    @classmethod
    def getInstance(cls) -> "IntentsManager":
        if cls._instance is None:
            cls._instance = IntentsManager()
        return cls._instance

    def _generate_id(self) -> str:
        self._next_id += 1
        return "%d_%s" % (self._next_id, secrets.token_hex(8))

    # -- registration --------------------------------------------------------

    def new_global_before_handler(self, callback: Callable,
                                  scheme: Optional[str] = None,
                                  host: Optional[str] = None,
                                  path: Optional[str] = None,
                                  required_path_args_names: Optional[List[str]] = None,
                                  action: Optional[str] = None,
                                  whitelist_flags: Optional[List[int]] = None,
                                  blacklist_flags: Optional[List[int]] = None,
                                  type: Optional[str] = None,
                                  categories: Optional[List[str]] = None,
                                  priority: int = 0) -> HandlerHandle:
        """Registers a handler that runs before the original method."""
        return self._register(callback, "before", scheme=scheme, host=host,
                              path=path, required_path_args_names=required_path_args_names,
                              action=action, whitelist_flags=whitelist_flags,
                              blacklist_flags=blacklist_flags, type=type,
                              categories=categories, priority=priority)

    def new_global_after_handler(self, callback: Callable,
                                 scheme: Optional[str] = None,
                                 host: Optional[str] = None,
                                 path: Optional[str] = None,
                                 required_path_args_names: Optional[List[str]] = None,
                                 action: Optional[str] = None,
                                 whitelist_flags: Optional[List[int]] = None,
                                 blacklist_flags: Optional[List[int]] = None,
                                 type: Optional[str] = None,
                                 categories: Optional[List[str]] = None,
                                 priority: int = 0) -> HandlerHandle:
        """Registers a handler that runs after the original method."""
        return self._register(callback, "after", scheme=scheme, host=host,
                              path=path, required_path_args_names=required_path_args_names,
                              action=action, whitelist_flags=whitelist_flags,
                              blacklist_flags=blacklist_flags, type=type,
                              categories=categories, priority=priority)

    def _register(self, callback: Callable, hook_type: str, **filters) -> HandlerHandle:
        handler_id = self._generate_id()
        normalized = self._normalize_filters(filters)
        info = _HandlerInfo(handler_id=handler_id, callback=callback,
                            filters=normalized, priority=int(filters.get("priority") or 0),
                            hook_type=hook_type)
        self._handlers[handler_id] = info
        self._ensure_installed()
        return HandlerHandle(self, handler_id)

    def _normalize_filters(self, filters: Dict[str, Any]) -> Dict[str, Any]:
        normalized = dict(filters)
        path = normalized.get("path")
        if path and not str(path).startswith("/"):
            normalized["path"] = "/" + str(path)
        for key in ("whitelist_flags", "blacklist_flags"):
            value = normalized.get(key)
            if value is not None and not isinstance(value, list):
                normalized[key] = [value]
        return normalized

    # -- unregistration ------------------------------------------------------

    def unhandle(self, handler_id: str):
        """Removes a handler by id. Raises ``HandlerNotRegistered`` if missing."""
        if handler_id not in self._handlers:
            raise HandlerNotRegistered(handler_id)
        del self._handlers[handler_id]
        if not self._handlers:
            self._stop()

    # -- parse helper --------------------------------------------------------

    @staticmethod
    def parse(url: str) -> dict:
        """Parse a URL-like string into ``{scheme, host, path?, query?}``."""
        parsed = urlparse(str(url))
        result = {"scheme": parsed.scheme, "host": parsed.hostname or ""}
        if parsed.path:
            result["path"] = parsed.path
        if parsed.query:
            result["query"] = parsed.query
        return result

    # -- dispatch (called by the host hook) ----------------------------------

    def dispatch_intent(self, intent: Any, hook_type: str = "before") -> bool:
        """Dispatch an Android Intent to matching handlers.

        Returns True when a ``before`` handler requested to stop further
        handling.
        """
        if not isenabled():
            return False
        try:
            data = intent.getData() if intent is not None else None
        except Exception:
            data = None
        scheme = host = path = None
        if data is not None:
            try:
                scheme = data.getScheme()
                host = data.getHost()
                path = data.getPath()
            except Exception:
                pass
        try:
            action = intent.getAction()
            flags = intent.getFlags()
            type_ = intent.getType()
            categories = set(intent.getCategories() or [])
        except Exception:
            action = flags = type_ = None
            categories = set()
        query_args = {}
        if data is not None:
            query_args = self._extract_query_args(data)

        ordered = sorted(self._handlers.values(), key=lambda h: h.priority)
        for info in ordered:
            if info.hook_type != hook_type:
                continue
            if not self._matches(info, scheme, host, path, action, flags, type_, categories, query_args):
                continue
            ctx = info.build_context(intent, scheme, host, path, query_args,
                                     action, flags, type_, categories)
            result = self._invoke_callback(info.callback, ctx)
            if hook_type == "before" and result is True:
                return True
        return False

    # -- hook installation ---------------------------------------------------

    def _ensure_installed(self):
        if self._installed:
            return
        self._installed = True
        # Intents are dispatched explicitly from the Java host bridge, so no
        # Xposed hook is installed here.
        try:
            if IntentsController is None:
                return
        except Exception:
            pass

    def _stop(self):
        for hook in self._hooks:
            try:
                hook.unhook()
            except Exception:
                pass
        self._hooks.clear()
        self._installed = False

    # -- internals -----------------------------------------------------------

    @staticmethod
    def _extract_query_args(uri) -> Dict[str, str]:
        try:
            names = uri.getQueryParameterNames() or []
            return {name: uri.getQueryParameter(name) for name in names}
        except Exception:
            return {}

    @staticmethod
    def _matches(info: _HandlerInfo, scheme, host, path, action, flags, type_,
                 categories, query_args: Optional[Dict[str, str]] = None) -> bool:
        f = info.filters
        if f.get("scheme") and scheme != f["scheme"]:
            return False
        if f.get("host") and host != f["host"]:
            return False
        if f.get("path"):
            path_regex, _names = _build_path_regex(f["path"])
            if not path or not path_regex.match(str(path)):
                return False
        required = f.get("required_path_args_names")
        if required:
            query_args = query_args or {}
            for name in required:
                if name not in query_args:
                    return False
        if f.get("action") and action != f["action"]:
            return False
        whitelist = f.get("whitelist_flags") or []
        if whitelist and not (flags and all(flags & flag == flag for flag in whitelist)):
            return False
        blacklist = f.get("blacklist_flags") or []
        if blacklist and flags and any(flags & flag == flag for flag in blacklist):
            return False
        if f.get("type") and type_ != f["type"]:
            return False
        if f.get("categories"):
            if not categories or not set(f["categories"]).issubset(categories):
                return False
        return True

    @staticmethod
    def _invoke_callback(callback: Callable, ctx: Dict[str, Any]) -> Any:
        """Call the callback with only the arguments it asks for.

        * named standard/query/path args are passed by keyword
        * ``*args`` receives the standard positional bundle
        * ``**kwargs`` receives the full available named context
        """
        try:
            sig = inspect.signature(callback)
        except (TypeError, ValueError):
            try:
                return callback(ctx)
            except TypeError:
                return callback()

        args = []
        kwargs = {}
        has_var_kwargs = False
        has_var_args = False
        consumed = set()
        for name, param in sig.parameters.items():
            kind = param.kind
            if kind is param.VAR_KEYWORD:
                has_var_kwargs = True
                continue
            if kind is param.VAR_POSITIONAL:
                has_var_args = True
                continue
            if name in ctx:
                if kind is param.POSITIONAL_ONLY:
                    args.append(ctx[name])
                elif kind is param.POSITIONAL_OR_KEYWORD:
                    kwargs[name] = ctx[name]
                elif kind is param.KEYWORD_ONLY:
                    kwargs[name] = ctx[name]
                consumed.add(name)

        if has_var_args:
            args.extend(tuple(ctx.get(name) for name in _STANDARD_CONTEXT))
        if has_var_kwargs:
            for name, value in ctx.items():
                if name not in consumed:
                    kwargs[name] = value

        try:
            return callback(*args, **kwargs)
        except TypeError:
            try:
                return callback(ctx)
            except TypeError:
                return callback()

    @staticmethod
    def _parse_intent_template(template: str) -> Tuple[re.Pattern, List[str]]:
        return _build_path_regex(template)


def _build_path_regex(path_template: str) -> Tuple[re.Pattern, List[str]]:
    """Build a regex from a path template like ``/user/{user_id}``.

    Each ``{name}`` becomes a named capturing group so matched values can be
    extracted back into the callback context.
    """
    param_names: List[str] = []
    pattern_parts: List[str] = []
    template = str(path_template or "")
    for part in re.split(_PATH_ARG_RE, template):
        if re.match(r"^\w+$", part) and part in template:
            param_names.append(part)
            pattern_parts.append(r"(?P<%s>[^/]+)" % part)
        else:
            pattern_parts.append(re.escape(part))
    regex = re.compile("^" + "".join(pattern_parts) + "$")
    return regex, param_names


class _HostHook:
    """Xposed hook adapter installed on the host intent handling method."""

    def __init__(self, manager: IntentsManager):
        self._manager = manager

    def before_hooked_method(self, param):
        try:
            stop = self._manager.dispatch_intent(param.thisObject, "before")
            if stop:
                param.setResult(True)
        except Exception:
            format_exc()

    def after_hooked_method(self, param):
        try:
            self._manager.dispatch_intent(param.thisObject, "after")
        except Exception:
            format_exc()


# singleton alias
_intents_manager = IntentsManager.getInstance()


def get_intents_manager() -> IntentsManager:
    return IntentsManager.getInstance()
