"""log_utils — plugin logger.

Every plugin gets a :class:`Logger` as ``self.logger``. Entries go to logcat under
``plugin.<plugin id>`` and start with the place they were logged from::

    I plugin.demo: [demo.py:on_plugin_load:12] Loaded version 1.0

Levels map onto Android log priorities, so ``adb logcat *:E`` shows only errors.
The default level is DEBUG while developer mode is on and INFO otherwise; a
logger can override it with ``logger.level = LogLevel.VERBOSE`` (``None`` restores
the default, ``"verbose"``/``2`` are accepted too).

Handlers receive :class:`LogRecord` objects and run on the logging thread, so keep
them cheap and never let them raise.
"""
from __future__ import annotations

import os
import sys
import threading
import time
import traceback
from enum import IntEnum
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

__all__ = [
    "LogLevel",
    "LogRecord",
    "Logger",
    "get_logger",
    "dump",
]

# logcat rejects messages longer than ~4000 bytes and tags longer than 23 chars.
_LOGCAT_MESSAGE_LIMIT = 4000
_LOGCAT_TAG_LIMIT = 23

# Text prefix of every logger owned by a plugin.
_PLUGIN_TAG_PREFIX = "plugin."


class LogLevel(IntEnum):
    """Log severity, matching the Android log priorities."""

    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 99


_LEVEL_ALIASES = {
    "verbose": LogLevel.VERBOSE,
    "v": LogLevel.VERBOSE,
    "debug": LogLevel.DEBUG,
    "d": LogLevel.DEBUG,
    "info": LogLevel.INFO,
    "i": LogLevel.INFO,
    "warning": LogLevel.WARNING,
    "warn": LogLevel.WARNING,
    "w": LogLevel.WARNING,
    "error": LogLevel.ERROR,
    "e": LogLevel.ERROR,
    "off": LogLevel.OFF,
    "none": LogLevel.OFF,
    "silent": LogLevel.OFF,
}

# Android log method per level.
_ANDROID_WRITERS = {
    LogLevel.VERBOSE: "v",
    LogLevel.DEBUG: "d",
    LogLevel.INFO: "i",
    LogLevel.WARNING: "w",
    LogLevel.ERROR: "e",
}

_dev_mode = None
_log_class = None
_plugin_loggers: Dict[str, "Logger"] = {}
_plugin_loggers_lock = threading.RLock()


# ---------------------------------------------------------------------------
# level helpers
# ---------------------------------------------------------------------------

def _coerce_level(value: Union[LogLevel, int, str, None]) -> Optional[LogLevel]:
    """Convert ``value`` into a :class:`LogLevel`; ``None`` means "use the default"."""
    if value is None:
        return None
    if isinstance(value, LogLevel):
        return value
    if isinstance(value, bool):
        raise TypeError("log level must not be a bool")
    if isinstance(value, int):
        try:
            return LogLevel(value)
        except ValueError:
            raise ValueError("unknown log level: %r" % (value,))
    if isinstance(value, str):
        alias = _LEVEL_ALIASES.get(value.strip().lower())
        if alias is None:
            raise ValueError("unknown log level: %r" % (value,))
        return alias
    raise TypeError("log level must be LogLevel, int or str, got %r" % (type(value).__name__,))


def _read_dev_mode() -> bool:
    """True when the plugins developer switch is on in the app settings."""
    global _dev_mode
    if _dev_mode is None:
        try:
            from java import jclass

            _dev_mode = bool(jclass("com.exteragram.messenger.ExteraConfig").pluginsDevMode)
        except Exception:
            _dev_mode = False
    return _dev_mode


def _set_dev_mode(value: Optional[bool]) -> None:
    """Override the developer-mode flag (``None`` re-reads it from the app)."""
    global _dev_mode
    _dev_mode = None if value is None else bool(value)


def _default_level() -> LogLevel:
    return LogLevel.DEBUG if _read_dev_mode() else LogLevel.INFO


# ---------------------------------------------------------------------------
# caller / formatting helpers
# ---------------------------------------------------------------------------

