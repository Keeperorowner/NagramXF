"""elyxcore._dev_server — Elyx dev-server extension.

Extends ``dev_server.DevServer`` with Elyx-specific commands (incremental file
sync via hash comparison).
"""
from __future__ import annotations

import hashlib
import pathlib
from typing import Any, Dict, List, Optional

import dev_server
from elyxcore._plugin import ElyxPlugin

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


def _get_engine():
    from elyxcore._plugin_engine import ElyxEngine
    return ElyxEngine.getInstance()


def _get_plugin_types() -> List[type]:
    return [ElyxPlugin]


class ElyxDevServer(dev_server.DevServer):
    """Dev server with Elyx hot-reload sync helpers."""

    def __init__(self):
        super().__init__()
        self._hash_cache: Dict[str, tuple] = {}
        self.hash_cache = self._hash_cache

    def setup(self):
        self.start_server()

    def teardown(self):
        self.stop_server()

    def get_file_hash(self, file_path: str) -> str:
        """MD5 hash of a file (cached by path+mtime)."""
        path = pathlib.Path(file_path)
        if not path.exists():
            return ""
        try:
            stat = path.stat()
            key = str(path)
            cached = self._hash_cache.get(key)
            if cached and cached[0] == stat.st_mtime_ns:
                return cached[1]
            digest = hashlib.md5(path.read_bytes()).hexdigest()
            self._hash_cache[key] = (stat.st_mtime_ns, digest)
            return digest
        except Exception:
            return ""

    def cmpss(self, rs):
        """Compare a set of remote file hashes and return changed files."""
        changed = []
        for path, remote_hash in (rs or {}).items():
            local_hash = self.get_file_hash(str(path))
            if remote_hash != local_hash:
                changed.append(path)
        return changed

    def _process_command(self, command: str, client_socket=None):
        handlers = {
            "elyx_ping": self._handle_elyx_ping,
            "get_elyx_plugins": self._handle_get_elyx_plugins,
            "elyx_compare_folder": self._handle_elyx_compare_folder,
            "elyx_changes": self._handle_elyx_changes,
        }
        handler = handlers.get(command)
        if handler is not None:
            try:
                handler()
            except Exception:
                pass

    def _handle_elyx_ping(self, *_args, **_kwargs):
        return {"pong": True}

    def _handle_get_elyx_plugins(self, *_args, **_kwargs):
        try:
            engine = _get_engine()
            return list(engine._plugins_instances.keys())
        except Exception:
            return []

    def _handle_elyx_compare_folder(self, *_args, **_kwargs):
        return {}

    def _handle_elyx_changes(self, *_args, **_kwargs):
        return {}
