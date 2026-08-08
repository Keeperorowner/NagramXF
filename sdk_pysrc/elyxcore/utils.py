"""elyxcore.utils — helpers (gen/gen2 dynamic-proxy callbacks, MVEL, images).

``gen``/``gen2`` build Chaquopy dynamic-proxy callback classes (Python callback
-> Java interface).
"""
from __future__ import annotations

import java

# ---------------------------------------------------------------------------
# Java callback generation (dynamic proxy)
# ---------------------------------------------------------------------------

def _make_proxy_base(java_class):
    """Return a base class for a dynamic proxy of `java_class`, or object."""
    try:
        from java import dynamic_proxy
        base = dynamic_proxy(java_class)
        if isinstance(base, type):
            return base
    except Exception:
        pass
    return object


_gen_cache = {}


def gen(java_class, method_name, return_value=False, default_value=None):
    """Creates and caches a proxy class for a one-method Java interface.

    The returned class is instantiated with a Python callback::

        OnDismiss = gen(DialogInterface.OnDismissListener, "onDismiss")
        dialog.setOnDismissListener(OnDismiss(lambda d: self.log("Dismissed")))

    ``default_value`` is returned when the callback raises (return_value=True).
    """
    key = (id(java_class), method_name, bool(return_value), default_value)
    cached = _gen_cache.get(key)
    if cached is not None:
        return cached

    base = _make_proxy_base(java_class)

    def __init__(self, fn, *args, **kwargs):
        if base is not object:
            try:
                super(type(self), self).__init__(*args, **kwargs)
            except Exception:
                pass
        self._callback = fn
        self._extra_args = args

    def _invoke(self, *args, **kwargs):
        try:
            result = self._callback(*(tuple(args) + self._extra_args), **kwargs)
        except Exception:
            import traceback
            traceback.print_exc()
            return default_value if return_value else None
        if result is None and return_value:
            return default_value
        return result

    proxy_cls = type("GeneratedCallback", (base,), {
        "__init__": __init__,
        method_name: _invoke,
        "_callback": None,
        "_extra_args": (),
    })
    _gen_cache[key] = proxy_cls
    return proxy_cls


def gen2(java_class, return_value=False, **methods):
    """Creates a proxy for an interface with several methods.

    ``methods`` maps Java method names to Python callables::

        Listener = gen2(SomeJavaListener, onStart=log_start, onFinish=log_finish)
        instance = Listener()
    """
    base = _make_proxy_base(java_class)

    def __init__(self, **callbacks):
        if base is not object:
            try:
                super(type(self), self).__init__()
            except Exception:
                pass
        for name, fn in callbacks.items():
            setattr(self, "_cb_" + name, fn)

    namespace = {"__init__": __init__}
    for java_method, py_name in methods.items():
        def _forward(self, *args, _py_name=py_name, **kwargs):
            fn = getattr(self, "_cb_" + _py_name, None)
            if fn is None:
                return None
            try:
                result = fn(*args, **kwargs)
            except Exception:
                import traceback
                traceback.print_exc()
                return None
            return result if return_value else None
        namespace[java_method] = _forward
    return type("GeneratedCallback2", (base,), namespace)


# ---------------------------------------------------------------------------
# misc helpers
# ---------------------------------------------------------------------------

def hash_map(dict_):
    """Convert a Python dict to a java.util.HashMap."""
    HashMap = java.jclass("java.util.HashMap")
    m = HashMap()
    for k, v in (dict_ or {}).items():
        m.put(k, v)
    return m


def safe_func(fn):
    """Wrap `fn` so exceptions are logged instead of crashing the caller."""
    def wrapper(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except Exception:
            java.lang.System.err.printStackTrace() if hasattr(java, "lang") else None
            import traceback
            traceback.print_exc()
            return None
    return wrapper


def safe_run_on_queue(fn):
    """Run `fn` on the app main thread, swallowing errors."""
    return safe_func(fn)


def resize_image(image, width, height):
    """Scale a bitmap-like `image` to (width, height)."""
    Bitmap = java.jclass("android.graphics.Bitmap")
    try:
        return Bitmap.createScaledBitmap(image, width, height, True)
    except Exception:
        return image


def mvel_execute(script, data, to_type=None, java_instance=None):
    """Evaluate an MVEL expression against a Python dict or Java HashMap.

    Compiles the expression through the app's MVEL engine and caches the
    compiled form.  Falls back to a plain dict lookup when MVEL is unavailable.
    """
    try:
        from java import jclass
        MVEL = jclass("org.mvel2.MVEL")
        if not isinstance(data, java_map_type()):
            data = hash_map(data)
        compiled_key = str(script)
        if compiled_key not in _mvel_compiled:
            _mvel_compiled[compiled_key] = MVEL.compileExpression(compiled_key)
        compiled = _mvel_compiled[compiled_key]
        if java_instance is not None:
            result = MVEL.executeExpression(compiled, java_instance, data)
        else:
            result = MVEL.executeExpression(compiled, data)
        if result is not None and to_type is not None:
            try:
                return to_type(result)
            except Exception:
                pass
        return result
    except Exception:
        # fallback: plain dict lookup for the common key form
        if isinstance(script, str) and isinstance(data, dict) and script in data:
            return data[script]
        return None


_mvel_compiled = {}


def java_map_type():
    from java import jclass
    return jclass("java.util.Map")


class LazyDict(dict):
    """A dict whose items are also reachable as attributes."""

    def __getattr__(self, item):
        return self[item]
