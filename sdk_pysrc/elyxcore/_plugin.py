"""elyxcore._plugin — ElyxPlugin: structured-plugin model for Elyx archives.

``ElyxPlugin`` extends ``base_plugin.BasePlugin`` and adds archive loading,
metadata/refmap parsing, string localization, asset environment variables and
wheel dependency installation.  It backs the ``elyx`` facade::

    from elyx import assets, metainfo, refmap, settings, strings
"""
from __future__ import annotations

import json
import os
import pathlib
import re
import shutil
import sys
import zipfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Union

from java import jclass
from traceback import format_exc

import android_utils
import base_plugin
from base_plugin import BasePlugin
from elyxcore.assets import Asset, Assets, AssetsDirNotFoundException
from elyxcore.localization import Strings
from elyxcore.settings import SettingsController

BuildVars = jclass("org.telegram.messenger.BuildVars")

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


class InvalidPluginError(Exception):
    """Raised when a plugin archive or directory is invalid."""

    def __init__(self, message: str = ""):
        super().__init__(message)

    @staticmethod
    def plugin_class_not_found(main_file_path: str) -> "InvalidPluginError":
        return InvalidPluginError("No plugin class found in %s" % main_file_path)


class ValidationError(Exception):
    pass


class ArchiveEncryptionError(Exception):
    pass


class SecurityError(Exception):
    pass


class RequiredPlugin:
    """A required plugin dependency."""

    def __init__(self, plugin_id: str, required_version: str = ""):
        self.plugin_id = plugin_id
        self.required_version = required_version


@dataclass
class PluginValidationResult:
    valid: bool = True
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


@dataclass
class ElyxPluginStructure:
    main_file: Optional[pathlib.Path] = None
    strings: Optional[pathlib.Path] = None
    assets: Optional[pathlib.Path] = None
    lib: Optional[pathlib.Path] = None
    files: Dict[str, Any] = field(default_factory=dict)


_BASE_METAINFO = {"id", "name", "version"}


def dlog(message: str):
    try:
        android_utils.log("[ElyxPlugin] %s" % message)
    except Exception:
        pass


def _install_local_wheels(wheels_path: pathlib.Path, plugin_id: str):
    """Extract bundled .whl files and add them to sys.path."""
    if not wheels_path.is_dir():
        return
    whl_files = list(wheels_path.glob("*.whl"))
    if not whl_files:
        return
    for whl in whl_files:
        try:
            extract_dir = wheels_path / whl.stem
            if extract_dir.exists():
                shutil.rmtree(str(extract_dir))
            extract_dir.mkdir(exist_ok=True)
            with zipfile.ZipFile(str(whl), "r") as zf:
                for info in zf.infolist():
                    if info.filename.startswith("..") or os.path.isabs(info.filename):
                        raise SecurityError("Path traversal in wheel: %s" % info.filename)
                zf.extractall(str(extract_dir))
            if str(extract_dir) not in sys.path:
                sys.path.insert(0, str(extract_dir))
        except Exception:
            format_exc()


def _get_wheels_lib_dir(plugin_dir: pathlib.Path) -> Optional[pathlib.Path]:
    lib_dir = plugin_dir / "lib"
    return lib_dir if lib_dir.is_dir() else None


def _resolve_description(description: str, strings: Optional[Dict[str, Any]] = None,
                         lang: str = "en") -> str:
    """Replace ``{key}`` placeholders in a description with string values."""
    if not description:
        return ""

    def replacer(match):
        key = match.group(1)
        if strings:
            if isinstance(strings, dict):
                lang_strings = strings.get(lang, strings.get("en", strings))
                if isinstance(lang_strings, dict) and key in lang_strings:
                    return str(lang_strings[key])
        return match.group(0)

    return re.sub(r"\{(\w+)\}", replacer, description)


