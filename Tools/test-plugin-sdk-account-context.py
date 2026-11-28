"""Exercise the SDK's account routing without Android or Chaquopy."""

import sys
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Barrier
from types import ModuleType, SimpleNamespace


class PostedCallbacks:
    def __init__(self):
        self.calls = []

    def post(self, runnable, delay=0):
        self.calls.append((runnable, delay))


background = PostedCallbacks()
ui = PostedCallbacks()


class UserConfig:
    selectedAccount = 0

    @staticmethod
    def getInstance(account):
        return account


class ConnectionsManager:
    requests = []

    @classmethod
    def getInstance(cls, account):
        def send_request(request, delegate):
            cls.requests.append((account, request, delegate))
            return len(cls.requests)
        return SimpleNamespace(sendRequest=send_request)


java_classes = {
    "android.view.View": SimpleNamespace(OnClickListener=object),
    "org.telegram.messenger.Utilities": SimpleNamespace(
        pluginsQueue=SimpleNamespace(postRunnable=background.post),
        Callback=object, Callback2=object, Callback3=object, CallbackReturn=object,
    ),
    "org.telegram.messenger.AndroidUtilities": SimpleNamespace(runOnUIThread=ui.post),
    "org.telegram.messenger.AccountInstance": SimpleNamespace(getInstance=lambda account: account),
    "org.telegram.messenger.UserConfig": UserConfig,
    "org.telegram.tgnet.ConnectionsManager": ConnectionsManager,
    "com.exteragram.messenger.plugins.hooks.HookFilter": lambda kind: SimpleNamespace(kind=kind),
}
java = ModuleType("java")
java.jclass = lambda name: java_classes.get(name, object)
java.dynamic_proxy = lambda *interfaces: object
java.jarray = lambda component_type: list
sys.modules["java"] = java
# Elyx only uses PyYAML to read assets, which these tests do not exercise.
sys.modules["yaml"] = ModuleType("yaml")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "sdk_pysrc"))

# Load the hook dispatcher without importing the public utility modules.
from _sdk_version import _call_account_hook


