"""client_utils — queues, requests, controllers, fragments, and send/edit helpers.

Thin Python wrappers over Telegram internals reached through Chaquopy.
Multi-account helpers use the explicit ``account``, the active callback scope,
or the selected UI account, in that order.
"""
from __future__ import annotations

from java import dynamic_proxy, jarray, jclass

from _account_context import _set_hook_account, _wrap_with_current_account, account_scope, get_hook_account
from android_utils import R, log

# ---- queue names ----
STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

# ---- Java classes ----
Utilities = jclass("org.telegram.messenger.Utilities")
UserConfig = jclass("org.telegram.messenger.UserConfig")
AccountInstance = jclass("org.telegram.messenger.AccountInstance")
MessagesController = jclass("org.telegram.messenger.MessagesController")
ConnectionsManager = jclass("org.telegram.tgnet.ConnectionsManager")
SendMessagesHelper = jclass("org.telegram.messenger.SendMessagesHelper")
MediaController = jclass("org.telegram.messenger.MediaController")
NotificationCenter = jclass("org.telegram.messenger.NotificationCenter")
LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")
ArrayList = jclass("java.util.ArrayList")

from extera_utils.text_formatting import parse_text


def _resolve_account(account):
    if account is not None:
        return int(account)
    scoped_account = get_hook_account()
    if scoped_account is not None:
        return scoped_account
    return get_selected_account()


def get_queue_by_name(name):
    """Return the raw Java ``DispatchQueue`` for a queue name, or None."""
    if not name:
        return None
    try:
        return getattr(Utilities, name)
    except Exception:
        return None


def run_on_queue(fn, queue_name=PLUGINS_QUEUE, delay=0):
    """Run *fn* on a background queue with the caller's callback scope."""
    if fn is None:
        return
    queue = get_queue_by_name(queue_name)
    if queue is None:
        return
    runnable = R(_wrap_with_current_account(fn))
    try:
        if delay:
            queue.postRunnable(runnable, int(delay))
        else:
            queue.postRunnable(runnable)
    except Exception:
        pass


def run_on_ui_thread(fn, delay=0):
    """Run *fn* on the Android main thread, optionally after ``delay`` ms."""
    from android_utils import run_on_ui_thread as _run_ui
    _run_ui(fn, delay)


def get_last_fragment():
    """Current foreground fragment, or None."""
    try:
        return LaunchActivity.getLastFragment()
    except Exception:
        return None


def get_current_account():
    """The currently selected account index (UserConfig.selectedAccount)."""
    try:
        return int(UserConfig.selectedAccount)
    except Exception:
        return 0


def get_selected_account():
    """The account currently selected in the UI (alias for get_current_account)."""
    return get_current_account()


def get_context():
    """Best-effort Context: visible activity if any, else the application context."""
    try:
        fragment = get_last_fragment()
        if fragment is not None and fragment.getParentActivity() is not None:
            return fragment.getParentActivity()
    except Exception:
        pass
    try:
        from java import jclass as _jc
        ApplicationLoader = _jc("org.telegram.messenger.ApplicationLoader")
        return ApplicationLoader.applicationContext
    except Exception:
        return None


def get_user_id(account=None):
    """The logged-in user's id (clientUserId) for the given/current account."""
    return get_account_instance(account).getUserConfig().getClientUserId()


def get_file_ref_controller(account=None):
    return get_account_instance(account).getFileRefController()


def get_stats_controller(account=None):
    return get_account_instance(account).getStatsController()


def get_account_instance(account=None):
    return AccountInstance.getInstance(_resolve_account(account))


def get_messages_controller(account=None):
    return get_account_instance(account).getMessagesController()


def get_contacts_controller(account=None):
    return get_account_instance(account).getContactsController()


def get_media_data_controller(account=None):
    return get_account_instance(account).getMediaDataController()


def get_connections_manager(account=None):
    return get_account_instance(account).getConnectionsManager()


def get_location_controller(account=None):
    return get_account_instance(account).getLocationController()


def get_notifications_controller(account=None):
    return get_account_instance(account).getNotificationsController()


def get_messages_storage(account=None):
    return get_account_instance(account).getMessagesStorage()


def get_send_messages_helper(account=None):
    return get_account_instance(account).getSendMessagesHelper()


def get_file_loader(account=None):
    return get_account_instance(account).getFileLoader()


def get_secret_chat_helper(account=None):
    return get_account_instance(account).getSecretChatHelper()


def get_download_controller(account=None):
    return get_account_instance(account).getDownloadController()


def get_notifications_settings(account=None):
    return get_account_instance(account).getNotificationsSettings()


def get_notification_center(account=None):
    return get_account_instance(account).getNotificationCenter()


def get_user_config(account=None):
    return UserConfig.getInstance(_resolve_account(account))


