"""extera_utils.classes — class-proxy DSL for DexMaker-backed Java subclassing.

    from extera_utils.classes import (
        Base, java_subclass, joverride, joverload, jmethod,
        jMVELmethod, jMVELoverride, jclassbuilder,
        jfield, jgetmethod, jsetmethod,
        jconstructor, jpreconstructor, PyObj,
    )

Builds a real Java class with DexMaker via ``ClassProxy``:

  * ``@java_subclass(JavaClass, *interfaces)`` binds a Python class to a Java base class.
  * method decorators collect Java method/field/constructor declarations.
  * ``Base.new_instance()`` / ``new_java_instance()`` / ``from_java()`` manage the
    Python peer <-> Java instance lifecycle.
  * a ``Utilities.Callback3Return`` bridge dispatches Java method calls back into Python.

``super()`` inside overridden methods forwards to the generated ``SUPER_<name>``
bridge, so ``super().method(...)`` calls the original Java implementation.
"""
from __future__ import annotations

import inspect
import re
from typing import Any, Callable, Dict, List, Optional, Tuple

from java import dynamic_proxy, jarray, jboolean, jbyte, jchar, jdouble, jfloat, jint, jlong, jshort, jclass

ClassProxy = jclass("com.exteragram.messenger.plugins.utils.ClassProxy")
Utilities = jclass("org.telegram.messenger.Utilities")

# ---- primitive Java type objects (for ClassProxy.ProxyMethodSpec) ----
_PRIMITIVE_CLASS = {
    "void": None,
    "boolean": jboolean,
    "byte": jbyte,
    "char": jchar,
    "short": jshort,
    "int": jint,
    "long": jlong,
    "float": jfloat,
    "double": jdouble,
}

_MODIFIER_PUBLIC = 0x1

# ---- module-level gating ----
_enabled = True


def enable():
    global _enabled
    _enabled = True


def disable():
    global _enabled
    _enabled = False


def isenabled() -> bool:
    return _enabled


def _resolve_java_type(type_spec: Any) -> Any:
    """Resolve a type spec to a Java Class object (or None for void).

    Accepts primitive names (``"int"``), fully-qualified names (``"java.lang.String"``)
    and Java class objects returned by ``jclass(...)``.
    """
    if type_spec is None:
        return None
    if isinstance(type_spec, str):
        name = type_spec.strip()
        if name in _PRIMITIVE_CLASS:
            return _PRIMITIVE_CLASS[name]
        return jclass(name)
    return type_spec


def _type_display_name(type_spec: Any) -> str:
    """Return a canonical name for a type spec (for signature keys)."""
    if type_spec is None:
        return ""
    if isinstance(type_spec, str):
        return type_spec.strip()
    try:
        return str(type_spec.getName())
    except Exception:
        return str(type_spec)


def _to_java_class_array(class_objects: List[Any]):
    """Build a Java Class[] array from resolved class objects."""
    if not class_objects:
        return jarray(jclass("java.lang.Class"), [])
    return jarray(jclass("java.lang.Class"), list(class_objects))


def _to_java_string_list(values: List[str]):
    return jarray(jclass("java.lang.String"), list(values)) if values else jarray(jclass("java.lang.String"), [])


def _to_java_object_list(values):
    """Build a java.util.ArrayList from a Python sequence, or None if empty."""
    if not values:
        return None
    arr = jclass("java.util.ArrayList")()
    for v in values:
        arr.add(v)
    return arr


def _args_to_list(args: Any) -> list:
    """Convert a Java Object[] (as received by the callback) into a Python list."""
    if args is None:
        return []
    try:
        return [args[i] for i in range(len(args))]
    except Exception:
        return list(args)


# ---------------------------------------------------------------------------
# Method / field / constructor metadata collectors
# ---------------------------------------------------------------------------

_METHOD_ATTR = "__jc_method__"
_OVERRIDE_ATTR = "__jc_override__"
_OVERLOAD_ATTR = "__jc_overload__"
_CONSTRUCTOR_ATTR = "__jc_constructor__"
_PRECONSTRUCTOR_ATTR = "__jc_preconstructor__"
_CLASSBUILDER_ATTR = "__jc_classbuilder__"
_JFIELD_ATTR = "__jc_jfield__"


