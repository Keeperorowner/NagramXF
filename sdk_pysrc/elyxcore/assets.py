"""elyxcore.assets — plugin asset files.

Represents the plugin's ``assets`` directory and individual asset files,
exposing path/content accessors plus Android drawable converters.
"""
from __future__ import annotations

import json
import string
import zipfile
from pathlib import Path

import java
import yaml

_JC = java.jclass

# Android / Telegram Java classes.
AndroidUtilities = _JC("org.telegram.messenger.AndroidUtilities")
ApplicationLoader = _JC("org.telegram.messenger.ApplicationLoader")
Bitmap = _JC("android.graphics.Bitmap")
BitmapDrawable = _JC("android.graphics.drawable.BitmapDrawable")
Drawable = _JC("android.graphics.drawable.Drawable")
File = _JC("java.io.File")
ImageLocation = _JC("org.telegram.messenger.ImageLocation")
RLottieDrawable = _JC("org.telegram.ui.Components.RLottieDrawable")
SvgHelper = _JC("org.telegram.messenger.SvgHelper")

# characters permitted in a normalized asset name
_SAFE_NAME_CHARS = frozenset(string.ascii_letters + string.digits + "_-.")


class AssetNotFoundException(Exception):
    def __init__(self, name, dir_path):
        super().__init__("Asset %r not found in %r" % (name, str(dir_path)))
        self.name = name
        self.dir_path = str(dir_path)


class AssetsDirNotFoundException(Exception):
    def __init__(self, dir_path):
        super().__init__("Assets directory %r not found" % str(dir_path))
        self.dir_path = str(dir_path)


def normalize_name(name):
    """Normalize a name: non-ASCII-letters/digits become ``_``."""
    return "".join(c if c.isalnum() and c.isascii() else "_" for c in str(name))


def load_data_file(path, plzip):
    """Read one entry from a plugin data archive (``.plzip``) and decode it."""
    with zipfile.ZipFile(str(plzip)) as zf:
        return zf.read(str(path)).decode("utf-8")


def _sVwCaL(data):
    """XOR-191 string deobfuscator."""
    return "".join(chr(x ^ 191) for x in data)


class Asset:
    """A single file inside a plugin's assets directory."""

    def __init__(self, dir_path, filename, name):
        self.dir_path = str(dir_path)
        self.filename = filename
        self.name = name

    @property
    def path(self) -> Path:
        return Path(self.dir_path) / self.filename

    @property
    def path_str(self) -> str:
        return str(self.path)

    @property
    def ext(self) -> str:
        return self.path.suffix.lstrip(".")

    @property
    def java_file(self):
        return File(self.path_str)

    @classmethod
    def from_path(cls, path):
        if hasattr(path, "getAbsolutePath"):
            path = path.getAbsolutePath()
        p = Path(str(path))
        return cls(str(p.parent), p.name, p.name)

    @classmethod
    def temp_asset_from_url(cls, url, filename):
        # downloads the URL into a temp asset dir; returns an Asset.
        data = None
        try:
            import requests
            data = requests.get(url).content
        except Exception:
            pass
        if data is None:
            return None
        import tempfile
        tmp = Path(tempfile.mkdtemp(prefix="elyx-asset-"))
        (tmp / filename).write_bytes(data)
        return cls(str(tmp), filename, filename)

    # ---- content accessors ----
    def content_bytes(self) -> bytes:
        return self.path.read_bytes()

    def content_string(self) -> str:
        return self.path.read_text(encoding="utf-8")

    def content_json(self):
        return json.loads(self.content_string())

    def content_yaml(self):
        return yaml.safe_load(self.content_string())

    def content(self):
        """Return parsed JSON/YAML, UTF-8 text, or raw bytes."""
        ext = self.ext.lower()
        if ext == "json":
            try:
                return self.content_json()
            except Exception:
                pass
        elif ext in ("yaml", "yml"):
            try:
                return self.content_yaml()
            except Exception:
                pass
        try:
            return self.content_string()
        except Exception:
            return self.content_bytes()

    # ---- Android conversions ----
    def to_drawable(self):
        return Drawable.createFromPath(self.path_str)

    def to_bitmap_drawable(self, width=32, height=32):
        drawable = self.to_drawable()
        bitmap = drawable.getBitmap()
        scaled = Bitmap.createScaledBitmap(bitmap, width, height, True)
        res = AndroidUtilities.getContext().getResources()
        return BitmapDrawable(res, scaled)

    def to_lottie_drawable(self, width=32, height=32):
        dp_w = AndroidUtilities.dp(width)
        dp_h = AndroidUtilities.dp(height)
        return RLottieDrawable(self.java_file, "", dp_w, dp_h, False, None)

    def to_svg_drawable(self, width=None, height=None):
        return SvgHelper.getDrawable(self.content_string(), width, height)

    def to_svg_bitmap(self, width=32, height=32, white=False):
        return SvgHelper.getBitmap(self.content_string(), white, width, height)

    def to_svg_thumb(self, color_key, alpha):
        return SvgHelper.getThumb(self.content_string(), color_key, alpha)

    def to_image_location(self):
        return ImageLocation.getForPath(self.path_str)

    def __repr__(self):
        return "<Asset %s>" % self.path_str

    def __str__(self):
        return self.path_str


class Assets:
    """A plugin's assets directory, addressable by name like a mapping."""

    def __init__(self, dir_path):
        self.dir_path = Path(dir_path)
        if not (self.dir_path.exists() and self.dir_path.is_dir()):
            raise AssetsDirNotFoundException(str(dir_path))
        self._files = {}
        self._dirs = {}
        self.names = {}
        for p in sorted(self.dir_path.iterdir()):
            if p.is_file():
                norm_stem = normalize_name(p.stem)
                norm_full = normalize_name(p.name)
                self._files[norm_stem] = p.name
                self._files[norm_full] = p.name
                self.names[norm_stem] = p.name
            elif p.is_dir():
                self._dirs[normalize_name(p.name)] = p
        self.files = sorted(set(self._files.values()))

    def get(self, name):
        parts = str(name).split("/")
        key = normalize_name(parts[0])
        if len(parts) > 1:
            if key not in self._dirs:
                raise AssetNotFoundException(name, str(self.dir_path))
            return Assets(str(self._dirs[key])).get("/".join(parts[1:]))
        if key in self._files:
            filename = self._files[key]
            return Asset(str(self.dir_path), filename, key)
        if key in self._dirs:
            return Assets(str(self._dirs[key]))
        raise AssetNotFoundException(name, str(self.dir_path))

    @property
    def parent(self):
        if str(self.dir_path) == self.dir_path.anchor:
            return None
        return Assets(str(self.dir_path.parent))

    def __getitem__(self, item):
        return self.get(item)

    def __getattr__(self, item):
        if item.startswith("_"):
            raise AttributeError(item)
        return self.get(item)

    def __contains__(self, item):
        try:
            self.get(item)
            return True
        except (AssetNotFoundException, AssetsDirNotFoundException):
            return False

    def __iter__(self):
        return iter(self.files)

    def __len__(self):
        return len(self.files) + len(self._dirs)

    def __repr__(self):
        return "<Assets %s>" % str(self.dir_path)

    def __str__(self):
        return str(self.dir_path)
