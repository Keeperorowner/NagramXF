"""elyxcore._importer — plugin-scoped import system for Elyx structured plugins.

Each installed plugin receives its own internal module namespace
``elyx_plugins.<plugin_id>.*`` so identically named files in different plugins
never collide in ``sys.modules``.  A meta-path finder resolves imports relative
to the calling plugin's root directory; ``elyx.get_environment()`` and
``elyx.import_module()`` are the public entry points.

Supported plugin-local module kinds (per docs):
  - ``.py`` source modules
  - ``.pyc`` bytecode modules (must match the CPython 3.11 magic number)
  - packages with ``__init__.py`` / ``__init__.pyc``
  - namespace directories
  - data files (``.json``/``.yaml``/``.yml``/``.txt``) imported as modules

Reserved top-level names are left to the normal runtime import machinery
(``android``, ``androidx``, ``base_plugin``, ``client_utils``, ``com``, ``de``,
``elyx``, ``hook_utils``, ``importlib``, ``java``, ``org``, ``ui``, ...).
"""
from __future__ import annotations

import contextvars
import importlib
import importlib.abc
import importlib.util
import json
import marshal
import pathlib
import sys
import types
from typing import Any, Dict, List, Optional, Tuple

from traceback import format_exc

import _sdk_version

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


PLUGIN_NAMESPACE = "elyx_plugins"
PUBLIC_MODULE = "elyx"
_MISSING = object()

_DATA_SUFFIXES = (".json", ".yaml", ".yml", ".txt")
_RESERVED_TOP_LEVEL = {
    "android", "androidx", "base_plugin", "client_utils", "com", "de",
    "elyx", "hook_utils", "importlib", "java", "org", "ui", "sys",
    "builtins", "os", "json", "pathlib", "typing", "types", "re",
}

# Context vars track the plugin currently being executed.
active_plugin_id: contextvars.ContextVar[Optional[str]] = contextvars.ContextVar(
    "active_plugin_id", default=None)

# Global state
_filename_roots: Dict[str, pathlib.Path] = {}
_plugin_roots: Dict[pathlib.Path, Any] = {}
_instances_by_id: Dict[str, Any] = {}
_builtins_by_plugin: Dict[str, dict] = {}


class _DataModule(types.ModuleType):
    """A module that serves a data file (JSON/YAML/TXT) from a plugin."""

    def __init__(self, fullname: str, path: pathlib.Path):
        super().__init__(fullname)
        self.__file__ = str(path)
        self._data_path = path
        self._content = None
        self._raw = None

    def _ensure_loaded(self):
        if self._content is None:
            suffix = self._data_path.suffix.lower()
            if suffix in (".json",):
                self._content = json.loads(self._data_path.read_text(encoding="utf-8"))
            elif suffix in (".yaml", ".yml"):
                import yaml
                self._content = yaml.safe_load(self._data_path.read_text(encoding="utf-8"))
            else:
                self._content = self._data_path.read_text(encoding="utf-8")
        return self._content

    def get(self, key: str, default=None):
        content = self._ensure_loaded()
        if isinstance(content, dict):
            return content.get(key, default)
        return default

    @property
    def content(self):
        return self._ensure_loaded()

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        content = self._ensure_loaded()
        if isinstance(content, dict) and name in content:
            return content[name]
        raise AttributeError(name)

    def __getitem__(self, key):
        content = self._ensure_loaded()
        if isinstance(content, dict):
            return content[key]
        raise KeyError(key)