class _MethodDecl:
    """Declared Java method (override, overload or new method)."""

    __slots__ = ("java_name", "arg_types", "return_type", "py_name", "kind", "modifiers")

    def __init__(self, java_name: str, arg_types: Optional[List[Any]], return_type: Any,
                 py_name: str, kind: str, modifiers: int = _MODIFIER_PUBLIC):
        self.java_name = java_name
        self.arg_types = list(arg_types) if arg_types else []
        self.return_type = return_type
        self.py_name = py_name
        self.kind = kind  # "override" | "overload" | "method"
        self.modifiers = modifiers

    def signature_key(self) -> Tuple[str, Tuple[str, ...]]:
        """(java_name, tuple of canonical arg type names) — matches Java's
        ``buildMethodSignature`` minus the leading name."""
        return self.java_name, tuple(_type_display_name(t) for t in self.arg_types)

    @property
    def override_existing(self) -> bool:
        return self.kind in ("override", "overload")


class _FieldDecl:
    """Declared Java field (jfield)."""

    __slots__ = ("field_name", "field_type", "default", "accessors", "modifiers")

    def __init__(self, field_name: str, field_type: Any, default: Any,
                 accessors: Optional[List["_FieldAccessorDecl"]] = None,
                 modifiers: int = _MODIFIER_PUBLIC):
        self.field_name = field_name
        self.field_type = field_type
        self.default = default
        self.accessors = list(accessors) if accessors else []
        self.modifiers = modifiers


class _FieldAccessorDecl:
    """Declared field getter/setter (jgetmethod / jsetmethod)."""

    __slots__ = ("name", "getter", "modifiers")

    def __init__(self, name: str, getter: bool, modifiers: int = _MODIFIER_PUBLIC):
        self.name = name
        self.getter = getter
        self.modifiers = modifiers


class _MvelDecl:
    """Declared MVEL method or override (jMVELmethod / jMVELoverride)."""

    __slots__ = ("java_name", "return_type", "arguments", "code", "override", "modifiers")

    def __init__(self, java_name: str, return_type: Any, arguments: Optional[List[Tuple[str, Any]]],
                 code: str, override: bool, modifiers: int = _MODIFIER_PUBLIC):
        self.java_name = java_name
        self.return_type = return_type
        self.arguments = list(arguments) if arguments else []
        self.code = code
        self.override = override
        self.modifiers = modifiers

    @property
    def arg_types(self) -> List[Any]:
        return [t for _n, t in self.arguments]

    @property
    def argument_names(self) -> List[str]:
        return [n for n, _t in self.arguments]


class _CtorDecl:
    """Declared constructor hook (jconstructor / jpreconstructor)."""

    __slots__ = ("arg_types", "kind")

    def __init__(self, arg_types: Optional[List[Any]], kind: str):
        self.arg_types = list(arg_types) if arg_types else []
        self.kind = kind  # "post" | "pre"


# ---------------------------------------------------------------------------
# Decorators
# ---------------------------------------------------------------------------

def _decorate(target: Callable, decl: Any, attr: str) -> Callable:
    setattr(target, attr, decl)
    return target


def joverride(java_name: Optional[str] = None, arg_types: Optional[List[Any]] = None, *,
              return_type: Any = None, modifiers: Optional[int] = None,
              throws: Optional[List[str]] = None, signature: Optional[str] = None) -> Callable:
    """Overrides an existing Java method.

    Usable as ``@joverride()``, ``@joverride("equals")`` or
    ``@joverride("setValue", ["int"])``.
    """
    if callable(java_name) and arg_types is None:
        fn = java_name
        return _decorate(fn, _MethodDecl(fn.__name__, [], return_type, fn.__name__, "override",
                                         modifiers or _MODIFIER_PUBLIC), _OVERRIDE_ATTR)

    def decorator(fn: Callable) -> Callable:
        name = java_name if java_name is not None else fn.__name__
        return _decorate(fn, _MethodDecl(name, arg_types, return_type, fn.__name__, "override",
                                         modifiers or _MODIFIER_PUBLIC), _OVERRIDE_ATTR)

    return decorator


def joverload(java_name: str, arg_types: Optional[List[Any]] = None, *,
              return_type: Any = None, modifiers: Optional[int] = None,
              throws: Optional[List[str]] = None, signature: Optional[str] = None) -> Callable:
    """Overload-friendly alias for ``@joverride`` (always takes an explicit name)."""

    def decorator(fn: Callable) -> Callable:
        return _decorate(fn, _MethodDecl(java_name, arg_types, return_type, fn.__name__, "overload",
                                         modifiers or _MODIFIER_PUBLIC), _OVERLOAD_ATTR)

    return decorator


