"""ui.settings — settings page item factories.

Each item is a dataclass with a ``type`` string the Java host reads to build the
real ``SettingItem``.  ``SimpleSettingFactory`` generates a Java
``CustomSetting.Factory`` subclass backed by Python callbacks via ``ClassProxy``.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from java import dynamic_proxy, jclass

from android_utils import log

UItem = jclass("org.telegram.ui.Components.UItem")
UItemFactory = jclass("org.telegram.ui.Components.UItem$UItemFactory")
Factory = jclass("com.exteragram.messenger.plugins.models.CustomSetting$Factory")
ClassProxy = jclass("com.exteragram.messenger.plugins.utils.ClassProxy")
Utilities = jclass("org.telegram.messenger.Utilities")


@dataclass
class Header:
    text: str
    type: str = "header"


@dataclass
class Divider:
    text: str = None
    type: str = "divider"


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: str = None
    icon: str = None
    on_change: object = None
    on_long_click: object = None
    link_alias: str = None
    type: str = "switch"


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: list = None
    icon: str = None
    on_change: object = None
    on_long_click: object = None
    link_alias: str = None
    type: str = "selector"


@dataclass
class Input:
    key: str
    text: str
    default: str = ""
    subtext: str = None
    icon: str = None
    on_change: object = None
    on_long_click: object = None
    link_alias: str = None
    type: str = "input"


@dataclass
class Text:
    text: str
    subtext: str = None
    icon: str = None
    accent: bool = False
    red: bool = False
    on_click: object = None
    on_long_click: object = None
    create_sub_fragment: object = None
    link_alias: str = None
    type: str = "text"


@dataclass
class EditText:
    key: str
    hint: str
    default: str = ""
    multiline: bool = False
    max_length: int = 256
    mask: str = None
    on_change: object = None
    type: str = "edit_text"


@dataclass
class Custom:
    item: object = None
    view: object = None
    factory: object = None
    factory_args: object = None
    on_click: object = None
    on_long_click: object = None
    create_sub_fragment: object = None
    link_alias: str = None
    type: str = "custom"


def _to_args_array(args):
    if args is None:
        return []
    return [args[i] for i in range(len(args))]


def _build_custom_factory(ssf):
    def dispatch(method_name, args, factory_instance):
        name = method_name.split(":")[0] if method_name else ""
        a = _to_args_array(args)

        if name == "createView":
            return ssf.create_view(a[0], a[1], a[2], a[3], a[4]) if ssf.create_view else None
        if name == "bindView":
            if ssf.bind_view:
                ssf.bind_view(a[0], a[1], a[2], a[3], a[4])
            return None
        if name == "attachedView":
            if ssf.attached_view:
                ssf.attached_view(a[0], a[1], a[2])
            return None
        if name == "create":
            if ssf.create_item:
                return ssf.create_item(a[0], a[1], a[2])
            try:
                UItemFactory.setup(factory_instance)
                return UItem.ofFactory(factory_instance.getClass())
            except Exception:
                try:
                    return UItem(int(factory_instance.viewType))
                except Exception:
                    return None
        if name == "onClick":
            if ssf.on_click:
                ssf.on_click(a[0], a[1], a[2])
            return None
        if name == "onLongClick":
            if ssf.on_long_click:
                return bool(ssf.on_long_click(a[0], a[1], a[2]))
            return False
        if name == "isShadow":
            return bool(ssf.is_shadow)
        if name == "isClickable":
            return bool(ssf.is_clickable)
        if name == "equals":
            if ssf.equals:
                return bool(ssf.equals(a[0], a[1]))
            return a[0] == a[1] if len(a) >= 2 else False
        if name == "contentsEquals":
            if ssf.content_equals:
                return bool(ssf.content_equals(a[0], a[1]))
            return a[0] == a[1] if len(a) >= 2 else False
        return None

    try:
        CallbackProxy = dynamic_proxy(Utilities.Callback3Return)
    except Exception as e:
        log("SimpleSettingFactory: dynamic_proxy(Callback3Return) failed: %s" % e)
        raise

    class _Callback(CallbackProxy):
        def run(self, instance, method_name, args):
            try:
                return dispatch(str(method_name), args, instance)
            except Exception as e:
                log("SimpleSettingFactory.%s error: %s" % (method_name, e))
                return None

    try:
        callback_instance = _Callback()
    except Exception as e:
        log("SimpleSettingFactory: _Callback() failed: %s" % e)
        raise

    try:
        generated = ClassProxy.createProxyClass(Factory, callback_instance, None, None)
    except Exception as e:
        log("SimpleSettingFactory: createProxyClass failed: %s" % e)
        raise

    try:
        return generated.newInstance()
    except Exception as e:
        log("SimpleSettingFactory: newInstance failed: %s" % e)
        return generated()


class _FactoryInstance:
    def __init__(self, ssf):
        self._ssf = ssf
        self._java = None

    @property
    def java(self):
        if self._java is None:
            try:
                self._java = _build_custom_factory(self._ssf)
            except Exception as e:
                log("SimpleSettingFactory: failed to build factory: %s" % e)
                self._java = None
        return self._java


class SimpleSettingFactory:
    def __init__(self, create_view, bind_view, *, is_clickable=False, is_shadow=False,
                 create_item=None, on_click=None, on_long_click=None,
                 attached_view=None, equals=None, content_equals=None):
        self.create_view = create_view
        self.bind_view = bind_view
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.attached_view = attached_view
        self.equals = equals
        self.content_equals = content_equals
        self.instance = _FactoryInstance(self)

    def __call__(self, *args, **kwargs):
        link_alias = kwargs.get("link_alias")
        factory_args = list(args) if args else None
        return Custom(factory=self.instance.java, factory_args=factory_args, link_alias=link_alias)
