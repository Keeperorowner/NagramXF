"""ui.alert — Pythonic wrapper around ``org.telegram.ui.ActionBar.AlertDialog``."""
from __future__ import annotations

from java import dynamic_proxy, jclass

from android_utils import log

AlertDialog = jclass("org.telegram.ui.ActionBar.AlertDialog")
OnButtonClickListenerIface = jclass("org.telegram.ui.ActionBar.AlertDialog$OnButtonClickListener")
DialogInterfaceOnClickListenerIface = jclass("android.content.DialogInterface$OnClickListener")
OnDismissListenerIface = jclass("android.content.DialogInterface$OnDismissListener")
OnCancelListenerIface = jclass("android.content.DialogInterface$OnCancelListener")


class _ButtonListener(dynamic_proxy(OnButtonClickListenerIface)):
    def __init__(self, builder, fn):
        super().__init__()
        self._builder = builder
        self._fn = fn

    def onClick(self, dialog, which):
        if self._fn is not None:
            try:
                self._fn(self._builder, which)
            except Exception as e:
                log("AlertDialog button listener error: %s" % e)


class _ItemsListener(dynamic_proxy(DialogInterfaceOnClickListenerIface)):
    def __init__(self, builder, fn):
        super().__init__()
        self._builder = builder
        self._fn = fn

    def onClick(self, dialog, which):
        if self._fn is not None:
            try:
                self._fn(self._builder, which)
            except Exception as e:
                log("AlertDialog items listener error: %s" % e)


class _DismissListener(dynamic_proxy(OnDismissListenerIface)):
    def __init__(self, builder, fn):
        super().__init__()
        self._builder = builder
        self._fn = fn

    def onDismiss(self, dialog):
        if self._fn is not None:
            try:
                self._fn(self._builder)
            except Exception as e:
                log("AlertDialog dismiss listener error: %s" % e)


class _CancelListener(dynamic_proxy(OnCancelListenerIface)):
    def __init__(self, builder, fn):
        super().__init__()
        self._builder = builder
        self._fn = fn

    def onCancel(self, dialog):
        if self._fn is not None:
            try:
                self._fn(self._builder)
            except Exception as e:
                log("AlertDialog cancel listener error: %s" % e)


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 1
    ALERT_TYPE_SPINNER = 2

    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context, progress_style=ALERT_TYPE_MESSAGE, resources_provider=None):
        if resources_provider is not None:
            self._builder = AlertDialog.Builder(context, resources_provider)
        else:
            self._builder = AlertDialog.Builder(context)
        self._dialog = None
        self._progress_style = progress_style

    # content
    def set_title(self, title):
        self._builder.setTitle(title)
        return self

    def set_message(self, message):
        self._builder.setMessage(message)
        return self

    def set_message_text_view_clickable(self, clickable):
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_view(self, view, height=-2):
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items, listener=None, icons=None):
        if icons is not None:
            self._builder.setItems(items, icons, _ItemsListener(self, listener) if listener else None)
        else:
            self._builder.setItems(items, _ItemsListener(self, listener) if listener else None)
        return self

    # buttons
    def set_positive_button(self, text, listener=None):
        self._builder.setPositiveButton(text, _ButtonListener(self, listener) if listener else None)
        return self

    def set_negative_button(self, text, listener=None):
        self._builder.setNegativeButton(text, _ButtonListener(self, listener) if listener else None)
        return self

    def set_neutral_button(self, text, listener=None):
        self._builder.setNeutralButton(text, _ButtonListener(self, listener) if listener else None)
        return self

    def make_button_red(self, button_type):
        try:
            self._builder.makeButtonRed(int(button_type))
        except Exception:
            pass
        return self

    # behaviour
    def set_on_back_button_listener(self, listener=None):
        self._builder.setOnBackButtonListener(_ButtonListener(self, listener) if listener else None)
        return self

    def set_on_dismiss_listener(self, listener=None):
        if listener is not None:
            self._builder.setOnDismissListener(_DismissListener(self, listener))
        return self

    def set_on_cancel_listener(self, listener=None):
        if listener is not None:
            self._builder.setOnCancelListener(_CancelListener(self, listener))
        return self

    # appearance
    def set_top_image(self, res_id, background_color):
        try:
            self._builder.setTopImage(int(res_id), background_color)
        except Exception as e:
            log("AlertDialog set_top_image error: %s" % e)
        return self

    def set_top_drawable(self, drawable, background_color):
        try:
            self._builder.setTopDrawable(drawable, background_color)
        except Exception as e:
            log("AlertDialog set_top_drawable error: %s" % e)
        return self

    def set_top_animation(self, res_id, size, auto_repeat, background_color, layer_colors=None):
        try:
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), background_color, layer_colors)
        except Exception as e:
            log("AlertDialog set_top_animation error: %s" % e)
        return self

    def set_dim_enabled(self, enabled):
        try:
            self._builder.setDimEnabled(bool(enabled))
        except Exception as e:
            log("AlertDialog set_dim_enabled error: %s" % e)
        return self

    def set_dialog_button_color_key(self, theme_key):
        try:
            self._builder.setDialogButtonColorKey(theme_key)
        except Exception as e:
            log("AlertDialog set_dialog_button_color_key error: %s" % e)
        return self

    def set_blurred_background(self, blur, blur_behind_if_possible=True):
        try:
            self._builder.setBlurredBackground(bool(blur), bool(blur_behind_if_possible))
        except Exception as e:
            log("AlertDialog set_blurred_background error: %s" % e)
        return self

    def set_cancelable(self, cancelable):
        if self._dialog is not None:
            self._dialog.setCancelable(bool(cancelable))
        return self

    def set_canceled_on_touch_outside(self, cancel):
        if self._dialog is not None:
            self._dialog.setCanceledOnTouchOutside(bool(cancel))
        return self

    def set_progress(self, progress):
        if self._dialog is not None:
            try:
                self._dialog.setProgress(int(progress))
            except Exception:
                pass
        return self

    # lifecycle
    def create(self):
        if self._dialog is None:
            self._dialog = self._builder.create()
        return self

    def show(self):
        self.create()
        self._dialog.show()
        return self

    def dismiss(self):
        if self._dialog is not None:
            self._dialog.dismiss()
        return self

    def get_dialog(self):
        self.create()
        return self._dialog

    def get_button(self, button_type):
        self.create()
        try:
            return self._dialog.getButton(int(button_type))
        except Exception:
            return None