def jmethod(*args, **kwargs) -> Callable:
    """Adds a new Java method to the generated class.

    Supported forms:

        @jmethod()                                        # infer from annotations
        @jmethod("java.lang.String", ["int"])             # return_type, arg_types
        @jmethod("debugLabel", "java.lang.String", ["int"])  # name, return_type, arg_types
    """
    java_name = None
    return_type = None
    arg_types = None
    modifiers = kwargs.get("modifiers")

    if args:
        if callable(args[0]):
            fn = args[0]
            if len(args) > 1:
                return_type = args[1]
            if len(args) > 2:
                arg_types = args[2]
            decl = _infer_method_decl(fn, java_name, return_type, arg_types, modifiers)
            return _decorate(fn, decl, _METHOD_ATTR)
        if len(args) == 1:
            return_type = args[0]
        elif len(args) == 2:
            return_type, arg_types = args[0], args[1]
        elif len(args) >= 3:
            java_name, return_type, arg_types = args[0], args[1], args[2]

    def decorator(fn: Callable) -> Callable:
        decl = _infer_method_decl(fn, java_name, return_type, arg_types, modifiers)
        return _decorate(fn, decl, _METHOD_ATTR)

    return decorator


def _infer_method_decl(fn: Callable, java_name: Optional[str], return_type: Any,
                       arg_types: Optional[List[Any]], modifiers: Optional[int]) -> _MethodDecl:
    """Fill unspecified pieces of a jmethod declaration from annotations."""
    name = java_name if java_name is not None else fn.__name__
    inferred_types = arg_types
    inferred_return = return_type
    try:
        sig = inspect.signature(fn)
        params = [p for p in sig.parameters.values()
                  if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD)]
        if arg_types is None and len(params) > 1:
            inferred_types = []
            for p in params[1:]:
                ann = p.annotation
                if ann is not inspect.Parameter.empty:
                    inferred_types.append(ann)
        if return_type is None and sig.return_annotation is not inspect.Signature.empty:
            inferred_return = sig.return_annotation
    except (TypeError, ValueError):
        pass
    return _MethodDecl(name, inferred_types or [], inferred_return, fn.__name__, "method",
                       modifiers or _MODIFIER_PUBLIC)


def jconstructor(arg_types: Optional[List[Any]] = None) -> Callable:
    """Runs Python code after the Java constructor finished."""

    def decorator(fn: Callable) -> Callable:
        return _decorate(fn, _CtorDecl(arg_types, "post"), _CONSTRUCTOR_ATTR)

    return decorator


def jpreconstructor(arg_types: Optional[List[Any]] = None) -> Callable:
    """Modifies constructor arguments before the Java parent constructor runs."""

    def decorator(fn: Callable) -> Callable:
        return _decorate(fn, _CtorDecl(arg_types, "pre"), _PRECONSTRUCTOR_ATTR)

    return decorator


def jclassbuilder() -> Callable:
    """Lets you modify the DexMaker builder before the class is finalized."""

    def decorator(fn: Callable) -> Callable:
        return _decorate(fn, True, _CLASSBUILDER_ATTR)

    return decorator


def jfield(field_type: Any, default: Any = None,
           methods: Optional[List["_FieldAccessorDecl"]] = None) -> Any:
    """Declares a Java field on the generated class.

    ``self.counter += 1`` reads/writes the generated Java field.  ``methods``
    accepts ``jgetmethod(...)`` / ``jsetmethod(...)`` accessor declarations.
    """
    return _JFieldHolder(field_type, default, methods)


def jgetmethod(name: str, *, modifiers: Optional[int] = None) -> "_FieldAccessorDecl":
    """Generates a Java-only getter method for a field."""
    return _FieldAccessorDecl(name, True, modifiers or _MODIFIER_PUBLIC)


def jsetmethod(name: str, *, modifiers: Optional[int] = None) -> "_FieldAccessorDecl":
    """Generates a Java-only setter method for a field."""
    return _FieldAccessorDecl(name, False, modifiers or _MODIFIER_PUBLIC)


class _JFieldHolder:
    """Placeholder descriptor returned by ``jfield``; resolved at class-build time."""

    __slots__ = ("field_type", "default", "methods", "name", "_decl")

    def __init__(self, field_type: Any, default: Any, methods: Optional[List[_FieldAccessorDecl]]):
        self.field_type = field_type
        self.default = default
        self.methods = methods or []
        self.name = None
        self._decl = None

    def __set_name__(self, owner, name: str):
        self.name = name
        self._decl = _FieldDecl(name, self.field_type, self.default, self.methods)

    def __get__(self, obj: Any, objtype=None):
        if obj is None:
            return self
        java = getattr(obj, "java", None)
        if java is not None:
            try:
                return getattr(java, self.name)
            except Exception:
                pass
        if self.name in obj.__dict__:
            return obj.__dict__[self.name]
        return self.default

    def __set__(self, obj: Any, value: Any):
        java = getattr(obj, "java", None)
        if java is not None:
            try:
                setattr(java, self.name, value)
                return
            except Exception:
                pass
        obj.__dict__[self.name] = value