def _caller_frame(depth: int = 1):
    """Frame of the caller that is not part of this module."""
    frame = sys._getframe(1)
    while frame is not None and frame.f_globals.get("__name__", "").startswith("log_utils"):
        frame = frame.f_back
    for _ in range(max(0, depth - 1)):
        if frame is None:
            break
        frame = frame.f_back
    return frame


def _format_location(frame) -> str:
    """``file.py:function:line`` for the given frame."""
    if frame is None:
        return "<unknown>"
    filename = os.path.basename(frame.f_code.co_filename) or "<unknown>"
    return "%s:%s:%d" % (filename, frame.f_code.co_name, frame.f_lineno)


def _format_message(message: Any, args: Tuple[Any, ...]) -> str:
    """Apply %-formatting lazily; fall back to appending reprs when the format is wrong."""
    text = message if isinstance(message, str) else str(message)
    if not args:
        return text
    try:
        return text % args
    except Exception:
        return text + " " + " ".join(repr(arg) for arg in args)


def _format_exc_info(exc_info=None) -> str:
    """Traceback text for an ``except`` block, an explicit exception or a tuple."""
    if exc_info is True or exc_info is None:
        exc_info = sys.exc_info()
    elif isinstance(exc_info, BaseException):
        exc_info = (type(exc_info), exc_info, exc_info.__traceback__)
    if not exc_info or exc_info[0] is None:
        return ""
    return "".join(traceback.format_exception(*exc_info)).rstrip()


def _truncate(text: str, limit: int) -> str:
    if limit <= 0 or len(text) <= limit:
        return text
    return text[: limit - 3] + "..."


def _strip_plugin_prefix(name: Optional[str]) -> str:
    """Drop a leading ``plugin.`` from a tag or logger name."""
    text = "" if name is None else str(name)
    if text.startswith(_PLUGIN_TAG_PREFIX):
        return text[len(_PLUGIN_TAG_PREFIX):]
    return text


def _plugin_id_from_frame(frame) -> Optional[str]:
    """Best-effort plugin id for a frame: ``plugins.<id>`` module or a ``/plugins/<id>/`` path."""
    if frame is None:
        return None
    module_name = frame.f_globals.get("__name__") or ""
    parts = module_name.split(".")
    if len(parts) >= 2 and parts[0] == "plugins":
        return parts[1]
    filename = frame.f_globals.get("__file__") or ""
    marker = os.sep + "plugins" + os.sep
    if marker in filename:
        tail = filename.split(marker, 1)[1]
        candidate = tail.split(os.sep, 1)[0]
        if candidate:
            return candidate
    return None


def _split_for_logcat(text: str, limit: int = _LOGCAT_MESSAGE_LIMIT) -> List[str]:
    """Split ``text`` into chunks logcat accepts, preferring line boundaries."""
    if len(text) <= limit:
        return [text]
    chunks: List[str] = []
    start = 0
    while start < len(text):
        end = min(start + limit, len(text))
        if end < len(text):
            newline = text.rfind("\n", start, end)
            if newline > start + limit // 2:
                end = newline + 1
        chunks.append(text[start:end])
        start = end
    return chunks


def _write_logcat(level: LogLevel, tag: str, text: str) -> bool:
    """Write ``text`` to logcat; returns False when the Java log class is unavailable."""
    global _log_class
    if _log_class is None:
        try:
            from java import jclass

            _log_class = jclass("android.util.Log")
        except Exception:
            return False
    writer_name = _ANDROID_WRITERS.get(level, "i")
    try:
        writer = getattr(_log_class, writer_name)
        safe_tag = _truncate(tag or "plugin", _LOGCAT_TAG_LIMIT)
        for chunk in _split_for_logcat(text):
            writer(safe_tag, chunk)
        return True
    except Exception:
        return False


def _report_internal_error(error: BaseException, tag: str) -> None:
    """Never let logging raise into the caller; report on logcat instead."""
    try:
        _write_logcat(LogLevel.ERROR, "plugin.sdk", "Failed to write a log entry for %s: %r" % (tag, error))
    except Exception:
        pass


