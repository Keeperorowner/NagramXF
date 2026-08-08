"""elyxcore.localization — Strings (plugin localization facade).

Locale-aware, read-only lookup object per the docs::

    catalog = Strings({
        "en": {"hello": "Hello, {name}!"},
        "ru": {"hello": "Привет, {name}!"},
    })

    catalog("hello", name="Alice")            # format with kwargs
    catalog.get("hello")                       # current locale or "en"
    catalog.get_with_locale("hello", locale="ru")
    catalog.pluralize(5, "file_forms")
"""
from __future__ import annotations

import java

LocaleController = java.jclass("org.telegram.messenger.LocaleController")


def dlog(msg):
    print("[elyx] %s" % msg)


def pluralization_string(number, words):
    """Telegram-style plural selection over a words list [one, few, many]."""
    n = abs(int(number))
    if n % 10 == 1 and n % 100 != 11:
        return words[0] if len(words) > 0 else ""
    if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14):
        return words[1] if len(words) > 1 else (words[0] if words else "")
    return words[-1] if words else ""


class Strings:
    """Locale-aware string lookup over a ``{locale: {key: value}}`` table."""

    def __init__(self, all_strings, plugin_id=None):
        self.all_strings = all_strings if all_strings is not None else {}
        self.plugin_id = plugin_id

    def get_current_language(self):
        try:
            return LocaleController.getInstance().getCurrentLocaleInfo().getLangCode()
        except Exception:
            return "en"

    def _table_for_locale(self, locale=None):
        """Return the string table for a locale (falls back to en)."""
        lang = locale or self.get_current_language()
        if isinstance(self.all_strings, dict):
            table = self.all_strings.get(lang)
            if table is None:
                table = self.all_strings.get("en", self.all_strings)
            if isinstance(table, dict):
                return table
        return self.all_strings

    def get(self, key, default=None):
        """Look up a key in the current locale table, then English, then the key itself."""
        table = self._table_for_locale()
        if isinstance(table, dict) and key in table:
            return table[key]
        try:
            val = LocaleController.getString(key)
        except Exception:
            val = None
        if val:
            return val
        return default if default is not None else key

    def get_with_locale(self, key, default=None, locale=None):
        if locale is None:
            locale = self.get_current_language()
        table = self._table_for_locale(locale)
        if isinstance(table, dict) and key in table:
            return table[key]
        try:
            val = LocaleController.getString(key)
        except Exception:
            val = None
        if val:
            return val
        return default if default is not None else key

    def __getattr__(self, key):
        if key.startswith("_"):
            raise AttributeError(key)
        return self.get(key)

    def __getitem__(self, key):
        return self.get(key, None)

    def __call__(self, key, default=None, locale=None, *args, **kwargs):
        s = self.get_with_locale(key, None, locale)
        if s is None:
            s = default if default is not None else key
        if args or kwargs:
            try:
                return s.format(*args, **kwargs)
            except (AttributeError, KeyError, IndexError):
                return s
        return s

    def pluralize(self, number, key):
        words = self.get(key, None)
        if isinstance(words, (list, tuple)):
            form = pluralization_string(number, words)
        elif isinstance(words, str):
            form = pluralization_string(number, words.split("|"))
        else:
            form = str(words)
        return "%s %s" % (number, form)