def jMVELmethod(*, return_type: Any = None, arguments: Optional[List[Tuple[str, Any]]] = None,
                code: str, name: Optional[str] = None, modifiers: Optional[int] = None) -> Any:
    """Adds a Java method backed by MVEL instead of Python."""
    return _JFieldHolder_MVEL(None, return_type, arguments, code, False, modifiers, name)


def jMVELoverride(*, arguments: Optional[List[Tuple[str, Any]]] = None,
                  code: str, name: Optional[str] = None, modifiers: Optional[int] = None) -> Any:
    """Overrides a parent Java method with MVEL code."""
    return _JFieldHolder_MVEL(None, None, arguments, code, True, modifiers, name)


# lowercase aliases (per docs)
jmvelmethod = jMVELmethod
jmveloverride = jMVELoverride


class _JFieldHolder_MVEL:
    """Placeholder for jMVELmethod/jMVELoverride; resolved at class-build time."""

    __slots__ = ("return_type", "arguments", "code", "override", "modifiers", "name", "_decl")

    def __init__(self, java_name, return_type, arguments, code, override, modifiers, name):
        self.name = name
        self.return_type = return_type
        self.arguments = arguments
        self.code = code
        self.override = override
        self.modifiers = modifiers
        self._decl = None

    def __set_name__(self, owner, name: str):
        self.name = name
        self._decl = _MvelDecl(name, self.return_type, self.arguments, self.code,
                               self.override, self.modifiers or _MODIFIER_PUBLIC)

    def __get__(self, obj, objtype=None):
        if obj is None:
            return self
        return None


# ---------------------------------------------------------------------------
# Class metadata / build
# ---------------------------------------------------------------------------

