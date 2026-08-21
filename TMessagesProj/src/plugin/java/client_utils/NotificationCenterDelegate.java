package client_utils;

import com.chaquo.python.PyCtorMarker;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.StaticProxy;

import org.telegram.messenger.NotificationCenter;

public class NotificationCenterDelegate implements NotificationCenter.NotificationCenterDelegate, StaticProxy {
    private PyObject _chaquopyDict;

    static {
        Python.getInstance().getModule("client_utils").get("NotificationCenterDelegate");
    }

    public NotificationCenterDelegate() {
        PyObject result = PyObject._chaquopyCall(this, "__init__", new Object[0]);
        if (result != null) {
            result.toJava(Void.TYPE);
        }
    }

    public NotificationCenterDelegate(PyCtorMarker marker) {
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        PyObject result = PyObject._chaquopyCall(this, "didReceivedNotification", id, account, args);
        if (result != null) {
            result.toJava(Void.TYPE);
        }
    }

    @Override
    public PyObject _chaquopyGetDict() {
        return _chaquopyDict;
    }

    @Override
    public void _chaquopySetDict(PyObject dict) {
        _chaquopyDict = dict;
    }
}