def get_media_controller():
    """MediaController is a single global instance (no per-account variant)."""
    return MediaController.getInstance()


_MIME_OVERRIDES = {
    "webp": "image/webp",
    "opus": "audio/opus",
    "mp3": "audio/mpeg",
    "m4a": "audio/m4a",
    "ogg": "audio/ogg",
    "flac": "audio/flac",
}

_AUDIO_EXTENSIONS = ("opus", "mp3", "m4a", "ogg", "flac")


def _document_mime_type(extension):
    """Best-effort MIME type for a file extension."""
    extension = (extension or "").lower()
    if extension in _MIME_OVERRIDES:
        return _MIME_OVERRIDES[extension]
    try:
        MimeTypeMap = jclass("android.webkit.MimeTypeMap")
        resolved = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        if resolved:
            return str(resolved)
    except Exception:
        pass
    return "application/octet-stream"


def _prepare_document(file_path):
    """Build a local ``TLRPC.TL_document`` for *file_path*, or ``None`` on failure.

    The returned document has no server id yet, so sending it together with the
    same path makes ``SendMessagesHelper`` upload the file.
    """
    if not file_path:
        return None
    try:
        File = jclass("java.io.File")
        TLRPC = jclass("org.telegram.tgnet.TLRPC")
        file = File(str(file_path))
        if not file.exists() or not file.isFile():
            log("_prepare_document: file not found: %s" % file_path)
            return None

        name = str(file.getName())
        size = int(file.length())
        extension = name.rsplit(".", 1)[-1].lower() if "." in name else ""

        document = TLRPC.TL_document()
        document.id = 0
        document.access_hash = 0
        document.file_reference = jarray("byte", [])
        document.date = int(get_connections_manager().getCurrentTime())
        document.mime_type = _document_mime_type(extension)
        document.size = size
        document.dc_id = 0
        document.attributes = ArrayList()

        file_name = TLRPC.TL_documentAttributeFilename()
        file_name.file_name = name
        document.attributes.add(file_name)

        if extension in _AUDIO_EXTENSIONS:
            audio = TLRPC.TL_documentAttributeAudio()
            audio.voice = extension == "opus"
            document.attributes.add(audio)
        elif extension == "gif":
            document.attributes.add(TLRPC.TL_documentAttributeAnimated())
        return document
    except Exception:
        log("_prepare_document: failed to prepare %s" % file_path)
        return None


class RequestCallback(dynamic_proxy(RequestDelegate)):
    """A ``RequestDelegate`` backed by a Python callable ``fn(response, error)``.

    The callback runs in the account resolved at construction: the explicit
    account, the enclosing callback scope, or the then-selected UI account.
    """

    def __init__(self, fn, account=None):
        super().__init__()
        self._fn = fn
        self._account = _resolve_account(account)

    def run(self, response, error):
        with account_scope(self._account):
            if self._fn is not None:
                self._fn(response, error)


def send_request(request, fn, account=None):
    """Send a raw ``TLObject`` request. Returns the ConnectionsManager request id."""
    account = _resolve_account(account)
    delegate = RequestCallback(fn, account)
    try:
        return int(ConnectionsManager.getInstance(account).sendRequest(request, delegate))
    except Exception:
        return 0


def _to_java_list(items):
    arr = ArrayList()
    if items is not None:
        for item in items:
            arr.add(item)
    return arr


_PARAM_FIELDS = (
    "peer", "message", "caption", "replyToMsg", "replyMarkup", "notify",
    "scheduleDate", "ttl", "hasMediaSpoilers", "sendingHighQuality",
    "searchLinks", "path", "photo", "document", "params", "location",
    "videoEditedInfo", "game", "poll", "pollSendParams", "invoice",
    "webPage", "mediaWebPage", "replyQuote",
)


def _build_send_params(data):
    p = SendMessagesHelper.SendMessageParams()
    if not isinstance(data, dict):
        return data
    for key, value in data.items():
        if key == "entities":
            p.entities = _to_java_list(value)
            continue
        if key in _PARAM_FIELDS:
            try:
                setattr(p, key, value)
            except Exception:
                pass
    return p


def send_message(params, parse_mode=None, account=None):
    """Send a message from a dict of ``SendMessageParams`` fields."""
    account = _resolve_account(account)
    data = params
    if isinstance(params, dict):
        data = dict(params)
        if parse_mode:
            key = "caption" if data.get("caption") else "message"
            text = data.get(key)
            if text:
                parsed = parse_text(text, parse_mode=parse_mode, is_caption=(key == "caption"))
                data[key] = parsed[key]
                if parsed.get("entities"):
                    data["entities"] = parsed["entities"]
    p = _build_send_params(data)
    SendMessagesHelper.getInstance(account).sendMessage(p)
    return p


def send_text(peer, text, *, account=None, parse_mode=None, **kwargs):
    data = {"peer": peer, "message": text}
    data.update(kwargs)
    return send_message(data, parse_mode=parse_mode, account=account)