class _ClassMeta:
    """Collected Java class declaration for one Python class."""

    def __init__(self):
        self.base_class = None
        self.interfaces: List[Any] = []
        self.custom_name: Optional[str] = None
        self.method_decls: List[_MethodDecl] = []
        self.field_decls: List[_FieldDecl] = []
        self.mvel_decls: List[_MvelDecl] = []
        self.pre_constructors: List[_CtorDecl] = []
        self.post_constructors: List[_CtorDecl] = []
        self.class_builders: List[Callable] = []
        self.methods: Optional[list] = None
        self.constructors: Optional[list] = None
        self.generated: Optional[Any] = None
        self.callback: Optional[Any] = None
        self._peers: Dict[int, Any] = {}

    def collect_from(self, cls: type):
        """Scan a Python class hierarchy for j* declarations."""
        for klass in reversed(cls.__mro__):
            if klass is object:
                continue
            for _name, value in list(vars(klass).items()):
                decl = getattr(value, _OVERRIDE_ATTR, None)
                if decl is not None:
                    self.method_decls.append(decl)
                decl = getattr(value, _OVERLOAD_ATTR, None)
                if decl is not None:
                    self.method_decls.append(decl)
                decl = getattr(value, _METHOD_ATTR, None)
                if decl is not None:
                    self.method_decls.append(decl)
                decl = getattr(value, _CONSTRUCTOR_ATTR, None)
                if decl is not None:
                    self.post_constructors.append(decl)
                decl = getattr(value, _PRECONSTRUCTOR_ATTR, None)
                if decl is not None:
                    self.pre_constructors.append(decl)
                if getattr(value, _CLASSBUILDER_ATTR, False) and callable(value):
                    self.class_builders.append(value)
                if isinstance(value, _JFieldHolder) and value._decl is not None:
                    self.field_decls.append(value._decl)
                if isinstance(value, _JFieldHolder_MVEL) and value._decl is not None:
                    self.mvel_decls.append(value._decl)

    # ---- peer registry ----

    def get_peer(self, java_instance: Any) -> Any:
        return self._peers.get(id(java_instance))

    def register_peer(self, java_instance: Any, peer: Any):
        self._peers[id(java_instance)] = peer

    def _peer_for(self, cls: type, java_instance: Any) -> Any:
        peer = self.get_peer(java_instance)
        if peer is None:
            peer = cls.__new__(cls)
            peer._jc_java = java_instance
            self.register_peer(java_instance, peer)
        return peer

    # ---- dispatch tables ----

    def _find_method(self, java_name: str, arg_type_names: Tuple[str, ...]) -> Optional[_MethodDecl]:
        """Find the best matching method declaration for a Java signature."""
        exact = [d for d in self.method_decls if d.java_name == java_name
                 and tuple(_type_display_name(t) for t in d.arg_types) == arg_type_names]
        if exact:
            return exact[0]
        arity = [d for d in self.method_decls if d.java_name == java_name
                 and len(d.arg_types) == len(arg_type_names)]
        if arity:
            return arity[0]
        named = [d for d in self.method_decls if d.java_name == java_name]
        return named[0] if named else None

    def _find_post_constructor(self, arg_type_names: Tuple[str, ...]) -> Optional[_CtorDecl]:
        exact = [d for d in self.post_constructors
                 if tuple(_type_display_name(t) for t in d.arg_types) == arg_type_names]
        if exact:
            return exact[0]
        arity = [d for d in self.post_constructors if len(d.arg_types) == len(arg_type_names)]
        if arity:
            return arity[0]
        return self.post_constructors[0] if self.post_constructors else None

    def _find_pre_constructor(self, arg_type_names: Tuple[str, ...]) -> Optional[_CtorDecl]:
        exact = [d for d in self.pre_constructors
                 if tuple(_type_display_name(t) for t in d.arg_types) == arg_type_names]
        if exact:
            return exact[0]
        arity = [d for d in self.pre_constructors if len(d.arg_types) == len(arg_type_names)]
        if arity:
            return arity[0]
        return self.pre_constructors[0] if self.pre_constructors else None

    # ---- post-constructor completion (after Python __init__) ----

    def finish_constructor(self, peer: Any):
        """Run the matched ``@jconstructor`` and ``on_post_init`` hooks after the
        Python ``__init__`` has executed (documented init order)."""
        info = getattr(peer, "_jc_ctor_info", None)
        if not info:
            return
        type_names, args = info
        cls = type(peer)
        decl = self._find_post_constructor(type_names)
        if decl is not None:
            for klass in reversed(cls.__mro__):
                if klass is object:
                    continue
                for _name, value in list(vars(klass).items()):
                    d = getattr(value, _CONSTRUCTOR_ATTR, None)
                    if d is decl and callable(value):
                        try:
                            value(peer, *args)
                        except Exception:
                            import traceback
                            traceback.print_exc()
                        break
                else:
                    continue
                break
        post_init = getattr(peer, "on_post_init", None)
        if callable(post_init):
            try:
                post_init(*args)
            except Exception:
                import traceback
                traceback.print_exc()

    # ---- build ----

    def build(self, cls: type) -> Any:
        """Generate (once) and return the Java proxy Class for ``cls``."""
        if self.generated is not None:
            return self.generated
        base = self.base_class
        if base is None:
            raise TypeError("no Java base class bound; use @java_subclass(...) or Base.bind(...)")

        callback = _CallbackBridge(self, cls)
        self.callback = callback

        # method specs
        method_specs: List[Any] = []
        seen = set()
        for decl in self.method_decls:
            key = decl.signature_key()
            if key in seen:
                continue
            seen.add(key)
            return_cls = _resolve_java_type(decl.return_type) if decl.return_type is not None else None
            arg_classes = [_resolve_java_type(t) for t in decl.arg_types]
            # For overrides without explicit arg types we must pass null so the
            # host matches the parent method by name (an empty array would be
            # treated as an exact-signature lookup and fail).
            if decl.override_existing and not decl.arg_types:
                param_array = None
            else:
                param_array = _to_java_class_array([c for c in arg_classes if c is not None])
            method_specs.append(ClassProxy.ProxyMethodSpec(
                decl.java_name,
                return_cls,
                param_array,
                decl.modifiers,
                decl.override_existing,
            ))

        # MVEL specs
        for decl in self.mvel_decls:
            return_cls = _resolve_java_type(decl.return_type) if decl.return_type is not None else None
            arg_classes = [_resolve_java_type(t) for t in decl.arg_types]
            if decl.override and not decl.arg_types:
                param_array = None
            else:
                param_array = _to_java_class_array([c for c in arg_classes if c is not None])
            method_specs.append(ClassProxy.ProxyMethodSpec(
                decl.java_name,
                return_cls,
                param_array,
                decl.modifiers,
                decl.override,
                "mvel",
                decl.code,
                _to_java_string_list(decl.argument_names),
            ))

        # field specs
        field_specs: List[Any] = []
        for fdecl in self.field_decls:
            field_cls = _resolve_java_type(fdecl.field_type)
            if field_cls is None:
                continue
            accessor_specs = [ClassProxy.FieldMethodSpec(a.name, a.modifiers, a.getter)
                              for a in fdecl.accessors]
            if accessor_specs:
                field_specs.append(ClassProxy.FieldSpec(fdecl.field_name, field_cls, fdecl.modifiers, accessor_specs))
            else:
                field_specs.append(ClassProxy.FieldSpec(fdecl.field_name, field_cls, fdecl.modifiers))

        # DexMaker hook (jclassbuilder)
        dex_maker_hook = None
        if self.class_builders:
            dex_maker_hook = _DexMakerHookProxy(self.class_builders)

        interfaces = self.interfaces or None
        name_suffix = self.custom_name or None

        generated = ClassProxy.createProxyClass(
            base,
            callback,
            dex_maker_hook,
            interfaces,
            _to_java_object_list(self.methods),
            _to_java_object_list(self.constructors),
            method_specs if method_specs else None,
            field_specs if field_specs else None,
            name_suffix,
        )
        self.generated = generated
        return generated


