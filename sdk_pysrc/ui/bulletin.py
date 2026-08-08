"""ui.bulletin — static helpers for Telegram "Bulletin" bottom notifications."""
from __future__ import annotations

from java import dynamic_proxy, jclass

from android_utils import R, run_on_ui_thread, log

BulletinFactory = jclass("org.telegram.ui.Components.BulletinFactory")
R_tg = jclass("org.telegram.messenger.R")
LocaleController = jclass("org.telegram.messenger.LocaleController")
LaunchActivity = jclass("org.telegram.ui.LaunchActivity")


def _resolve_fragment(fragment):
    if fragment is not None:
        return fragment
    try:
        return LaunchActivity.getSafeLastFragment()
    except Exception:
        return None


def _factory(fragment):
    frag = _resolve_fragment(fragment)
    if frag is None:
        try:
            return getattr(BulletinFactory, "global")()
        except Exception:
            return None
    try:
        return BulletinFactory.of(frag)
    except Exception:
        return None


def _raw(name, fallback="info"):
    try:
        return int(getattr(R_tg.raw, name))
    except Exception:
        try:
            return int(getattr(R_tg.raw, fallback))
        except Exception:
            return 0


def _string(res_name, fallback):
    try:
        return LocaleController.getString(getattr(R_tg.string, res_name))
    except Exception:
        return fallback


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @staticmethod
    def show_info(message, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    f.createSimpleBulletin(_raw("info"), str(message)).show()
            except Exception as e:
                log("BulletinHelper.show_info: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_error(message, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    f.createErrorBulletin(str(message)).show()
            except Exception as e:
                log("BulletinHelper.show_error: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_success(message, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    f.createSuccessBulletin(str(message)).show()
            except Exception as e:
                log("BulletinHelper.show_success: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_simple(text, icon_res_id, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    f.createSimpleBulletin(int(icon_res_id), str(text)).show()
            except Exception as e:
                log("BulletinHelper.show_simple: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_two_line(title, subtitle, icon_res_id, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    f.createSimpleBulletin(int(icon_res_id), str(title), str(subtitle)).show()
            except Exception as e:
                log("BulletinHelper.show_two_line: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_with_button(text, icon_res_id, button_text, on_click, fragment=None,
                         duration=DURATION_PROLONG):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    runnable = R(on_click) if on_click else None
                    f.createSimpleBulletin(int(icon_res_id), str(text), str(button_text),
                                           runnable, int(duration)).show()
            except Exception as e:
                log("BulletinHelper.show_with_button: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_undo(text, on_undo, on_action=None, subtitle=None, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    if subtitle is not None:
                        f.createUndoBulletin(str(text), str(subtitle),
                                             R(on_undo) if on_undo else None,
                                             R(on_action) if on_action else None).show()
                    else:
                        f.createUndoBulletin(str(text), R(on_undo) if on_undo else None,
                                             R(on_action) if on_action else None).show()
            except Exception as e:
                log("BulletinHelper.show_undo: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_copied_to_clipboard(message=None, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    text = message if message is not None else _string("TextCopied", "Text copied")
                    f.createCopyBulletin(str(text)).show()
            except Exception as e:
                log("BulletinHelper.show_copied_to_clipboard: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_link_copied(is_private_link_info=False, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    name = "LinkCopiedPrivateInfo" if is_private_link_info else "LinkCopied"
                    f.createCopyBulletin(_string(name, "Link copied")).show()
            except Exception as e:
                log("BulletinHelper.show_link_copied: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_file_saved_to_gallery(is_video=False, amount=1, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    if amount > 1:
                        ft = BulletinFactory.FileType.VIDEOS if is_video else BulletinFactory.FileType.PHOTOS
                    else:
                        ft = BulletinFactory.FileType.VIDEO if is_video else BulletinFactory.FileType.PHOTO
                    f.createDownloadBulletin(ft, int(amount), None).show()
            except Exception as e:
                log("BulletinHelper.show_file_saved_to_gallery: %s" % e)
        run_on_ui_thread(_do)

    @staticmethod
    def show_file_saved_to_downloads(file_type_enum_name="UNKNOWN", amount=1, fragment=None):
        def _do():
            try:
                f = _factory(fragment)
                if f is not None:
                    ft = BulletinFactory.FileType.valueOf(str(file_type_enum_name))
                    f.createDownloadBulletin(ft, int(amount), None).show()
            except Exception as e:
                log("BulletinHelper.show_file_saved_to_downloads: %s" % e)
        run_on_ui_thread(_do)
