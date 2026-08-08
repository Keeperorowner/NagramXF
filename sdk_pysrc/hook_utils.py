"""hook_utils — Java reflection helpers.

Pure-Java reflection wrappers used by plugins for Xposed-style access to
non-public members.  Every function is defensive: any failure yields ``None``
or ``False`` instead of raising.
"""
from __future__ import annotations

import traceback

from java import jclass
from android_utils import log


def find_class(class_name):
    """Return a Java ``Class`` for *class_name*, or ``None`` if not found.

    Chaquopy's ``jclass`` returns a Python *type* (JavaClass) which only exposes
    the class's own members, not ``java.lang.Class`` instance methods such as
    ``getDeclaredMethod``.  Call ``getClass()`` so callers receive the actual
    ``java.lang.Class`` object.
    """
    if not class_name:
        return None
    try:
        import time
        start = time.perf_counter()
        cls = jclass(class_name)
        elapsed = (time.perf_counter() - start) * 1000.0
        if elapsed > 50.0:
            log("find_class(%s) took %.1fms" % (class_name, elapsed))
        return cls.getClass()
    except Exception:
        return None


def _find_field(clazz, field_name):
    """Walk the class hierarchy looking for a declared field."""
    if clazz is None or not field_name:
        return None
    current = clazz
    while current is not None:
        try:
            field = current.getDeclaredField(field_name)
            if field is not None:
                return field
        except Exception:
            pass
        try:
            current = current.getSuperclass()
        except Exception:
            current = None
    return None


def get_private_field(obj, field_name):
    """Get a (possibly private) instance field value, searching the hierarchy."""
    try:
        if obj is None:
            return None
        field = _find_field(obj.getClass(), field_name)
        if field is None:
            return None
        field.setAccessible(True)
        return field.get(obj)
    except Exception:
        return None


def set_private_field(obj, field_name, new_value):
    """Set a (possibly private) instance field value. Returns success bool."""
    try:
        if obj is None:
            return False
        field = _find_field(obj.getClass(), field_name)
        if field is None:
            return False
        field.setAccessible(True)
        field.set(obj, new_value)
        return True
    except Exception:
        return False


def get_static_private_field(clazz, field_name):
    """Get a (possibly private) static field value."""
    try:
        field = _find_field(clazz, field_name)
        if field is None:
            return None
        field.setAccessible(True)
        return field.get(None)
    except Exception:
        return None


def set_static_private_field(clazz, field_name, new_value):
    """Set a (possibly private) static field value. Returns success bool."""
    try:
        field = _find_field(clazz, field_name)
        if field is None:
            return False
        field.setAccessible(True)
        field.set(None, new_value)
        return True
    except Exception:
        return False