class _CallbackBridge(dynamic_proxy(Utilities.Callback3Return)):
    """Java -> Python dispatch bridge.

    ``run(javaInstance, methodSignature, args)`` where the signature is
    ``name:type1:type2`` (or ``<constructor>:type1``).  For constructors the
    bridge is invoked twice: once with ``javaInstance == None`` (pre-constructor,
    may return replacement args) and once with the constructed instance.
    """

    def __init__(self, meta: _ClassMeta, cls: type):
        self._meta = meta
        self._cls = cls

    def run(self, java_instance, method_signature, args):
        try:
            return self._dispatch(java_instance, method_signature, args)
        except Exception:
            import traceback
            traceback.print_exc()
            return None

    def _dispatch(self, java_instance, method_signature, args):
        if not method_signature:
            return None
        signature = str(method_signature)
        arg_list = _args_to_list(args)
        meta = self._meta
        cls = self._cls

        if signature.startswith("<constructor>"):
            type_part = signature[len("<constructor>"):]
            type_names = tuple(t for t in type_part.split(":") if t)
            if java_instance is None:
                return self._handle_pre_constructor(type_names, arg_list)
            return self._handle_post_constructor(java_instance, type_names, arg_list)

        # method dispatch
        parts = signature.split(":")
        java_name = parts[0]
        type_names = tuple(parts[1:])
        decl = meta._find_method(java_name, type_names)
        if decl is None:
            return None
        handler = getattr(cls, decl.py_name, None)
        if handler is None:
            return None
        peer = meta._peer_for(cls, java_instance)
        bound = handler.__get__(peer, cls) if hasattr(handler, "__get__") else handler
        result = bound(*arg_list)
        return result

    def _handle_pre_constructor(self, type_names: Tuple[str, ...], args: list):
        meta = self._meta
        decl = meta._find_pre_constructor(type_names)
        if decl is None:
            return None
        # jpreconstructor handlers are stored as plain functions; find by walking decls
        for klass in reversed(self._cls.__mro__):
            if klass is object:
                continue
            for _name, value in list(vars(klass).items()):
                d = getattr(value, _PRECONSTRUCTOR_ATTR, None)
                if d is decl and callable(value):
                    try:
                        result = value(self._cls, *args)
                    except TypeError:
                        result = value(*args)
                    return _normalize_pre_constructor_result(result, len(args))
        return None

    def _handle_post_constructor(self, java_instance, type_names: Tuple[str, ...], args: list):
        """Peer creation happens here (during Java construction).  The
        ``__init__`` -> ``@jconstructor`` -> ``on_post_init`` sequence is
        finished by ``Base.new_instance`` so that separate ``init_args`` are
        honoured in the documented order."""
        meta = self._meta
        cls = self._cls
        peer = meta._peer_for(cls, java_instance)
        peer._jc_ctor_info = (type_names, tuple(args))
        return None


def _normalize_pre_constructor_result(result: Any, arg_count: int):
    """jpreconstructor may return None (keep), a list/tuple (replacements) or a
    single value for a one-argument constructor."""
    if result is None:
        return None
    if isinstance(result, (list, tuple)):
        return jarray(jclass("java.lang.Object"), list(result))
    if arg_count == 1:
        return jarray(jclass("java.lang.Object"), [result])
    return None