def send_photo(peer, file_path, caption="", high_quality=False, *, account=None, parse_mode=None, **kwargs):
    data = {"peer": peer, "caption": caption, "sendingHighQuality": bool(high_quality), "path": file_path}
    data.update(kwargs)
    return send_message(data, parse_mode=parse_mode, account=account)


def send_document(peer, file_path, caption="", *, account=None, parse_mode=None, **kwargs):
    data = {"peer": peer, "caption": caption, "path": file_path}
    data.update(kwargs)
    return send_message(data, parse_mode=parse_mode, account=account)


def send_video(peer, file_path, caption="", *, account=None, parse_mode=None, **kwargs):
    data = {"peer": peer, "caption": caption, "path": file_path}
    data.update(kwargs)
    return send_message(data, parse_mode=parse_mode, account=account)


def send_audio(peer, file_path, caption="", *, account=None, parse_mode=None, **kwargs):
    data = {"peer": peer, "caption": caption, "path": file_path}
    data.update(kwargs)
    return send_message(data, parse_mode=parse_mode, account=account)


def edit_message(message_obj, text=None, file_path=None, with_spoiler=False, *, account=None, parse_mode=None, **kwargs):
    account = _resolve_account(account)
    helper = SendMessagesHelper.getInstance(account)
    if file_path is not None:
        helper.editMessage(message_obj, None, None, None, file_path, None, None, False, bool(with_spoiler), None)
    if text is not None:
        entities = None
        if parse_mode:
            parsed = parse_text(text, parse_mode=parse_mode)
            text = parsed["message"] if isinstance(parsed, dict) and "message" in parsed else text
            entities = parsed.get("entities") if isinstance(parsed, dict) else None
        if entities:
            arr = ArrayList()
            for e in entities:
                arr.add(e)
            helper.editMessage(message_obj, text, True, None, arr, 0, 0)
        else:
            helper.editMessage(message_obj, text, True, None)
    return message_obj


def get_client(account):
    return AccountClient(account)


class AccountClient:
    """A client scoped to a specific account; ``BasePlugin.client(account)`` returns one."""

    def __init__(self, account):
        self.account = account

    def __repr__(self):
        return "AccountClient(account=%r)" % (self.account,)

    def __eq__(self, other):
        return isinstance(other, AccountClient) and other.account == self.account

    def __hash__(self):
        return hash(self.account)

    def get_account_instance(self):
        return get_account_instance(self.account)

    def get_messages_controller(self):
        return get_messages_controller(self.account)

    def get_contacts_controller(self):
        return get_contacts_controller(self.account)

    def get_media_data_controller(self):
        return get_media_data_controller(self.account)

    def get_connections_manager(self):
        return get_connections_manager(self.account)

    def get_location_controller(self):
        return get_location_controller(self.account)

    def get_notifications_controller(self):
        return get_notifications_controller(self.account)

    def get_messages_storage(self):
        return get_messages_storage(self.account)

    def get_send_messages_helper(self):
        return get_send_messages_helper(self.account)

    def get_file_loader(self):
        return get_file_loader(self.account)

    def get_secret_chat_helper(self):
        return get_secret_chat_helper(self.account)

    def get_download_controller(self):
        return get_download_controller(self.account)

    def get_notifications_settings(self):
        return get_notifications_settings(self.account)

    def get_notification_center(self):
        return get_notification_center(self.account)

    def get_user_config(self):
        return get_user_config(self.account)

    def send_request(self, request, fn):
        return send_request(request, fn, account=self.account)

    def send_message(self, params, parse_mode=None):
        return send_message(params, parse_mode=parse_mode, account=self.account)

    def send_text(self, peer, text, parse_mode=None, **kwargs):
        return send_text(peer, text, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_photo(self, peer, file_path, caption="", high_quality=False, parse_mode=None, **kwargs):
        return send_photo(peer, file_path, caption, high_quality, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_document(self, peer, file_path, caption="", parse_mode=None, **kwargs):
        return send_document(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_video(self, peer, file_path, caption="", parse_mode=None, **kwargs):
        return send_video(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def send_audio(self, peer, file_path, caption="", parse_mode=None, **kwargs):
        return send_audio(peer, file_path, caption, account=self.account, parse_mode=parse_mode, **kwargs)

    def edit_message(self, message_obj, text=None, file_path=None, with_spoiler=False, parse_mode=None, **kwargs):
        return edit_message(message_obj, text, file_path, with_spoiler, account=self.account, parse_mode=parse_mode, **kwargs)


class NotificationCenterDelegate(dynamic_proxy(jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate"))):
    """Python base class for ``NotificationCenter.NotificationCenterDelegate``."""

    def __init__(self):
        super().__init__()

    def didReceivedNotification(self, id, account, args):
        pass