class ElyxImporter(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    """Meta path finder/loader resolving modules relative to plugin roots."""

    def __init__(self):
        self._plugins_dir: Optional[pathlib.Path] = None
        self.instances_by_id: Dict[str, Any] = {}
        self.loader_state: Dict[str, Any] = {}

    # -- directory handling --------------------------------------------------

    def set_plugins_dir(self, plugins_dir: str):
        self._plugins_dir = pathlib.Path(plugins_dir)

    def _root_from_filename(self, filename: str) -> Optional[pathlib.Path]:
        if filename in _filename_roots:
            return _filename_roots[filename]
        for root in _plugin_roots:
            if str(root) in filename:
                return root
        return None

    def _plugin_for_root(self, root: pathlib.Path) -> Optional[Any]:
        return _plugin_roots.get(root)

    def register_plugin(self, plugin_id: str, plugin_root: str, plugin: Any):
        """Register a plugin so its modules resolve and get_caller_plugin works."""
        root_path = pathlib.Path(plugin_root)
        _plugin_roots[root_path] = plugin
        _filename_roots[str(root_path)] = root_path
        self.instances_by_id[plugin_id] = plugin
        _instances_by_id[plugin_id] = plugin

    def unload_plugin(self, plugin_id: str):
        instance = _instances_by_id.pop(plugin_id, None)
        self.instances_by_id.pop(plugin_id, None)
        if instance is not None:
            root = getattr(instance, "getPluginPath", None)
            root_path = None
            if callable(root):
                try:
                    root_path = pathlib.Path(root())
                except Exception:
                    root_path = None
            if root_path is not None:
                _plugin_roots.pop(root_path, None)
        # remove namespace modules for this plugin
        prefix = PLUGIN_NAMESPACE + "." + plugin_id
        for name in [k for k in list(sys.modules) if k == prefix or k.startswith(prefix + ".")]:
            sys.modules.pop(name, None)

    def unload_all(self):
        for plugin_id in list(self.instances_by_id.keys()):
            self.unload_plugin(plugin_id)

    def invalidate_caches(self):
        _filename_roots.clear()
        _plugin_roots.clear()
        self.loader_state.clear()

    # -- caller resolution ---------------------------------------------------

    def get_caller_root(self) -> Optional[pathlib.Path]:
        frame = sys._getframe(2)
        while frame is not None:
            filename = frame.f_code.co_filename
            root = self._root_from_filename(filename)
            if root is not None:
                return root
            frame = frame.f_back
        return None

    def get_caller_plugin(self) -> Optional[Any]:
        root = self.get_caller_root()
        if root is not None:
            plugin = _plugin_roots.get(root)
            if plugin is not None:
                return plugin
        plugin_id = active_plugin_id.get()
        if plugin_id is not None:
            return _instances_by_id.get(plugin_id)
        # walk stack as a final fallback
        frame = sys._getframe(1)
        while frame is not None:
            plugin_id = frame.f_globals.get("__plugin_id__")
            if plugin_id and plugin_id in _instances_by_id:
                return _instances_by_id[plugin_id]
            frame = frame.f_back
        return None

    # -- path resolution -----------------------------------------------------

    def _resolve_path(self, fullname: str, root: pathlib.Path) -> Optional[pathlib.Path]:
        parts = fullname.split(".")
        relative = pathlib.Path(*parts)
        candidates = [
            root / relative.with_suffix(".py"),
            root / relative / "__init__.py",
            root / relative.with_suffix(".pyc"),
            root / relative / "__init__.pyc",
        ]
        for candidate in candidates:
            if candidate.is_file():
                return candidate
        # data files
        for suffix in _DATA_SUFFIXES:
            candidate = root / (relative.name + suffix)
            if candidate.is_file():
                return candidate
        # namespace directory
        candidate = root / relative
        if candidate.is_dir():
            return candidate
        return None

    def _qualified(self, fullname: str) -> str:
        if fullname.startswith(PLUGIN_NAMESPACE + "."):
            return fullname[len(PLUGIN_NAMESPACE) + 1:]
        return fullname

    def find_spec(self, fullname, path=None, target=None):
        if not isenabled():
            return None
        if fullname.split(".")[0] in _RESERVED_TOP_LEVEL:
            return None
        root = self.get_caller_root()
        if root is None:
            return None
        if not self._root_is_plugin(fullname, root):
            return None
        resolved = self._resolve_path(self._qualified(fullname), root)
        if resolved is None:
            return None
        is_package = resolved.is_dir() or resolved.name == "__init__.py" or resolved.name == "__init__.pyc"
        spec = importlib.util.spec_from_loader(fullname, self, origin=str(resolved), is_package=is_package)
        spec.__plugin_root__ = str(root)
        return spec

    def _root_is_plugin(self, fullname: str, root: pathlib.Path) -> bool:
        # The calling module lives under a plugin root; every local resolution
        # is relative to that root.
        return True

    def create_module(self, spec):
        origin = spec.origin or ""
        if origin.endswith(_DATA_SUFFIXES):
            return _DataModule(spec.name, pathlib.Path(origin))
        module = types.ModuleType(spec.name)
        module.__spec__ = spec
        module.__loader__ = self
        module.__file__ = origin
        module.__package__ = spec.name.rpartition(".")[0]
        return module

    def exec_module(self, module):
        if not isenabled():
            raise ImportError("importer is disabled")
        path = pathlib.Path(module.__file__)
        suffix = path.suffix.lower()

        root = self._root_from_filename(str(path))
        plugin_id = None
        if root is not None:
            plugin = _plugin_roots.get(root)
            if plugin is not None:
                plugin_id = getattr(plugin, "getId", lambda: None)()
                if plugin_id is None:
                    plugin_id = getattr(plugin, "_plugin_id", None)

        if isinstance(module, _DataModule):
            module._ensure_loaded()
            return

        if path.is_dir():
            return

        if suffix == ".pyc":
            data = path.read_bytes()
            if data[:4] != importlib.util.MAGIC_NUMBER:
                raise ImportError("Incompatible bytecode file: %s" % path)
            code_obj = marshal.loads(data[16:])
        else:
            code_obj = compile(path.read_text(encoding="utf-8"), str(path), "exec")

        builtins = self._plugin_builtins(plugin_id) if plugin_id else None
        if builtins:
            module.__builtins__ = builtins
        token = None
        if plugin_id:
            token = active_plugin_id.set(plugin_id)
        try:
            exec(code_obj, module.__dict__)
        finally:
            if token is not None:
                active_plugin_id.reset(token)

    def _plugin_builtins(self, plugin_id: str) -> dict:
        if plugin_id in _builtins_by_plugin:
            return _builtins_by_plugin[plugin_id]
        real_builtins = __builtins__ if isinstance(__builtins__, dict) else __builtins__.__dict__
        scoped = dict(real_builtins)

        def scoped_import(name, globals=None, locals=None, fromlist=(), level=0):
            if level > 0:
                return self.import_module(name, package=(globals or {}).get("__name__"))
            return real_builtins["__import__"](name, globals, locals, fromlist, level)

        scoped["__import__"] = scoped_import
        _builtins_by_plugin[plugin_id] = scoped
        return scoped

    # -- public import helpers ----------------------------------------------

    def import_module(self, name: str, package: Optional[str] = None):
        """Import a module relative to the calling plugin.

        Falls back to the normal import machinery when no local module exists.
        """
        root = self.get_caller_root()
        module_name = name
        if package:
            module_name = package + "." + name.lstrip(".")
        if root is not None:
            qualified = self._qualified(module_name)
            resolved = self._resolve_path(qualified, root)
            if resolved is not None:
                if module_name in sys.modules:
                    return sys.modules[module_name]
                spec = importlib.util.spec_from_loader(module_name, self, origin=str(resolved),
                                                       is_package=resolved.is_dir() or resolved.name.startswith("__init__"))
                module = importlib.util.module_from_spec(spec)
                sys.modules[module_name] = module
                self.exec_module(module)
                return module
        # fall back to normal import
        return importlib.import_module(name)


# Singleton importer
importer: Optional[ElyxImporter] = None


def setup():
    """Initialize the Elyx importer and register it on sys.meta_path."""
    global importer
    if importer is None:
        importer = ElyxImporter()
        if importer not in sys.meta_path:
            sys.meta_path.insert(0, importer)


def setup_importer():
    setup()


def stop():
    global importer
    if importer is not None:
        importer.unload_all()
        if importer in sys.meta_path:
            sys.meta_path.remove(importer)
        importer = None


def stop_importer():
    stop()


def reset():
    global importer
    stop()
    _builtins_by_plugin.clear()
    _filename_roots.clear()
    _plugin_roots.clear()
    _instances_by_id.clear()


def register_plugin_root(plugin_id: str, plugin_root: str, plugin: Any):
    """Register a plugin root so ``_root_from_filename`` and caller resolution work."""
    if importer is None:
        setup()
    importer.register_plugin(plugin_id, plugin_root, plugin)


def get_caller_plugin():
    if importer is None:
        return None
    return importer.get_caller_plugin()


def get_environment(plugin_id: str) -> Optional[Dict[str, Any]]:
    """Return the environment vars dict for a plugin (LazyDict-compatible)."""
    if importer is None:
        return None
    plugin = importer.instances_by_id.get(plugin_id)
    if plugin is None:
        return None
    return getattr(plugin, "get_environment_vars", lambda: {})() or {}


def load_data_file(plugin_id: str, relative: str) -> bytes:
    if importer is None:
        raise ImportError("importer not initialized")
    plugin = importer.instances_by_id.get(plugin_id)
    if plugin is None:
        raise FileNotFoundError("plugin %s not registered" % plugin_id)
    root = None
    getter = getattr(plugin, "getPluginPath", None)
    if callable(getter):
        try:
            root = pathlib.Path(getter())
        except Exception:
            root = None
    if root is None:
        root = pathlib.Path(importer._plugins_dir or "plugins") / plugin_id
    p = root / relative
    if not p.exists():
        raise FileNotFoundError("Data file not found: %s" % p)
    return p.read_bytes()
