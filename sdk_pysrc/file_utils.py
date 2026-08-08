"""file_utils — app directories and small file I/O helpers for plugins.

Provides ``get_*_dir`` / ``read_file`` / ``write_file`` / ``list_dir`` helpers,
``plugin_data_dir``, and ``FilesController`` for per-extension file-open
handlers and custom file icons.
"""

from __future__ import annotations

import os

from java import jclass

from android_utils import log

PluginsController = jclass("com.exteragram.messenger.plugins.PluginsController")


def _app():
    ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")
    return ApplicationLoader.applicationContext


def get_files_dir():
    return _app().getFilesDir().getAbsolutePath()


def get_cache_dir():
    return _app().getCacheDir().getAbsolutePath()


def plugin_data_dir(plugin_id):
    """A private, persistent directory a plugin can use for its own data."""
    path = os.path.join(get_files_dir(), "plugins_data", str(plugin_id))
    try:
        os.makedirs(path, exist_ok=True)
    except Exception:
        pass
    return path


def get_plugins_dir():
    """Directory where installed .plugin files live."""
    path = os.path.join(get_files_dir(), "plugins")
    try:
        os.makedirs(path, exist_ok=True)
    except Exception:
        pass
    return path


def get_documents_dir():
    return os.path.join(get_files_dir(), "documents")


def get_images_dir():
    return os.path.join(get_files_dir(), "images")


def get_videos_dir():
    return os.path.join(get_files_dir(), "videos")


def get_audios_dir():
    return os.path.join(get_files_dir(), "audios")


def ensure_dir_exists(path):
    if path:
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            pass
    return path


def read_text(path, default=""):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return default


def write_text(path, text):
    directory = os.path.dirname(path)
    if directory:
        try:
            os.makedirs(directory, exist_ok=True)
        except Exception:
            pass
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    return path


