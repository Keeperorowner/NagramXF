"""Public API for structured Elyx plugins.

Plugin code should import supported symbols from ``elyx``::

    from elyx import Asset, Assets, SettingsController, Strings

Plugin-bound values are resolved at runtime and remain available in the same
facade::

    from elyx import assets, metainfo, refmap, settings, strings

Runtime modules whose names start with an underscore are implementation details
and may change without preserving import compatibility.
"""

from __future__ import annotations

import java

from typing import Any

View = java.jclass("android.view.View")
JavaRunnable = java.jclass("java.lang.Runnable")
Utilities = java.jclass("org.telegram.messenger.Utilities")

from elyxcore.assets import (
    Asset,
    AssetNotFoundException,
    Assets,
    AssetsDirNotFoundException,
)
from elyxcore.localization import Strings
from elyxcore.settings import SettingsController
from elyxcore.utils import LazyDict, gen, gen2, mvel_execute

OnClickListener = gen(View.OnClickListener, "onClick")
Runnable = gen(JavaRunnable, "run")
Callback = gen(Utilities.Callback, "run")
Callback2 = gen(Utilities.Callback2, "run")
Callback3 = gen(Utilities.Callback3, "run")
CallbackReturn = gen(Utilities.CallbackReturn, "run", True)

def get_environment() -> dict[str, Any]:
    """Return assets, settings, strings and metadata for the calling plugin."""
    from elyxcore import _importer

    _importer.setup()
    plugin = _importer.importer.get_caller_plugin()
    if plugin is None:
        raise RuntimeError("Elyx environment is only available from plugin code")
    return plugin.get_environment_vars()

def import_module(name: str, package: str | None = None):
    """Import a module relative to the calling plugin when it exists locally."""
    from elyxcore import _importer

    _importer.setup()
    return _importer.importer.import_module(name, package)

__all__ = (
    "Asset",
    "AssetNotFoundException",
    "Assets",
    "AssetsDirNotFoundException",
    "Callback",
    "Callback2",
    "Callback3",
    "CallbackReturn",
    "LazyDict",
    "OnClickListener",
    "Runnable",
    "SettingsController",
    "Strings",
    "gen",
    "gen2",
    "get_environment",
    "import_module",
    "mvel_execute",
)

def _public_api() -> dict[str, Any]:
    return {name: globals()[name] for name in __all__}


def __getattr__(name: str):
    # Plugin-bound environment values are resolved lazily from the calling
    # plugin, so ``from elyx import assets`` / ``settings`` / ``strings`` work.
    if name in ("assets", "settings", "strings", "metainfo", "refmap"):
        return get_environment()[name]
    raise AttributeError(f"module 'elyx' has no attribute '{name}'")

def _setup():
    from elyxcore._engine import _setup as setup_engine

    return setup_engine()

def _stop():
    from elyxcore._engine import _stop as stop_engine

    return stop_engine()
