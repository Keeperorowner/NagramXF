"""metadata_parser — parse plugin metadata from a ``.py`` source file via AST.

The Java host (``PythonPluginsEngine.parsePluginMetadata``) calls
``get_metadata(file_path)`` and reads a dict whose keys are the plain names
(no leading underscores)::

    id, name, version, author, description, icon,
    app_version, sdk_version, requirements, min_version

``requirements`` is returned as a comma-joined string (the Java side splits it).
"""
from __future__ import annotations

import ast

# module-level ``__x__`` name -> output key expected by the Java host
_ATTR_MAP = {
    "__id__": "id",
    "__name__": "name",
    "__version__": "version",
    "__author__": "author",
    "__description__": "description",
    "__icon__": "icon",
    "__app_version__": "app_version",
    "__sdk_version__": "sdk_version",
    "__requirements__": "requirements",
    "__min_version__": "min_version",
}


def _PKs():
    """Allowed metadata keys."""
    return list(_ATTR_MAP.values())


def _normalize_value(key, value):
    if value is None:
        return None
    if key == "requirements":
        if isinstance(value, (list, tuple)):
            return ",".join(str(v) for v in value)
        return str(value)
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def get_metadata(file_path):
    """Parse module-level metadata assignments and return a flat dict."""
    result = {}
    try:
        with open(file_path, "r", encoding="utf-8") as fh:
            source = fh.read()
    except Exception:
        return result

    try:
        tree = ast.parse(source)
    except Exception:
        return result

    for node in getattr(tree, "body", []):
        if not isinstance(node, ast.Assign):
            continue
        for target in node.targets:
            if not isinstance(target, ast.Name) or target.id not in _ATTR_MAP:
                continue
            key = _ATTR_MAP[target.id]
            try:
                value = ast.literal_eval(node.value)
            except Exception:
                value = None
            result[key] = _normalize_value(key, value)

    return result
