package com.chaquo.python;

import java.util.*;


class PyList extends AbstractList<PyObject> {
    private final PyObject obj;
    private final MethodCache methods;

    public PyList(PyObject obj) {
        this.obj = obj;
        methods = new MethodCache(obj);
        methods.get("__getitem__");
        methods.get("__len__");
    }

    // Python accepts negative indices, but the Java interface should reject them. We don't
    // usually check the upper bound, because that would make a redundant call to `__len__`.
    // Instead, the caller should catch IndexError.
    private void checkLowerBound(int index) {
        if (index < 0) {
            throw outOfBounds(index);
        }
    }

    private RuntimeException maybeOutOfBounds(int index, PyException e) {
        if (e.getMessage().startsWith("IndexError:")) {
            return outOfBounds(index);
        } else {
            return e;
        }
    }

    private IndexOutOfBoundsException outOfBounds(int index) {
        // Same wording as ArrayList exception.
        return new IndexOutOfBoundsException(
            "Invalid index " + index + ", size is " + size());
    }


    // === Read methods ======================================================

    @Override public int size() {
        return methods.get("__len__").call().toInt();
    }

    @Override public PyObject get(int index) {
        checkLowerBound(index);
        try {
            return methods.get("__getitem__").call(index);
        } catch (PyException e) {
            throw maybeOutOfBounds(index, e);
        }
    }


    // === Modification methods ==============================================
    //
    // These take PyObject (not Object) because an Object overload would erase to the same
    // signature as AbstractList.set/add. Extending List<Object> instead breaks `iterator`
    // (would have to return Iterator<Object>).

    public PyObject set(int index, PyObject element) {
        PyObject oldElement = get(index);  // Includes bounds check.
        methods.get("__setitem__").call(index, element);
        return oldElement;
    }

    @Override public void add(int index, PyObject element) {
        // For this method we need to check the upper bound as well, because `insert` accepts
        // any index and truncates it to the length of the sequence.
        checkLowerBound(index);
        if (index > size()) {
            throw outOfBounds(index);
        }
        methods.get("insert").call(index, element);  // Never throws IndexError.
    }

    @Override public PyObject remove(int index) {
        checkLowerBound(index);
        try {
            return methods.get("pop").call(index);
        } catch (PyException e) {
            throw maybeOutOfBounds(index, e);
        }
    }

    @Override public void clear() {
        methods.get("clear").call();
    }
}