class ElyxPlugin(BasePlugin):
    """Structured Elyx plugin: archive/metadata/strings/assets support."""

    def __init__(self, plugin_id: str = "", plugin_path: str = ""):
        super().__init__()
        self._plugin_id = plugin_id
        self._plugin_path = plugin_path
        self.__metainfo: Dict[str, Any] = {}
        self.__strings: Optional[Strings] = None
        self.__refmap: Dict[str, Any] = {}
        self.__structure: Optional[ElyxPluginStructure] = None
        self.__validation_result: Optional[PluginValidationResult] = None
        self.plugin_strings: Dict[str, str] = {}

    # ---- BasePlugin compatibility ------------------------------------------

    @property
    def plugin_id(self) -> str:
        return self._plugin_id or self.id or ""

    def getId(self) -> str:
        return self.plugin_id

    def getPluginPath(self) -> str:
        return self._plugin_path

    def init(self, engine: Any, plugin_path: str):
        self._plugin_path = plugin_path
        path = pathlib.Path(plugin_path)
        if not self._plugin_id:
            self._plugin_id = path.name
        self.id = self._plugin_id
        self.__metainfo = self.get_metainfo()
        self.__strings = self.get_strings()
        self.__structure = self.load_structure(path)
        wheels_dir = _get_wheels_lib_dir(path)
        if wheels_dir:
            _install_local_wheels(wheels_dir, self._plugin_id)

    # ---- metadata ----------------------------------------------------------

    def get_metainfo(self) -> Dict[str, Any]:
        if self.__metainfo:
            return self.__metainfo
        path = pathlib.Path(self._plugin_path)
        for name in ("metainfo.json", "metainfo.yml", "metainfo.yaml", "meta.json", "plugin.ini"):
            candidate = path / name
            if not candidate.exists():
                continue
            try:
                if name.endswith(".json"):
                    self.__metainfo = json.loads(candidate.read_text(encoding="utf-8"))
                elif name.endswith((".yml", ".yaml")):
                    import yaml
                    self.__metainfo = yaml.safe_load(candidate.read_text(encoding="utf-8"))
                else:
                    raw = {}
                    for line in candidate.read_text(encoding="utf-8").splitlines():
                        if "=" in line:
                            k, _, v = line.partition("=")
                            raw[k.strip()] = v.strip()
                    self.__metainfo = raw
                return self.__metainfo
            except Exception:
                format_exc()
        # fall back to parsing from main.py docstring
        main_file = path / "main.py"
        if main_file.exists():
            try:
                from extera_utils.metadata_parser import get_metadata
                meta = get_metadata(main_file.read_text(encoding="utf-8"))
                if meta:
                    self.__metainfo = meta
            except Exception:
                format_exc()
        return self.__metainfo

    def get_refmap(self) -> Dict[str, Any]:
        if self.__refmap:
            return self.__refmap
        path = pathlib.Path(self._plugin_path)
        for name in ("refmap.json", "refmap.yml", "refmap.yaml"):
            candidate = path / name
            if not candidate.exists():
                continue
            try:
                if name.endswith(".json"):
                    self.__refmap = json.loads(candidate.read_text(encoding="utf-8"))
                else:
                    import yaml
                    self.__refmap = yaml.safe_load(candidate.read_text(encoding="utf-8"))
                return self.__refmap
            except Exception:
                format_exc()
        return self.__refmap

    # ---- strings -----------------------------------------------------------

    def get_strings(self) -> Strings:
        if self.__strings is not None:
            return self.__strings
        path = pathlib.Path(self._plugin_path)
        all_strings: Dict[str, Dict[str, str]] = {}
        strings_dir = path / "strings"
        if strings_dir.is_dir():
            for lang_file in strings_dir.iterdir():
                if lang_file.suffix in (".json", ".yml", ".yaml"):
                    try:
                        if lang_file.suffix == ".json":
                            data = json.loads(lang_file.read_text(encoding="utf-8"))
                        else:
                            import yaml
                            data = yaml.safe_load(lang_file.read_text(encoding="utf-8"))
                        if isinstance(data, dict):
                            all_strings[lang_file.stem] = data
                    except Exception:
                        format_exc()
        self.__strings = Strings(all_strings)
        return self.__strings

    # ---- environment -------------------------------------------------------

    def get_environment_vars(self) -> Dict[str, Any]:
        path = pathlib.Path(self._plugin_path)
        env: Dict[str, Any] = {}
        assets_path = path / "assets"
        if assets_path.is_dir():
            try:
                env["assets"] = Assets(assets_path)
            except AssetsDirNotFoundException:
                pass
        env["strings"] = self.get_strings()
        env["metainfo"] = self.get_metainfo()
        env["refmap"] = self.get_refmap()
        env["settings"] = SettingsController(self.plugin_id)
        env["plugin_id"] = self.plugin_id
        return env

    # ---- structure ---------------------------------------------------------

    def load_structure(self, path: pathlib.Path) -> ElyxPluginStructure:
        structure = ElyxPluginStructure()
        main_file = path / "main.py"
        if not main_file.exists():
            main_file = path / "__init__.py"
        if not main_file.exists():
            py_files = sorted(path.glob("*.py"))
            main_file = py_files[0] if py_files else None
        structure.main_file = main_file
        strings_dir = path / "strings"
        if strings_dir.is_dir():
            structure.strings = strings_dir
        assets_dir = path / "assets"
        if assets_dir.is_dir():
            structure.assets = assets_dir
        lib_dir = path / "lib"
        if lib_dir.is_dir():
            structure.lib = lib_dir
        structure.files = {p.name: p for p in path.iterdir() if p.is_file()}
        return structure

    # ---- validation --------------------------------------------------------

    def validate(self, raise_errors: bool = False) -> PluginValidationResult:
        result = PluginValidationResult()
        meta = self.get_metainfo()
        for key in _BASE_METAINFO:
            if key not in meta or not meta.get(key):
                result.errors.append("Missing required field: %s" % key)
        plugin_id = meta.get("id", self.plugin_id)
        if plugin_id and not re.match(r"^[a-zA-Z0-9_]{2,32}$", plugin_id):
            result.errors.append("Invalid plugin id: %s" % plugin_id)
        result.valid = len(result.errors) == 0
        if not result.valid and raise_errors:
            raise ValidationError("; ".join(result.errors))
        self.__validation_result = result
        return result

    def get_validation_result(self) -> Optional[PluginValidationResult]:
        if self.__validation_result is None:
            self.__validation_result = self.validate()
        return self.__validation_result

    # ---- archive loading ---------------------------------------------------

    @classmethod
    def from_archive(cls, archive_path: str, password: str = "", raise_errors: bool = False) -> "ElyxPlugin":
        archive = pathlib.Path(archive_path)
        plugin_id = archive.stem
        with zipfile.ZipFile(str(archive), "r") as zf:
            if password:
                zf.setpassword(password.encode("utf-8"))
            for member in zf.namelist():
                if member.startswith("..") or os.path.isabs(member):
                    raise SecurityError("Path traversal in archive: %s" % member)
            zf.extractall(str(archive.parent))
        plugin_dir = archive.parent / plugin_id
        plugin = cls(plugin_id, str(plugin_dir))
        plugin.__structure = plugin.load_structure(plugin_dir)
        return plugin

    def is_archive(self) -> bool:
        return bool(self._plugin_path) and self._plugin_path.endswith((".plzip", ".zip"))

    def get_file_content(self, file_name: str, encoding: str = "utf-8") -> Optional[str]:
        path = pathlib.Path(self._plugin_path)
        p = path / file_name
        if not p.exists():
            return None
        try:
            return p.read_text(encoding=encoding)
        except Exception:
            format_exc()
            return None

    def get_icon_path(self) -> Optional[str]:
        meta = self.get_metainfo()
        icon = meta.get("icon")
        if icon:
            candidate = pathlib.Path(self._plugin_path) / icon
            if candidate.exists():
                return str(candidate)
        return None

    def is_custom_icon(self) -> bool:
        return self.get_icon_path() is not None