def read_file(path, binary=False, default=None):
    """Read a file as text (default) or bytes; returns `default` on any error."""
    try:
        if binary:
            with open(path, "rb") as f:
                return f.read()
        with open(path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception as e:
        log("read_file(%s) failed: %s" % (path, e))
        return default


def read_file_bytes(path):
    return read_file(path, binary=True, default=None)


def write_file(path, content):
    """Write str or bytes to path (creating parent dirs as needed). Returns the path."""
    directory = os.path.dirname(path)
    if directory:
        try:
            os.makedirs(directory, exist_ok=True)
        except Exception:
            pass
    if isinstance(content, (bytes, bytearray)):
        with open(path, "wb") as f:
            f.write(content)
    else:
        with open(path, "w", encoding="utf-8") as f:
            f.write(str(content))
    return path


def write_file_bytes(path, content):
    return write_file(path, content)


def exists(path):
    return os.path.exists(path) if path else False


def delete_file(path):
    try:
        if path and os.path.exists(path):
            os.remove(path)
            return True
    except Exception:
        pass
    return False


def _matches_extensions(name, extensions):
    if not extensions:
        return True
    ext = os.path.splitext(name)[1].lstrip(".").lower()
    wanted = [str(e).lstrip(".").lower() for e in extensions]
    return ext in wanted


def list_dir(path, recursive=False, include_files=True, include_dirs=False, extensions=None):
    """List entries under *path*. Returns a list of absolute paths."""
    result = []
    if not path or not os.path.exists(path):
        return result
    try:
        if recursive:
            for root, dirs, files in os.walk(path):
                if include_dirs:
                    for d in dirs:
                        result.append(os.path.join(root, d))
                if include_files:
                    for f in files:
                        if _matches_extensions(f, extensions):
                            result.append(os.path.join(root, f))
        else:
            for name in os.listdir(path):
                full = os.path.join(path, name)
                if include_dirs and os.path.isdir(full):
                    result.append(full)
                elif include_files and os.path.isfile(full):
                    if _matches_extensions(name, extensions):
                        result.append(full)
    except Exception:
        pass
    return result


class FilesController:
    """File-extension open handlers and custom icons.

    API: register(FileInfo) -> secret; unregister(ext, secret); SUPPORT_ICONS;
    Place / FileInfo / OnClickArgs.  File opens arrive via the host bridge
    (``AndroidUtilities.openDocument`` -> ``dispatch()``); custom icons
    (``get_icon``) are registered through ``PluginsController.registerFileIcon``.
    """

    SUPPORT_ICONS = True
    DIRECT_FILE_ICONS = True

    class ExtensionAlreadyRegistered(Exception):
        pass

    class ExtensionNotRegistered(Exception):
        pass

    class SecretInvalid(Exception):
        pass

    class Place:
        UNKNOWN = 0
        ChatActivity = 1
        FilteredSearchView = 2
        SharedMediaLayout = 3
        SearchDownloadsContainer = 4
        ChannelAdminLogActivity = 5

    class FileInfo:
        def __init__(self, ext, on_click, whitelist_places=None, blacklist_places=None, get_icon=None):
            if whitelist_places and blacklist_places:
                raise ValueError("cannot use whitelist_places and blacklist_places together")
            if get_icon is not None and not FilesController.SUPPORT_ICONS:
                raise ValueError("get_icon requires FilesController.SUPPORT_ICONS")
            self.ext = ext
            self.on_click = on_click
            self.whitelist_places = list(whitelist_places or [])
            self.blacklist_places = list(blacklist_places or [])
            self.get_icon = get_icon

    class OnClickArgs:
        def __init__(self, place, file, file_name, message, activity, parent_fragment):
            self.place = place
            self.file = file
            self.file_name = file_name
            self.message = message
            self.activity = activity
            self.parent_fragment = parent_fragment

    _handlers = {}
    _secrets = {}
    _next_secret = 1
    _icon_ids = {}

    @classmethod
    def register(cls, file_info):
        if file_info is None or not getattr(file_info, "ext", None):
            raise cls.ExtensionAlreadyRegistered("no extension given")
        ext = str(file_info.ext).lower()
        if ext in cls._handlers:
            raise cls.ExtensionAlreadyRegistered("extension already registered: %s" % ext)
        cls._handlers[ext] = file_info
        secret = str(cls._next_secret)
        cls._next_secret += 1
        cls._secrets[ext] = secret
        cls._register_icon(ext, file_info)
        return secret

    @classmethod
    def unregister(cls, ext, secret=None):
        key = str(ext).lower()
        if key not in cls._handlers:
            raise cls.ExtensionNotRegistered("extension not registered: %s" % key)
        if secret is None or cls._secrets.get(key) != str(secret):
            raise cls.SecretInvalid("invalid secret for extension: %s" % key)
        del cls._handlers[key]
        del cls._secrets[key]
        cls._unregister_icon(key)

    @classmethod
    def _register_icon(cls, ext, file_info):
        get_icon = getattr(file_info, "get_icon", None)
        if get_icon is None or not cls.SUPPORT_ICONS:
            return
        try:
            drawable = get_icon()
            if drawable is None:
                return
            icon_id = PluginsController.registerFileIcon(ext, drawable)
            cls._icon_ids[ext] = icon_id
        except Exception:
            import traceback
            traceback.print_exc()

    @classmethod
    def _unregister_icon(cls, ext):
        if ext not in cls._icon_ids:
            return
        try:
            PluginsController.unregisterFileIcon(ext)
        except Exception:
            import traceback
            traceback.print_exc()
        finally:
            cls._icon_ids.pop(ext, None)

    @classmethod
    def dispatch(cls, place, file, file_name, message, activity, parent_fragment):
        """Java-side file-open bridge: dispatch an open event to the registered
        handler for the file's extension.  Returns True when a handler consumed
        the event (so the host skips its default open flow)."""
        if not file_name:
            return False
        ext = os.path.splitext(str(file_name))[1].lstrip(".").lower()
        info = cls._handlers.get(ext)
        if info is None:
            return False
        whitelist = list(getattr(info, "whitelist_places", None) or [])
        blacklist = list(getattr(info, "blacklist_places", None) or [])
        if whitelist and place not in whitelist:
            return False
        if blacklist and place in blacklist:
            return False
        args = cls.OnClickArgs(place, file, file_name, message, activity, parent_fragment)
        try:
            result = info.on_click(args)
            return result is True
        except Exception:
            import traceback
            traceback.print_exc()
            return False

    def _ex(self, *args, **kwargs):
        return None

    def create_methods(self, dex, proxy_type, _a, _b):
        return None
