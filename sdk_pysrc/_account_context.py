"""Thread-local account scopes shared by SDK helpers and hook dispatch."""
from contextlib import contextmanager
from threading import local


_hook_account_local = local()


def get_hook_account():
    """Return the current callback's account, or None outside a scope."""
    return getattr(_hook_account_local, "account", None)


def _set_hook_account(account):
    _hook_account_local.account = int(account) if account is not None else None


@contextmanager
def account_scope(account):
    """Temporarily bind *account* on this thread and restore the enclosing scope."""
    previous = get_hook_account()
    _set_hook_account(account)
    try:
        yield account
    finally:
        _set_hook_account(previous)


def _wrap_with_current_account(fn):
    account = get_hook_account()

    def run():
        with account_scope(account):
            return fn()

    return run


def _call_account_hook(account, plugin, method, *args):
    """Enter and leave the scope within the same Python invocation as the hook."""
    with account_scope(account if account >= 0 else None):
        return getattr(plugin, method)(*args)