# ---------------------------------------------------------------------------
# records and handlers
# ---------------------------------------------------------------------------

class LogRecord:
    """One log entry handed to handlers."""

    __slots__ = ("level", "tag", "location", "message", "exc_text", "timestamp", "plugin_id")

    def __init__(self, level: LogLevel, tag: str, location: str, message: str,
                 exc_text: str = "", plugin_id: Optional[str] = None, timestamp: Optional[float] = None):
        self.level = level
        self.tag = tag
        self.location = location
        self.message = message
        self.exc_text = exc_text
        self.plugin_id = plugin_id
        self.timestamp = time.time() if timestamp is None else timestamp

    @property
    def text(self) -> str:
        """``[location] message`` followed by the traceback, if any."""
        body = "[%s] %s" % (self.location, self.message) if self.location else self.message
        if self.exc_text:
            body += "\n" + self.exc_text
        return body

    def __repr__(self) -> str:
        return "<LogRecord %s %s %s>" % (self.level.name, self.tag, self.location)


# ---------------------------------------------------------------------------
# object dumper
# ---------------------------------------------------------------------------

class _Unreadable:
    """Placeholder for a value the dumper refused to render."""

    __slots__ = ("reason",)

    def __init__(self, reason: str):
        self.reason = reason

    def __repr__(self) -> str:
        return "<unreadable: %s>" % self.reason


def _java_type_name(obj) -> Optional[str]:
    """Java class name of ``obj``, or None for Python values."""
    try:
        get_class = getattr(obj, "getClass", None)
        if get_class is None:
            return None
        return str(get_class().getName())
    except Exception:
        return None


def _java_iter(obj):
    """Iterate a Java collection through its iterator."""
    try:
        iterator = obj.iterator()
        while iterator.hasNext():
            yield iterator.next()
    except Exception:
        return


def _scalar_repr(value: Any, max_string: int = 200) -> str:
    if isinstance(value, str):
        text = value.replace("\n", "\\n")
        return '"%s"' % _truncate(text, max_string)
    if isinstance(value, bytes):
        return "<%d bytes>" % len(value)
    return repr(value)