class _DexMakerHookProxy(dynamic_proxy(ClassProxy.DexMakerHook)):
    """Forwards DexMakerHook.apply to jclassbuilder-marked Python functions."""

    def __init__(self, builders: List[Callable]):
        self._builders = builders

    def apply(self, dex_maker, generated_type, base_type, declared_interfaces):
        for fn in self._builders:
            try:
                fn(dex_maker, generated_type, base_type, declared_interfaces)
            except Exception:
                import traceback
                traceback.print_exc()


# ---------------------------------------------------------------------------
# Base
# ---------------------------------------------------------------------------

def _instantiate_java(java_cls, *args):
    """Instantiate a Java class via reflection (getConstructors + newInstance).

    Avoids Chaquopy's ``JavaClass.__call__`` which fails on generated proxy
    classes with "Class is not callable because it implements no functional
    interfaces".
    """
    try:
        constructors = java_cls.getConstructors()
        n = len(constructors)
        for i in range(n):
            ctor = constructors[i]
            try:
                if len(ctor.getParameterTypes()) == len(args):
                    return ctor.newInstance(*args)
            except Exception:
                continue
    except Exception:
        pass
    return java_cls(*args)


class Base:
    """Base class for managed Java subclasses.

    Use ``@java_subclass(JavaClass)`` on a subclass, or call ``bind(...)`` after
    the class definition::

        from extera_utils.classes import Base, java_subclass, jfield, joverload
        from java.util import ArrayList


        @java_subclass(ArrayList)
        class CountingList(Base):
            added_count = jfield("int", default=0)

            @joverload("add", ["java.lang.Object"])
            def add_item(self, value):
                self.added_count += 1
                return super().add_item(value)


        items = CountingList.new_instance()
        items.add("Hello")
    """

    _jc_meta: Optional[_ClassMeta] = None
    _jc_java: Any = None

    # -- binding -----------------------------------------------------------

    @classmethod
    def bind(cls, java_class: Any, *interfaces: Any, methods: Optional[list] = None,
             constructors: Optional[list] = None, custom_name: Optional[str] = None,
             **kwargs) -> type:
        """Bind this Python class to a Java base class after its definition."""
        meta = cls._jc_meta if cls._jc_meta is not None else _ClassMeta()
        meta.base_class = java_class
        meta.interfaces = list(interfaces)
        if custom_name is not None:
            meta.custom_name = custom_name
        if methods is not None:
            meta.methods = methods
        if constructors is not None:
            meta.constructors = constructors
        meta.collect_from(cls)
        cls._jc_meta = meta
        return cls

    @classmethod
    def extends(cls, java_class: Any, *interfaces: Any, custom_name: Optional[str] = None,
                **kwargs):
        """Decorator alias of ``bind`` (usable as ``Base.extends("...")``)."""

        def decorator(py_class):
            return py_class.bind(java_class, *interfaces, custom_name=custom_name)

        if isinstance(java_class, str):
            return decorator
        return decorator(java_class) if False else decorator

    # -- generation --------------------------------------------------------

    @classmethod
    def java_class(cls) -> Any:
        """Return the generated Java Class for this managed subclass."""
        meta = cls._jc_meta if cls._jc_meta is not None else _ClassMeta()
        if meta.base_class is None:
            raise TypeError("no Java base class bound; use @java_subclass(...) or Base.bind(...)")
        meta.collect_from(cls)
        cls._jc_meta = meta
        return meta.build(cls)

    @classmethod
    def new_instance(cls, *args, init_args: Optional[list] = None, **kwargs) -> "Base":
        """Create a Python peer instance backed by a new Java instance.

        Java constructor args go to ``*args``; separate Python ``__init__``
        arguments can be passed via ``init_args=[...]``.
        """
        meta = cls._jc_meta if cls._jc_meta is not None else _ClassMeta()
        if meta.base_class is None:
            raise TypeError("no Java base class bound; use @java_subclass(...) or Base.bind(...)")
        meta.collect_from(cls)
        cls._jc_meta = meta
        meta.build(cls)
        init = getattr(cls, "__init__", None)
        java_cls = meta.generated
        java_instance = _instantiate_java(java_cls, *args)
        peer = meta.get_peer(java_instance)
        if peer is None:
            peer = cls.__new__(cls)
            peer._jc_java = java_instance
            meta.register_peer(java_instance, peer)
        if init is not None and init is not Base.__init__:
            py_args = list(init_args) if init_args is not None else list(args)
            try:
                init(peer, *py_args)
            except TypeError:
                init(peer)
        meta.finish_constructor(peer)
        return peer

    @classmethod
    def new_java_instance(cls, *args, **kwargs) -> Any:
        """Create a raw Java instance of the generated class (no peer binding)."""
        java_cls = cls.java_class()
        return _instantiate_java(java_cls, *args)

    @classmethod
    def from_java(cls, java_instance: Any) -> "Base":
        """Recover the Python peer for an existing Java instance of this class."""
        meta = cls._jc_meta if cls._jc_meta is not None else _ClassMeta()
        meta.collect_from(cls)
        cls._jc_meta = meta
        peer = meta.get_peer(java_instance)
        if peer is None:
            peer = cls.__new__(cls)
            peer._jc_java = java_instance
            meta.register_peer(java_instance, peer)
        return peer

    # -- java instance access ----------------------------------------------

    @property
    def java(self) -> Any:
        """The attached raw Java instance."""
        return self._jc_java

    @property
    def this(self) -> Any:
        """Alias for ``.java``."""
        return self._jc_java

    def __getattr__(self, name: str) -> Any:
        """Fall back to the attached Java instance (and to SUPER_ bridges)."""
        if name.startswith("_"):
            raise AttributeError(name)
        java = self.__dict__.get("_jc_java")
        if java is not None:
            attr = getattr(java, name, None)
            if attr is not None:
                return attr
        # try SUPER_<name> bridge for overridden methods
        if java is not None:
            super_name = "SUPER_" + name
            bridge = getattr(java, super_name, None)
            if bridge is not None:
                return bridge
        raise AttributeError("'%s' object has no attribute '%s'" % (type(self).__name__, name))

    def on_post_init(self, *args, **kwargs):
        """Called after the Java constructor finished (override in subclass)."""
        pass

    def __init__(self, *args, **kwargs):
        self._jc_java = None


