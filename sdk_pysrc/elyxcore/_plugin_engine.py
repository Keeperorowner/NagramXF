"""elyxcore._plugin_engine — ElyxEngine: structured-plugin lifecycle engine.

Loads Elyx structured plugins (directories or archives), registers them with
``elyxcore._importer`` and exposes lifecycle/hook/settings helpers.
"""
from __future__ import annotations

import pathlib
import shutil
from typing import Any, Callable, Dict, List, Optional

from java import jclass
from traceback import format_exc

import android_utils
from elyxcore._importer import importer as _importer_module
from elyxcore._plugin import (
    ArchiveEncryptionError,
    ElyxPlugin,
    InvalidPluginError,
    SecurityError,
)

PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")

# SDK-version gating.
_enabled = True
_debug_logs = False


def enable():
    global _enabled
    _enabled = True


def disable():
    global _enabled
    _enabled = False


def isenabled() -> bool:
    return _enabled


def enable_debug_logs():
    global _debug_logs
    _debug_logs = True


def disable_debug_logs():
    global _debug_logs
    _debug_logs = False


def dlog(message: str):
    if _debug_logs:
        try:
            android_utils.log(message)
        except Exception:
            pass


class SettingItemsHook:
    """Post-install hook adapter."""

    def __init__(self, engine: "ElyxEngine"):
        self._engine = engine

    def after_hooked_method(self, param):
        try:
            self._engine.notify_plugins_changed()
        except Exception:
            format_exc()


class HookResult:
    def __init__(self, result: Any = None, error: Optional[str] = None):
        self.result = result
        self.error = error

    def hasError(self) -> bool:
        return self.error is not None


class HookStrategy:
    BEFORE = "before"
    AFTER = "after"
    REPLACE = "replace"


