"""elyxcore.settings — SettingsController.

A thin dict-like facade over ``plugin_settings`` keyed by a plugin id.
"""
from __future__ import annotations

import plugin_settings


class SettingsController:
    def __init__(self, plugin_id):
        self.plugin_id = plugin_id

    def get_settings(self):
        return plugin_settings.get_all_settings(self.plugin_id)

    def get_setting(self, key, default=None):
        return plugin_settings.get_setting(self.plugin_id, key, default)

    def get(self, key, default=None):
        return self.get_setting(key, default)

    def set_setting(self, key, value, reload_settings=False):
        plugin_settings.set_setting(self.plugin_id, key, value)
        if reload_settings:
            self._reload()
        return True

    def set(self, key, value, reload_settings=False):
        return self.set_setting(key, value, reload_settings)

    def __getitem__(self, item):
        return self.get_setting(item, None)

    def __setitem__(self, key, value):
        self.set_setting(key, value, False)

    def clear_settings(self):
        return plugin_settings.clear_settings(self.plugin_id)

    def __call__(self, key, default=None):
        return self.get_setting(key, default)

    def _reload(self):
        try:
            from java import jclass
            PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")
            PluginsController.getInstance().loadPluginSettings(self.plugin_id)
        except Exception:
            pass
