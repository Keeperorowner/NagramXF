package android_utils;

import com.chaquo.python.PyCtorMarker;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.StaticProxy;

public class R implements Runnable, StaticProxy {
    private PyObject _chaquopyDict;

    static {
        Python.getInstance().getModule("android_utils").get("R");
    }

    public R() {
        PyObject result = PyObject._chaquopyCall(this, "__init__", new Object[0]);
        if (result != null) {
            result.toJava(Void.TYPE);
        }
    }

    public R(PyCtorMarker marker) {
    }

    @Override
    public void run() {
        PyObject result = PyObject._chaquopyCall(this, "run", new Object[0]);
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
