package de.robv.android.xposed.callbacks;

public abstract class XCallback implements Comparable<XCallback> {
    public static final int PRIORITY_DEFAULT = 50;
    public static final int PRIORITY_HIGHEST = 10000;
    public static final int PRIORITY_LOWEST = -10000;

    public final int priority;

    @Deprecated
    public XCallback() {
        this(PRIORITY_DEFAULT);
    }

    public XCallback(int priority) {
        this.priority = priority;
    }

    public static abstract class Param {
    }

    @Override
    public int compareTo(XCallback other) {
        if (this == other) {
            return 0;
        }
        if (other.priority != priority) {
            return other.priority - priority;
        }
        return System.identityHashCode(this) < System.identityHashCode(other) ? -1 : 1;
    }
}