# ---------------------------------------------------------------------------
# java_subclass
# ---------------------------------------------------------------------------

def java_subclass(*classes, methods: Optional[list] = None,
                  constructors: Optional[list] = None,
                  custom_name: Optional[str] = None, **kwargs) -> Callable:
    """Binds a Python class to a Java base class and optional Java interfaces.

    Usage::

        @java_subclass(SomeJavaClass)
        class MySubclass(Base):
            ...

        @java_subclass(FrameLayout, CustomDelegate1, CustomDelegate2)
        class MyCell(Base):
            ...
    """
    if not classes:
        raise TypeError("java_subclass requires at least one Java class")
    base = classes[0]
    interfaces = list(classes[1:])

    def decorator(cls: type) -> type:
        meta = _ClassMeta()
        meta.base_class = base
        meta.interfaces = interfaces
        if custom_name is not None:
            meta.custom_name = custom_name
        if methods is not None:
            meta.methods = methods
        if constructors is not None:
            meta.constructors = constructors
        meta.collect_from(cls)
        cls._jc_meta = meta
        return cls

    return decorator


# ---------------------------------------------------------------------------
# class_proxy / legacy aliases
# ---------------------------------------------------------------------------

class_proxy = java_subclass


def extends(java_class: Any, *interfaces: Any, custom_name: Optional[str] = None, **kwargs):
    """Module-level alias for ``Base.extends`` / ``java_subclass``."""

    def decorator(cls: type) -> type:
        return cls.bind(java_class, *interfaces, custom_name=custom_name)

    if isinstance(java_class, str):
        return decorator
    return decorator


class _LegacyDecl:
    """Structured metadata holders for the class-proxy DSL."""

    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)


class Override(_LegacyDecl):
    pass


class Overload(_LegacyDecl):
    pass


class Method(_LegacyDecl):
    pass


class MVELMethod(_LegacyDecl):
    pass


class MVELOverride(_LegacyDecl):
    pass


class ClassBuilder(_LegacyDecl):
    pass


class Field(_LegacyDecl):
    pass


class GetMethod(_LegacyDecl):
    pass


class SetMethod(_LegacyDecl):
    pass


class Constructor(_LegacyDecl):
    pass


class PreConstructor(_LegacyDecl):
    pass


# ---------------------------------------------------------------------------
# PyObj
# ---------------------------------------------------------------------------

class PyObj:
    """Carries an arbitrary Python object through Java-facing APIs.

    ``PyObj.create(value)`` wraps the value; the attached Python object can be
    retrieved via ``.java`` (identity on this host, since Chaquopy marshals
    Python objects to Java ``PyObject`` automatically).
    """

    __slots__ = ("value",)

    def __init__(self, value: Any):
        self.value = value

    @staticmethod
    def create(value: Any) -> "PyObj":
        return PyObj(value)

    @property
    def java(self) -> Any:
        return self.value

    @property
    def this(self) -> Any:
        return self.value

    def __call__(self, *args, **kwargs):
        return self.value
