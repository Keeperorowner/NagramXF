package com.chaquo.python;

import android.util.Log;

import java.util.Iterator;

/**
 * Fork divergence: {@link #next()} returns {@code null} (with a log) when exhausted, instead
 * of throwing {@link java.util.NoSuchElementException}.
 */
abstract class PyIterator<T> implements Iterator<T> {
    private boolean hasNextElem = true;
    private PyObject iter;
    private PyObject nextElem;

    public PyIterator(MethodCache methods) {
        iter = methods.get("__iter__").call();
        updateNext();
    }

    public void updateNext() {
        try {
            nextElem = iter.callAttr("__next__");
        } catch (PyException e) {
            if (e.getMessage().startsWith("StopIteration:")) {
                hasNextElem = false;
                nextElem = null;
            } else {
                throw e;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return hasNextElem;
    }

    @Override
    public T next() {
        if (!hasNext()) {
            Log.w("chaquopy", "PyIterator: no more elements");
            return null;
        }
        T result = makeNext(nextElem);
        updateNext();
        return result;
    }

    public abstract T makeNext(PyObject element);

    @Override
    public void remove() {
        throw new UnsupportedOperationException(
                "Python does not support removing from a container while iterating over it");
    }
}
