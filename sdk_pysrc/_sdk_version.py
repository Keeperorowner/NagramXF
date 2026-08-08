"""_sdk_version — SDK version bootstrap.

Exposes the values the Java host (``PythonPluginsEngine``) reads:

  - ``__start__()``  -> must return a truthy value
  - ``__version__``  -> SDK version string
  - ``__beta__``     -> boolean beta flag
"""
from __future__ import annotations

__version__ = "1.4.6.6"
__beta__ = False

version_str = __version__
version = tuple(int(part) for part in __version__.split(".") if part.isdigit())
beta = __beta__


class X:
    """Version info holder."""
    BUILD_VERSION = None


class SafeModeImporter:
    """No-op safe-mode import hook."""

    IS_OLD_VERSION = False
    I = False
    E = False
    INSTANCE = None

    def find_spec(self, fullname, path=None, target=None):
        return None

    def c(self):
        return None


def check_safemode():
    return False


def setup_hooks():
    pass


def _remove_safe_mode_importers():
    pass


def _is_safe_mode_importer_entry(entry):
    return False


def __start__():
    """Return a truthy value to mark the SDK as initialized."""
    return True


def __stop__():
    pass


def _reset_state():
    pass