class _Dumper:
    """Render Python and Java objects into a compact single-string form."""

    def __init__(self, max_depth: int = 4, max_items: int = 20, max_string: int = 200):
        self.max_depth = max_depth
        self.max_items = max_items
        self.max_string = max_string

    # -- public entry point -------------------------------------------------
    def render(self, obj: Any) -> str:
        try:
            return self._node(obj, 0, set())
        except Exception as error:
            return "<dump failed: %r>" % (error,)

    # -- traversal ---------------------------------------------------------
    def _node(self, obj: Any, depth: int, seen: set) -> str:
        if obj is None or isinstance(obj, (bool, int, float, str, bytes)):
            return _scalar_repr(obj, self.max_string)
        if depth >= self.max_depth:
            return "..."
        marker = id(obj)
        if marker in seen:
            return "<recursion>"
        seen = seen | {marker}
        java_name = _java_type_name(obj)
        if java_name is not None:
            return self._inspect_java(obj, java_name, depth, seen)
        if isinstance(obj, dict):
            return self._inspect_mapping(obj, depth, seen)
        if isinstance(obj, (list, tuple, set, frozenset)):
            return self._inspect_sequence(obj, depth, seen)
        return self._inspect(obj, depth, seen)

    def _inspect_mapping(self, obj, depth: int, seen: set) -> str:
        parts = []
        for index, key in enumerate(obj):
            if index >= self.max_items:
                parts.append("...")
                break
            parts.append("%s: %s" % (self._key(key), self._node(obj[key], depth + 1, seen)))
        return "{%s}" % ", ".join(parts)

    def _inspect_sequence(self, obj, depth: int, seen: set) -> str:
        parts = []
        for index, value in enumerate(obj):
            if index >= self.max_items:
                parts.append("...")
                break
            parts.append(self._node(value, depth + 1, seen))
        opener, closer = ("(", ")") if isinstance(obj, tuple) else ("[", "]")
        return "%s%s%s" % (opener, ", ".join(parts), closer)

    def _inspect(self, obj, depth: int, seen: set) -> str:
        """Python object: class name plus its attributes."""
        fields = self._python_fields(obj)
        if not fields:
            return _scalar_repr(obj, self.max_string)
        parts = []
        for index, (name, value) in enumerate(fields):
            if index >= self.max_items:
                parts.append("...")
                break
            parts.append("%s=%s" % (name, self._node(value, depth + 1, seen)))
        return "%s(%s)" % (type(obj).__name__, ", ".join(parts))

    def _python_fields(self, obj) -> List[Tuple[str, Any]]:
        fields: List[Tuple[str, Any]] = []
        slots = getattr(type(obj), "__slots__", None)
        if slots:
            for name in slots:
                try:
                    fields.append((name, getattr(obj, name)))
                except Exception as error:
                    fields.append((name, _Unreadable(repr(error))))
            return fields
        try:
            fields.extend(sorted(vars(obj).items()))
        except Exception as error:
            return [("<attributes>", _Unreadable(repr(error)))]
        return fields

    def _inspect_java(self, obj, java_name: str, depth: int, seen: set) -> str:
        """Java object: collection/map contents or declared fields."""
        if java_name.startswith("java.util.") and hasattr(obj, "entrySet"):
            parts = []
            for index, entry in enumerate(_java_iter(obj.entrySet())):
                if index >= self.max_items:
                    parts.append("...")
                    break
                parts.append("%s: %s" % (self._key(entry.getKey()),
                                         self._node(entry.getValue(), depth + 1, seen)))
            return "{%s}" % ", ".join(parts)
        if java_name.startswith("java.util.") and hasattr(obj, "iterator"):
            parts = []
            for index, value in enumerate(_java_iter(obj)):
                if index >= self.max_items:
                    parts.append("...")
                    break
                parts.append(self._node(value, depth + 1, seen))
            return "[%s]" % ", ".join(parts)
        if java_name in ("java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Boolean",
                         "java.lang.Double", "java.lang.Float", "java.lang.Short", "java.lang.Byte",
                         "java.lang.Character"):
            try:
                return _scalar_repr(str(obj), self.max_string)
            except Exception:
                return "<%s>" % java_name
        fields = self._java_fields(obj)
        if not fields:
            try:
                return "<%s %s>" % (java_name, _truncate(str(obj), self.max_string))
            except Exception:
                return "<%s>" % java_name
        parts = []
        for index, (name, value) in enumerate(fields):
            if index >= self.max_items:
                parts.append("...")
                break
            parts.append("%s=%s" % (name, self._node(value, depth + 1, seen)))
        return "%s{%s}" % (java_name, ", ".join(parts))

    def _java_fields(self, obj) -> List[Tuple[str, Any]]:
        """Readable declared fields of a Java object, following one superclass level."""
        fields: List[Tuple[str, Any]] = []
        try:
            java_class = obj.getClass()
        except Exception as error:
            return [("<class>", _Unreadable(repr(error)))]
        try:
            declared = java_class.getDeclaredFields()
        except Exception as error:
            return [("<fields>", _Unreadable(repr(error)))]
        for field in _java_iter(declared):
            try:
                name = str(field.getName())
                modifiers = int(field.getModifiers())
                # Skip synthetic/static noise, keep instance state only.
                if name.startswith("$") or name.startswith("this$"):
                    continue
                if modifiers & 0x0008:  # static
                    continue
                field.setAccessible(True)
                fields.append((name, field.get(obj)))
            except Exception as error:
                try:
                    fields.append((str(field.getName()), _Unreadable(repr(error))))
                except Exception:
                    continue
        return fields

    def _key(self, key: Any) -> str:
        rendered = self._node(key, self.max_depth - 1, set())
        return _truncate(rendered, self.max_string)

    def _inline(self, text: str) -> str:
        return _truncate(text.replace("\n", " "), self.max_string)

    def _take(self, sequence, count: int) -> List[Any]:
        taken = []
        for index, value in enumerate(sequence):
            if index >= count:
                break
            taken.append(value)
        return taken


