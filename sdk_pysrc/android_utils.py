"""android_utils — UI-thread helpers, listeners, logging, clipboard.

Wraps the Java side via Chaquopy: ``AndroidUtilities.runOnUIThread``,
``AppUtils.log`` and interface proxies for ``Runnable`` / click listeners.
"""
from __future__ import annotations

from java import dynamic_proxy, jclass

AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
AppUtils = jclass("com.exteragram.messenger.utils.AppUtils")

_Runnable = jclass("java.lang.Runnable")
_OnClickListenerIface = jclass("android.view.View$OnClickListener")
_OnLongClickListenerIface = jclass("android.view.View$OnLongClickListener")


def dp(value):
    """Density-independent pixels -> px (AndroidUtilities.dp)."""
    try:
        return AndroidUtilities.dp(float(value))
    except Exception:
        return 0


def get_string(key, res=0):
    """Localized UI string by key (LocaleController.getString); optional fallback resource id."""
    from java import jclass as _jc
    try:
        LocaleController = _jc("org.telegram.messenger.LocaleController")
        if res:
            return LocaleController.getString(str(key), int(res))
        return LocaleController.getString(str(key))
    except Exception:
        return str(key)


class R(dynamic_proxy(_Runnable)):
    """A Java ``Runnable`` backed by a Python callable."""

    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def run(self):
        self._fn()


class OnClickListener(dynamic_proxy(_OnClickListenerIface)):
    """A Java ``View.OnClickListener`` backed by a Python callable."""

    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def onClick(self, view):
        self._fn(view)


class OnLongClickListener(dynamic_proxy(_OnLongClickListenerIface)):
    """A Java ``View.OnLongClickListener`` backed by a Python callable."""

    def __init__(self, fn):
        super().__init__()
        self._fn = fn

    def onLongClick(self, view):
        return bool(self._fn(view))


def run_on_ui_thread(func, delay=0):
    """Schedule *func* on the Android UI thread, optionally after *delay* ms."""
    if func is None:
        return
    runnable = R(func)
    if delay:
        AndroidUtilities.runOnUIThread(runnable, int(delay))
    else:
        AndroidUtilities.runOnUIThread(runnable)


def log(data):
    """Send *data* into the app logging pipeline (``AppUtils.log``)."""
    try:
        if data is None:
            AppUtils.log("None")
        elif isinstance(data, str):
            AppUtils.log(data)
        else:
            AppUtils.log(str(data))
    except Exception:
        try:
            AppUtils.log(repr(data))
        except Exception:
            pass


def copy_to_clipboard(text):
    """Copy *text* to the clipboard and show the standard "copied" bulletin."""
    try:
        if AndroidUtilities.addToClipboard(str(text)):
            from ui.bulletin import BulletinHelper
            BulletinHelper.show_copied_to_clipboard()
    except Exception:
        pass
