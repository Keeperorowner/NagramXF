"""elyxcore._engine — structured (Elyx) plugin engine lifecycle.

Initializes the plugin importer, the dev-server check and the ElyxEngine
singleton.
"""
from __future__ import annotations

from typing import Optional

from traceback import format_exc

from elyxcore._importer import setup_importer, stop_importer
from elyxcore._plugin_engine import ElyxEngine

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


_registration_pending = False
_inst: Optional[ElyxEngine] = None


def _register_when_controller_is_ready(controller, *args, **kwargs):
    """Called when PluginsController is initialized."""
    global _registration_pending, _inst
    try:
        if not controller.isInitialized():
            _registration_pending = True
            return
        controller.loadPluginSettings()
        _registration_pending = False
        eng = ElyxEngine()
        eng.setup()
        _inst = eng
    except Exception:
        format_exc()


def _setup(*args, **kwargs):
    """Initialize the structured (Elyx) plugin engine."""
    setup_importer()
    try:
        engine = ElyxEngine()
        engine.setup()
    except Exception:
        format_exc()


def _stop(*args, **kwargs):
    """Shut down the structured plugin engine."""
    global _inst
    try:
        if _inst is not None:
            _inst.shutdown()
            _inst = None
    except Exception:
        format_exc()
    stop_importer()