# ---------------------------------------------------------------------------
# timing helper
# ---------------------------------------------------------------------------

class _Timer:
    """Context manager returned by :meth:`Logger.timed`."""

    __slots__ = ("_logger", "_label", "_level", "_start", "elapsed")

    def __init__(self, logger: "Logger", label: str, level: LogLevel):
        self._logger = logger
        self._label = label
        self._level = level
        self._start = None
        self.elapsed = 0.0

    def __enter__(self) -> "_Timer":
        self._start = time.perf_counter()
        return self

    def __exit__(self, exc_type, exc_value, exc_tb) -> bool:
        self.elapsed = (time.perf_counter() - self._start) * 1000.0 if self._start else 0.0
        self._logger.log(self._level, "%s took %.1f ms", self._label, self.elapsed)
        return False


# ---------------------------------------------------------------------------
# logger
# ---------------------------------------------------------------------------

class Logger:
    """Level-filtered logger writing to logcat and to any attached handlers."""

    def __init__(self, tag: str = "plugin", level: Union[LogLevel, int, str, None] = None,
                 show_location: bool = True, plugin_id: Optional[str] = None):
        self._tag = str(tag) if tag else "plugin"
        self._level = _coerce_level(level)
        self._show_location = bool(show_location)
        self._plugin_id = plugin_id
        self._handlers: List[Callable[[LogRecord], Any]] = []

    # -- configuration -----------------------------------------------------
    @property
    def tag(self) -> str:
        return self._tag

    @tag.setter
    def tag(self, value: str) -> None:
        self._tag = str(value) if value else "plugin"

    @property
    def level(self) -> Optional[LogLevel]:
        """Explicit level, or None while the logger follows the app default."""
        return self._level

    @level.setter
    def level(self, value: Union[LogLevel, int, str, None]) -> None:
        self._level = _coerce_level(value)

    @property
    def effective_level(self) -> LogLevel:
        return self._level if self._level is not None else _default_level()

    @property
    def plugin_id(self) -> Optional[str]:
        return self._plugin_id

    @property
    def show_location(self) -> bool:
        return self._show_location

    @show_location.setter
    def show_location(self, value: bool) -> None:
        self._show_location = bool(value)

    def is_enabled_for(self, level: Union[LogLevel, int, str]) -> bool:
        """True when an entry of ``level`` would be emitted."""
        resolved = _coerce_level(level) or LogLevel.INFO
        effective = self.effective_level
        if effective == LogLevel.OFF:
            return False
        return resolved >= effective

    # -- handlers ----------------------------------------------------------
    def add_handler(self, handler: Callable[[LogRecord], Any]) -> Callable[[LogRecord], Any]:
        """Attach ``handler``; it receives every emitted :class:`LogRecord`."""
        if handler not in self._handlers:
            self._handlers.append(handler)
        return handler

    def remove_handler(self, handler: Callable[[LogRecord], Any]) -> None:
        self._handlers = [existing for existing in self._handlers if existing is not handler]

    def _dispatch(self, record: LogRecord) -> None:
        for handler in list(self._handlers):
            try:
                handler(record)
            except Exception as error:
                _report_internal_error(error, self._tag)

    def _reset(self) -> None:
        """Drop handlers and the explicit level."""
        self._handlers = []
        self._level = None

    # -- emitting ----------------------------------------------------------
    def _emit(self, level: LogLevel, message: Any, args: Tuple[Any, ...],
              exc_info=None, frame=None) -> Optional[LogRecord]:
        if not self.is_enabled_for(level):
            return None
        try:
            text = _format_message(message, args)
            exc_text = _format_exc_info(exc_info)
            location = _format_location(frame if frame is not None else _caller_frame(2)) \
                if self._show_location else ""
            record = LogRecord(level, self._tag, location, text, exc_text, self._plugin_id)
            if not _write_logcat(level, self._tag, record.text):
                _report_internal_error(RuntimeError("logcat unavailable"), self._tag)
            self._dispatch(record)
            return record
        except Exception as error:
            _report_internal_error(error, self._tag)
            return None

    def log(self, level: Union[LogLevel, int, str], message: Any, *args) -> Optional[LogRecord]:
        """Emit at an explicit level."""
        resolved = _coerce_level(level) or LogLevel.INFO
        return self._emit(resolved, message, args)

    def verbose(self, message: Any, *args) -> Optional[LogRecord]:
        return self._emit(LogLevel.VERBOSE, message, args)

    def debug(self, message: Any, *args) -> Optional[LogRecord]:
        return self._emit(LogLevel.DEBUG, message, args)

    def info(self, message: Any, *args) -> Optional[LogRecord]:
        return self._emit(LogLevel.INFO, message, args)

    def warning(self, message: Any, *args) -> Optional[LogRecord]:
        return self._emit(LogLevel.WARNING, message, args)

    def error(self, message: Any, *args) -> Optional[LogRecord]:
        return self._emit(LogLevel.ERROR, message, args)

    def exception(self, message: Any, *args, exc_info=None) -> Optional[LogRecord]:
        """Log at ERROR with the traceback of the active (or given) exception."""
        return self._emit(LogLevel.ERROR, message, args, exc_info=exc_info if exc_info is not None else True)

    # -- helpers -----------------------------------------------------------
    def child(self, name: str) -> "Logger":
        """A logger sharing this one's level and handlers but with a sub-tag."""
        child = Logger("%s.%s" % (self._tag, name), self._level, self._show_location, self._plugin_id)
        child._handlers = self._handlers
        return child

    def _child(self, name: str) -> "Logger":
        return self.child(name)

    def dump(self, obj: Any, label: Optional[str] = None, level: Union[LogLevel, int, str] = LogLevel.DEBUG,
             max_depth: int = 4, max_items: int = 20) -> Optional[LogRecord]:
        """Log a readable rendering of a Python or Java object."""
        text = _Dumper(max_depth, max_items).render(obj)
        if label:
            text = "%s: %s" % (label, text)
        return self.log(level, text)

    def timed(self, label: str, level: Union[LogLevel, int, str] = LogLevel.DEBUG) -> _Timer:
        """Context manager logging how long the block took."""
        return _Timer(self, label, _coerce_level(level) or LogLevel.DEBUG)

    def __repr__(self) -> str:
        return "<Logger %s level=%s>" % (self._tag, self.effective_level.name)


