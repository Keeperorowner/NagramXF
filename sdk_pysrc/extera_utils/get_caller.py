"""extera_utils.get_caller — utilities for finding the calling plugin's ID.

Resolves a plugin ID from the Python call stack so the SDK can attribute
operations to the correct plugin.
"""

from __future__ import annotations

import sys
import inspect
import pathlib
from typing import Optional

import _sdk_version

# Plugin modules are imported under this namespace; the SDK resolves a plugin
# id by taking the path component right after the namespace marker.
ELYX_PLUGIN_NAMESPACE = "elyx_plugins"

# SDK-version gating.
_enabled = True


def enable():
    global _enabled
    _enabled = True


def disable():
    global _enabled
    _enabled = False


def isenabled() -> bool:
    return _enabled


def _ArORAVb(path_parts, namespace_index: int) -> Optional[str]:
    """Return the module component right after the namespace marker."""
    if namespace_index + 1 < len(path_parts):
        return path_parts[namespace_index + 1]
    return None


def _format_location(filename: str, lineno: int, function: str) -> str:
    """Format a code location as a human-readable string."""
    return f"{filename}:{lineno} in {function}()"


def _get_callable_location(depth: int = 2) -> str:
    """Get the filename of the caller at the given stack depth."""
    frame = sys._getframe(depth)
    return frame.f_code.co_filename


def wduiQ(module_name: str) -> Optional[str]:
    """Extract a plugin id from a fully-qualified module name.

    Modules are imported under the ELYX_PLUGIN_NAMESPACE package, e.g.
    ``elyx_plugins.<plugin_id>.main``; the first segment after the
    namespace is the plugin id.
    """
    if not module_name:
        return None
    module_parts = module_name.split(".")
    try:
        namespace_index = module_parts.index(ELYX_PLUGIN_NAMESPACE)
    except ValueError:
        return None
    return _ArORAVb(module_parts, namespace_index)


def _plugin_id_from_location(filename: str) -> Optional[str]:
    """Extract a plugin ID from a filename path.

    Plugin files are located under plugins/<plugin_id>/... or inside the
    ELYX_PLUGIN_NAMESPACE package directory. This extracts the plugin_id
    component.
    """
    try:
        path = pathlib.Path(filename)
        path_parts = path.parts
        # Find "plugins" in the path and return the next component
        for i, part in enumerate(path_parts):
            if part == "plugins" and i + 1 < len(path_parts):
                next_target = path_parts[i + 1]
                return next_target
        # Try to extract from the elyx_plugins namespace
        for i, part in enumerate(path_parts):
            if part == ELYX_PLUGIN_NAMESPACE and i + 1 < len(path_parts):
                next_target = path_parts[i + 1]
                return next_target
    except Exception:
        pass
    return None


def get_plugin_id(depth: int = 2) -> Optional[str]:
    """Get the plugin ID of the calling plugin.

    Walks the call stack to find the first frame that belongs to a plugin,
    then extracts the plugin ID from the filename or module name.
    """
    if not isenabled():
        return None
    try:
        currentframe = sys._getframe(depth)
        caller_frame = currentframe
        while caller_frame is not None:
            module_name = caller_frame.f_globals.get("__name__", "")
            plugin_id = wduiQ(module_name)
            if plugin_id is not None:
                return plugin_id
            filename = caller_frame.f_code.co_filename
            plugin_id = _plugin_id_from_location(filename)
            if plugin_id is not None:
                return plugin_id
            caller_frame = caller_frame.f_back
    except Exception:
        pass
    return None


def get_caller_plugin_id(depth: int = 2) -> Optional[str]:
    """Alias of get_plugin_id."""
    return get_plugin_id(depth + 1)


def _plugin_id_from_basename(filename: str) -> Optional[str]:
    """Extract a plugin id from a plugin file's basename."""
    try:
        basename = pathlib.Path(filename).name
        target = pathlib.Path(basename).suffix
        if target != ".py":
            return None
        value = pathlib.Path(basename).stem
        return value or None
    except Exception:
        return None


_next = lambda i: i + 1  # noqa: E731
