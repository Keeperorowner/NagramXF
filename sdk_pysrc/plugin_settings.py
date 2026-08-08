"""plugin_settings — persistent per-plugin settings stored as JSON files.

Each plugin has a settings JSON file under the plugins directory, guarded by a
module lock.
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path

_lock = threading.Lock()
_plugins_dir_path: str | None = None
_all_shared_prefs = None
_cache: dict[str, dict] = {}


def init(plugins_dir_path, all_shared_prefs):
    """Store the plugins dir and a shared-prefs bridge for later use.

    Returns the list of SharedPreferences keys migrated to JSON (always empty).
    """
    global _plugins_dir_path, _all_shared_prefs
    _plugins_dir_path = str(plugins_dir_path)
    _all_shared_prefs = all_shared_prefs
    _load_settings_from_file()
    return []


def _settings_file(plugin_id) -> Path:
    if _plugins_dir_path is None:
        raise RuntimeError("plugin_settings.init() was not called")
    return Path(_plugins_dir_path) / str(plugin_id) / "settings.json"


def _load_settings_from_file():
    """Load all plugins' settings from disk into the cache."""
    if _plugins_dir_path is None:
        return
    base = Path(_plugins_dir_path)
    for settings_file in base.glob("*/settings.json"):
        try:
            data = json.loads(settings_file.read_text(encoding="utf-8"))
        except Exception:
            data = {}
        _cache[settings_file.parent.name] = data


def _save_settings_to_file():
    """Persist the in-memory cache to disk."""
    if _plugins_dir_path is None:
        return
    for plugin_id, data in _cache.items():
        settings_file = Path(_plugins_dir_path) / str(plugin_id) / "settings.json"
        settings_file.parent.mkdir(parents=True, exist_ok=True)
        settings_file.write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def _settings_of(plugin_id) -> dict:
    pid = str(plugin_id)
    if pid not in _cache:
        _cache[pid] = {}
    return _cache[pid]


def get_setting(plugin_id, key, default):
    with _lock:
        return _settings_of(plugin_id).get(key, default)


def set_setting(plugin_id, key, value):
    with _lock:
        _settings_of(plugin_id)[key] = value
        _save_settings_to_file()


def get_all_settings(plugin_id):
    with _lock:
        return dict(_settings_of(plugin_id))


def set_all_settings(plugin_id, settings):
    with _lock:
        _cache[str(plugin_id)] = dict(settings)
        _save_settings_to_file()


def clear_settings(plugin_id):
    with _lock:
        _cache[str(plugin_id)] = {}
        _save_settings_to_file()


def _restore_plugin_settings(plugin_id, previous_settings):
    with _lock:
        _cache[str(plugin_id)] = dict(previous_settings)