class ElyxEngine:
    """Manages the structured (Elyx) plugin lifecycle."""

    INSTANCE: Optional["ElyxEngine"] = None

    def __init__(self):
        self._plugins_dir: Optional[pathlib.Path] = None
        self._plugins_instances: Dict[str, ElyxPlugin] = {}
        self._controller = None
        self._inited = False
        self._initializing = False
        self.__ih = SettingItemsHook(self)

    # ---- singleton ---------------------------------------------------------

    @classmethod
    def getInstance(cls) -> "ElyxEngine":
        if cls.INSTANCE is None:
            cls.INSTANCE = ElyxEngine()
        return cls.INSTANCE

    @classmethod
    def getEngines(cls) -> List["ElyxEngine"]:
        return [cls.INSTANCE] if cls.INSTANCE is not None else []

    # ---- properties --------------------------------------------------------

    @property
    def plugins_dir(self) -> pathlib.Path:
        if self._plugins_dir is None:
            try:
                self._plugins_dir = pathlib.Path(PluginsController.getInstance().pluginsDir.getAbsolutePath())
            except Exception:
                self._plugins_dir = pathlib.Path("plugins")
        return self._plugins_dir

    @property
    def plugins_dir_path(self) -> str:
        return str(self.plugins_dir)

    def isEngineAvailable(self) -> bool:
        return self._inited and not self._initializing

    # ---- lifecycle ---------------------------------------------------------

    def setup(self):
        if self._initializing:
            return
        self._initializing = True
        try:
            self.loadPlugins()
            self._inited = True
        except Exception:
            format_exc()
        finally:
            self._initializing = False

    def shutdown(self, callback: Optional[Callable] = None):
        self.unloadPlugins()
        if _importer_module is not None:
            _importer_module.unload_all()
        self._inited = False
        if callback is not None:
            try:
                callback()
            except Exception:
                format_exc()

    # ---- plugin loading ----------------------------------------------------

    def loadPlugins(self, callback: Optional[Callable] = None):
        if not self.plugins_dir.exists():
            return
        for plugin_dir in self.plugins_dir.iterdir():
            if not plugin_dir.is_dir():
                continue
            try:
                self.loadPlugin(str(plugin_dir))
            except Exception:
                format_exc()
        if callback is not None:
            try:
                callback()
            except Exception:
                format_exc()

    def loadPlugin(self, plugin_path: str, password: str = "",
                   enable_after_install: bool = False, **kwargs):
        path = pathlib.Path(plugin_path)
        if path.is_file() and path.suffix in (".plzip", ".zip", ".eaf", ".elyx"):
            return self._load_from_archive(path, password, enable_after_install)
        if not path.is_dir():
            raise InvalidPluginError("Plugin path not found: %s" % path)

        plugin_id = path.name
        if plugin_id in self._plugins_instances:
            return self._plugins_instances[plugin_id]

        main_file = path / "main.py"
        if not main_file.exists():
            main_file = path / "__init__.py"
        if not main_file.exists():
            py_files = sorted(path.glob("*.py"))
            if not py_files:
                raise InvalidPluginError("No Python file found in plugin: %s" % path)
            main_file = py_files[0]

        try:
            if _importer_module is None:
                raise InvalidPluginError("Elyx importer not initialized")
            _importer_module.setup()
            module = _importer_module.import_module(main_file.stem, package=str(path.parent))
            plugin_class = self._find_plugin_class(module)
            if plugin_class is None:
                raise InvalidPluginError("No plugin class found in %s" % main_file)
            plugin_instance = plugin_class(plugin_id, str(path))
            plugin_instance.init(self, str(path))
            self._plugins_instances[plugin_id] = plugin_instance
            _importer_module.register_plugin(plugin_id, str(path), plugin_instance)
            self.notify_plugins_changed()
            return plugin_instance
        except Exception:
            format_exc()
            raise

    def _load_from_archive(self, archive_path: pathlib.Path, password: str,
                           enable_after_install: bool):
        plugin_id = archive_path.stem
        extract_dir = self.plugins_dir / plugin_id
        if extract_dir.exists():
            shutil.rmtree(str(extract_dir))
        import zipfile
        try:
            with zipfile.ZipFile(str(archive_path), "r") as zf:
                if password:
                    zf.setpassword(password.encode("utf-8"))
                for member in zf.namelist():
                    if member.startswith("..") or member.startswith("/"):
                        raise SecurityError("Path traversal in archive: %s" % member)
                zf.extractall(str(extract_dir))
        except RuntimeError as e:
            raise ArchiveEncryptionError("Failed to decrypt archive: %s" % e)
        except zipfile.BadZipFile as e:
            raise InvalidPluginError("Corrupt archive: %s" % e)
        return self.loadPlugin(str(extract_dir), enable_after_install=enable_after_install)

    def isPlugin(self, plugin_path: str) -> bool:
        path = pathlib.Path(plugin_path)
        if path.is_file() and path.suffix in (".plzip", ".zip", ".eaf", ".elyx"):
            return True
        if path.is_dir():
            return any(path.glob("*.py"))
        return False

    @staticmethod
    def _find_plugin_class(module) -> Optional[type]:
        for name in dir(module):
            obj = getattr(module, name)
            if isinstance(obj, type) and issubclass(obj, ElyxPlugin) and obj is not ElyxPlugin:
                return obj
        return None

    # ---- unload ------------------------------------------------------------

    def unloadPlugin(self, plugin_id: str):
        plugin = self._plugins_instances.pop(plugin_id, None)
        if plugin is not None:
            try:
                plugin.on_plugin_unload()
            except Exception:
                format_exc()
        if _importer_module is not None:
            try:
                _importer_module.unload_plugin(plugin_id)
            except Exception:
                format_exc()
        self.notify_plugins_changed()

    def unloadPlugins(self, callback: Optional[Callable] = None):
        for plugin_id in list(self._plugins_instances.keys()):
            self.unloadPlugin(plugin_id)
        if callback is not None:
            try:
                callback()
            except Exception:
                format_exc()

    # ---- settings / helpers ------------------------------------------------

    def getPluginPath(self, plugin_id: str) -> Optional[str]:
        plugin = self._plugins_instances.get(plugin_id)
        if plugin is not None:
            return plugin.getPluginPath()
        path = self.plugins_dir / plugin_id
        return str(path) if path.exists() else None

    def getPluginSetting(self, plugin_id: str, key: str, default_value: Any = None):
        try:
            from plugin_settings import get_setting
            return get_setting(plugin_id, key, default_value)
        except Exception:
            format_exc()
        return default_value

    def setPluginSetting(self, plugin_id: str, key: str, value: Any):
        try:
            from plugin_settings import set_setting
            set_setting(plugin_id, key, value)
        except Exception:
            format_exc()

    def getAllPluginSettings(self, plugin_id: str) -> Dict[str, Any]:
        try:
            from plugin_settings import get_all_settings
            return get_all_settings(plugin_id) or {}
        except Exception:
            format_exc()
        return {}

    def clearPluginSettings(self, plugin_id: str):
        try:
            from plugin_settings import clear_settings
            clear_settings(plugin_id)
        except Exception:
            format_exc()

    def notify_plugins_changed(self):
        try:
            PluginsController.getInstance().updateNotificationRunnable()
        except Exception:
            pass

    def run_callback(self, callback: Callable, *args, on_ui_thread: bool = False,
                     on_queue: bool = False, **kwargs):
        try:
            if on_ui_thread:
                import android_utils
                android_utils.run_on_ui_thread(lambda: callback(*args, **kwargs))
            else:
                callback(*args, **kwargs)
        except Exception:
            format_exc()
