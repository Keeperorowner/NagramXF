"""elyxcore._install — installation UI helpers for structured (Elyx) plugins.
"""
from __future__ import annotations

from typing import Any, Callable, Optional

from java import jclass
from traceback import format_exc

import android_utils
from ui.alert import AlertDialogBuilder
from ui.bulletin import BulletinHelper

AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")


def _get_engine():
    from elyxcore._plugin_engine import ElyxEngine
    return ElyxEngine.getInstance()


def dlog(message: str):
    try:
        android_utils.log(message)
    except Exception:
        pass


class InstallAlert:
    """Password / confirmation dialog for installing an Elyx plugin archive."""

    def __init__(self, lib=None, java_file=None, activity=None, plugin=None, trusted=False):
        self.lib = lib
        self.java_file = java_file
        self.activity = activity
        self.plugin = plugin
        self.trusted = trusted
        self._password: Optional[str] = None

    def show_password_dialog(self, lib=None, activity=None, f=None, trusted=None,
                             on_complete: Optional[Callable] = None,
                             on_error: Optional[Callable] = None):
        """Show a password prompt for an encrypted archive."""
        builder = AlertDialogBuilder()
        builder.set_title("Enter plugin password")
        builder.set_message("This plugin archive is password protected.")
        builder.set_input("Password", "", lambda text: self._on_password(text, on_complete, on_error))
        builder.show()

    def _on_password(self, text, on_complete, on_error):
        self._password = text
        if on_complete:
            try:
                on_complete(text)
            except Exception:
                format_exc()
                if on_error:
                    on_error()

    def error(self, message: str):
        try:
            BulletinHelper.show_error(message)
        except Exception:
            format_exc()

    def on_pip_progress(self, ptext: str):
        pass

    def show(self, sset=None):
        pass


class BulletinHook:
    """Hook that shows a bulletin after plugin installation."""

    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        try:
            _get_engine().notify_plugins_changed()
        except Exception:
            format_exc()