class AccountContextTest(unittest.TestCase):
    def setUp(self):
        UserConfig.selectedAccount = 3
        background.calls.clear()
        ui.calls.clear()
        ConnectionsManager.requests.clear()

    def test_01_hook_can_be_first_import_of_client_utils(self):
        self.assertNotIn("client_utils", sys.modules)

        def hook(label, account, payload):
            from client_utils import get_account_instance, get_selected_account
            self.assertEqual(get_account_instance(), account)
            self.assertEqual(get_selected_account(), 3)
            return label, payload

        payload = object()
        self.assertEqual(_call_account_hook(0, SimpleNamespace(hook=hook), "hook", "update", 0, payload),
                         ("update", payload))
        from client_utils import get_account_instance
        self.assertEqual(get_account_instance(), 3)

    def test_explicit_account_and_bound_client(self):
        from base_plugin import BasePlugin
        from client_utils import account_scope, get_account_instance, get_current_account, get_selected_account

        plugin = BasePlugin()
        dynamic = plugin.client()
        with account_scope(1):
            bound = plugin.client()
            self.assertEqual(get_account_instance(0), 0)
            self.assertEqual(plugin.client(0).get_account_instance(), 0)
            self.assertEqual(dynamic.get_account_instance(), 1)
            self.assertEqual(get_account_instance(), 1)
            self.assertEqual((get_current_account(), get_selected_account()), (3, 3))
        UserConfig.selectedAccount = 2
        self.assertEqual(dynamic.get_account_instance(), 2)
        self.assertEqual(bound.get_account_instance(), 1)

    def test_nested_hooks_restore_scope_on_exception(self):
        from client_utils import get_account_instance, get_hook_account

        marker = RuntimeError("hook failed")
        calls = []

        def inner():
            calls.append(get_account_instance())
            raise marker

        def outer():
            self.assertEqual(get_account_instance(), 2)
            with self.assertRaises(RuntimeError) as failure:
                _call_account_hook(0, plugin, "inner")
            self.assertIs(failure.exception, marker)
            self.assertEqual(get_account_instance(), 2)
            self.assertEqual(_call_account_hook(-1, plugin, "account"), 3)
            self.assertEqual(get_account_instance(), 2)
            raise marker

        plugin = SimpleNamespace(inner=inner, outer=outer, account=get_account_instance)
        with self.assertRaises(RuntimeError) as failure:
            _call_account_hook(2, plugin, "outer")
        self.assertIs(failure.exception, marker)
        self.assertEqual(calls, [0])
        self.assertIsNone(get_hook_account())
        self.assertEqual(get_account_instance(), 3)

    def test_concurrent_hooks_have_independent_accounts(self):
        from client_utils import get_account_instance, get_hook_account

        barrier = Barrier(2, timeout=5)

        def hook(account):
            barrier.wait()
            resolved = get_account_instance()
            barrier.wait()
            self.assertEqual(resolved, account)
            return get_account_instance()

        def dispatch(account):
            result = _call_account_hook(account, SimpleNamespace(hook=hook), "hook", account)
            return result, get_hook_account(), get_account_instance()

        with ThreadPoolExecutor(max_workers=2) as executor:
            self.assertEqual(list(executor.map(dispatch, (0, 1))), [(0, None, 3), (1, None, 3)])

    def test_public_queues_capture_scope_and_restore_destination(self):
        import android_utils
        import client_utils as client
        from elyxcore._plugin_engine import ElyxEngine

        def elyx_ui(callback, delay=0):
            # Elyx's callback API has no delay parameter.
            ElyxEngine.run_callback(None, callback, on_ui_thread=True)

        for schedule, queue, expected_delay in ((client.run_on_queue, background, 12),
                                                (client.run_on_ui_thread, ui, 12),
                                                (android_utils.run_on_ui_thread, ui, 12), (elyx_ui, ui, 0)):
            with self.subTest(schedule=schedule):
                seen = []
                failure = RuntimeError("scheduled callback failed")
                UserConfig.selectedAccount = 3

                def bound_callback():
                    seen.append((client.get_hook_account(), client.get_account_instance()))
                    raise failure

                def unbound_callback():
                    seen.append((client.get_hook_account(), client.get_account_instance()))

                with client.account_scope(0):
                    schedule(bound_callback, delay=12)
                schedule(unbound_callback)
                (bound, delay), (unbound, default_delay) = queue.calls
                queue.calls.clear()
                self.assertEqual((delay, default_delay), (expected_delay, 0))
                UserConfig.selectedAccount = 2

                def receive():
                    self.assertIsNone(client.get_hook_account())
                    with client.account_scope(1):
                        with self.assertRaises(RuntimeError) as caught:
                            bound.run()
                        self.assertIs(caught.exception, failure)
                        self.assertEqual(client.get_hook_account(), 1)
                        unbound.run()
                        self.assertEqual(client.get_hook_account(), 1)
                    self.assertIsNone(client.get_hook_account())

                with ThreadPoolExecutor(max_workers=1) as executor:
                    executor.submit(receive).result()
                self.assertEqual(seen, [(0, 0), (None, 2)])

    def test_request_delegates_capture_request_account(self):
        from client_utils import RequestCallback, account_scope, get_account_instance, get_hook_account, send_request

        failure = RuntimeError("request callback failed")
        seen = []

        def callback(response, error):
            seen.append((get_account_instance(), response, error))
            if error:
                raise failure

        unscoped = RequestCallback(callback)
        with account_scope(1):
            scoped = RequestCallback(callback)
            explicit = RequestCallback(callback, account=0)
            self.assertEqual(send_request("default", callback), 1)
            self.assertEqual(send_request("explicit", callback, account=0), 2)
        self.assertEqual([(account, request) for account, request, _ in ConnectionsManager.requests],
                         [(1, "default"), (0, "explicit")])
        UserConfig.selectedAccount = 1

        def receive():
            with account_scope(2):
                for delegate in (unscoped, scoped, explicit, *(r[2] for r in ConnectionsManager.requests)):
                    with self.assertRaises(RuntimeError) as caught:
                        delegate.run("response", "error")
                    self.assertIs(caught.exception, failure)
                    self.assertEqual(get_hook_account(), 2)
            self.assertIsNone(get_hook_account())

        with ThreadPoolExecutor(max_workers=1) as executor:
            executor.submit(receive).result()
        self.assertEqual(seen, [(account, "response", "error") for account in (3, 1, 0, 1, 0)])


if __name__ == "__main__":
    unittest.main()