# ---------------------------------------------------------------------------
# module-level API
# ---------------------------------------------------------------------------

def get_logger(name: Optional[str] = None, level: Union[LogLevel, int, str, None] = None) -> Logger:
    """Logger for ``name``; the tag becomes ``plugin.<name>``."""
    base = _strip_plugin_prefix(name) if name else "plugin"
    tag = _PLUGIN_TAG_PREFIX + base if base != "plugin" else "plugin"
    return Logger(tag, level)


def _plugin_logger(plugin_id: str, level: Union[LogLevel, int, str, None] = None) -> Logger:
    """Shared logger for a plugin, created on first use."""
    key = str(plugin_id)
    with _plugin_loggers_lock:
        logger = _plugin_loggers.get(key)
        if logger is None:
            logger = Logger(_PLUGIN_TAG_PREFIX + key, level, True, key)
            _plugin_loggers[key] = logger
        return logger


def _reset_plugin_logger(plugin_id: str) -> None:
    """Forget a plugin's logger and its handlers, e.g. when the plugin unloads."""
    with _plugin_loggers_lock:
        logger = _plugin_loggers.pop(str(plugin_id), None)
    if logger is not None:
        logger._reset()


def dump(obj: Any, label: Optional[str] = None, level: Union[LogLevel, int, str] = LogLevel.DEBUG,
         max_depth: int = 4, max_items: int = 20) -> None:
    """Dump ``obj`` through a module logger, deriving the plugin id from the caller."""
    plugin_id = _plugin_id_from_frame(_caller_frame(1))
    logger = _plugin_logger(plugin_id) if plugin_id else get_logger()
    logger.dump(obj, label, level, max_depth, max_items)
