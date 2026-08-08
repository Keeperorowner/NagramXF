"""Pack sdk_pysrc/ into the plugin SDK archives shipped in assets/plugins_pysdk.

The Java host (``PythonPluginsEngine``) unpacks ``sdk-<abi>.zip`` into the app's
files dir and then imports the SDK modules from there.  Two constraints come
from that side and are enforced here:

  - ``isSdkDirValid`` accepts a required module only as ``.so`` or ``.pyc``
    (see ``sdkModuleExists``), never as bare ``.py``.  So ``_sdk_version``,
    ``base_plugin`` and ``plugin_settings`` must ship precompiled.
  - The bytecode magic must match the embedded interpreter (Chaquopy 3.11), so
    this script refuses to run on any other Python version.

``v.txt`` is written both inside the archive and next to it, and is derived from
``_sdk_version.__version__`` so the two can never drift apart.

Usage:
    py -3.11 Tools/build-plugin-sdk-zip.py [--check]

``--check`` rebuilds into a temp dir and compares against the committed
archives without writing anything (useful in CI).
"""

from __future__ import annotations

import argparse
import hashlib
import py_compile
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "sdk_pysrc"
OUT_DIR = ROOT / "TMessagesProj" / "src" / "main" / "assets" / "plugins_pysdk"
ABIS = ("arm64-v8a", "armeabi-v7a")

# Kept in sync with PythonPluginsEngine.SDK_REQUIRED_MODULES.
REQUIRED_MODULES = ("_sdk_version", "base_plugin", "plugin_settings")

REQUIRED_PYTHON = (3, 11)

# Fixed timestamp so the archive is byte-reproducible across machines.
ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)


def read_version() -> str:
    text = (SRC / "_sdk_version.py").read_text(encoding="utf-8")
    match = re.search(r'^__version__\s*=\s*"([^"]+)"', text, re.MULTILINE)
    if not match:
        raise SystemExit("Could not find __version__ in sdk_pysrc/_sdk_version.py")
    return match.group(1)


def source_files() -> list[Path]:
    files = [
        path
        for path in SRC.rglob("*.py")
        if "__pycache__" not in path.parts
    ]
    return sorted(files, key=lambda path: path.relative_to(SRC).as_posix())


def compile_required(stage: Path) -> None:
    """Byte-compile the modules the host requires as .pyc."""
    for module in REQUIRED_MODULES:
        src = SRC / f"{module}.py"
        if not src.exists():
            raise SystemExit(f"Required module missing from sdk_pysrc: {module}.py")
        # dont_write_bytecode-safe: write straight to the staging dir, and keep
        # the recorded filename relative so the archive carries no local paths.
        py_compile.compile(
            str(src),
            cfile=str(stage / f"{module}.pyc"),
            dfile=f"{module}.py",
            doraise=True,
        )


def build_archive(dest: Path, version: str) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        stage = Path(tmp)
        compile_required(stage)

        dest.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as archive:

            def write(arcname: str, data: bytes) -> None:
                info = zipfile.ZipInfo(arcname, date_time=ZIP_DATE_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                archive.writestr(info, data)

            for path in source_files():
                write(path.relative_to(SRC).as_posix(), path.read_bytes())
            for module in REQUIRED_MODULES:
                write(f"{module}.pyc", (stage / f"{module}.pyc").read_bytes())
            write("v.txt", version.encode("utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify the committed archives match sdk_pysrc without writing",
    )
    args = parser.parse_args()

    if sys.version_info[:2] != REQUIRED_PYTHON:
        raise SystemExit(
            "This script must run on Python "
            f"{REQUIRED_PYTHON[0]}.{REQUIRED_PYTHON[1]} to match the embedded "
            f"Chaquopy interpreter (running {sys.version_info[0]}.{sys.version_info[1]}). "
            "Try: py -3.11 Tools/build-plugin-sdk-zip.py"
        )

    if not SRC.is_dir():
        raise SystemExit(f"Source tree not found: {SRC}")

    version = read_version()
    files = source_files()

    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            reference = Path(tmp) / "sdk.zip"
            build_archive(reference, version)
            expected = sha256(reference)
            failures = []
            for abi in ABIS:
                shipped = OUT_DIR / f"sdk-{abi}.zip"
                if not shipped.exists():
                    failures.append(f"{shipped.name}: missing")
                elif sha256(shipped) != expected:
                    failures.append(f"{shipped.name}: differs from sdk_pysrc")
            v_txt = OUT_DIR / "v.txt"
            if not v_txt.exists() or v_txt.read_text(encoding="utf-8").strip() != version:
                failures.append(f"v.txt: does not match __version__ ({version})")
            if failures:
                print("SDK archives are stale:")
                for failure in failures:
                    print(f"  - {failure}")
                print("Run: py -3.11 Tools/build-plugin-sdk-zip.py")
                return 1
        print(f"SDK archives are up to date (v{version}, {len(files)} modules).")
        return 0

    # The archives are byte-identical across ABIs (pure Python, no native code),
    # so build once and copy.
    primary = OUT_DIR / f"sdk-{ABIS[0]}.zip"
    build_archive(primary, version)
    for abi in ABIS[1:]:
        shutil.copyfile(primary, OUT_DIR / f"sdk-{abi}.zip")
    (OUT_DIR / "v.txt").write_text(version, encoding="utf-8")

    print(f"Built plugin SDK v{version} ({len(files)} modules)")
    for abi in ABIS:
        path = OUT_DIR / f"sdk-{abi}.zip"
        print(f"  {path.name}: {path.stat().st_size} bytes  sha256={sha256(path)[:16]}")
    print(f"  v.txt: {version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
