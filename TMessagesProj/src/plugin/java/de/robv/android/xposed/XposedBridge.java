package de.robv.android.xposed;

import android.util.Log;
import android.os.SystemClock;

import com.sun.jna.Callback;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class XposedBridge {
    private static final String TAG = "AliuHook-XposedBridge";
    private static final Object[] EMPTY_ARRAY;
    private static final Method CALLBACK_METHOD;
    private static final Map<Member, HookInfo> HOOK_RECORDS;

    private static native Method hook0(Object callback, Member member, Method method);
    private static native boolean unhook0(Member member);
    private static native boolean deoptimize0(Member member);
    private static native Object allocateInstance0(Class<?> cls);
    private static native boolean invokeConstructor0(Object instance, Constructor<?> constructor, Object[] args);
    private static native boolean makeClassInheritable0(Class<?> cls);
    private static native String getInitDiag();
    public static native boolean disableHiddenApiRestrictions();
    public static native boolean disableProfileSaver();

    private static volatile boolean nativeAvailable;

    static {
        try {
            CALLBACK_METHOD = HookInfo.class.getMethod(Callback.METHOD_NAME, Object[].class);
            EMPTY_ARRAY = new Object[0];
            HOOK_RECORDS = new HashMap<>();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to initialize XposedBridge metadata", t);
        }
        try {
            System.loadLibrary("aliuhook");
            nativeAvailable = true;
        } catch (Throwable t) {
            nativeAvailable = false;
            Log.e("XposedBridge", "Xposed native runtime (libaliuhook.so) is unavailable; method hooking is disabled. " +
                    "Plugins that hook Java methods will not function until the native library is provided.", t);
        }
    }

    public static void ensureInitialized() {
        if (!nativeAvailable) {
            return;
        }
        try {
            initRuntime();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to force native runtime initialization", t);
        }
    }

    private static native void initRuntime();

    private static void ensureNativeAvailable() {
        if (!nativeAvailable) {
            throw new IllegalStateException("Xposed native runtime unavailable (libaliuhook.so not loaded); method hooking is disabled.");
        }
    }

    public static boolean isNativeAvailable() {
        return nativeAvailable;
    }

    private static void checkMethod(Member member) {
        if (member == null) {
            throw new NullPointerException("method must not be null");
        }
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("method must be a Method or Constructor");
        }
        if (Modifier.isAbstract(member.getModifiers())) {
            throw new IllegalArgumentException("method must not be abstract");
        }
    }

    public static boolean isHooked(Member member) {
        return HOOK_RECORDS.containsKey(member);
    }

    public static boolean makeClassInheritable(Class<?> cls) {
        if (cls == null) {
            throw new NullPointerException("class must not be null");
        }
        ensureNativeAvailable();
        return makeClassInheritable0(cls);
    }

    public static boolean deoptimizeMethod(Member member) {
        checkMethod(member);
        ensureNativeAvailable();
        return deoptimize0(member);
    }

    public static final long HOOK_BACKUP_AWAIT_TIMEOUT_MS = 1000L;
    private static final long HOOK_BACKUP_POLL_INTERVAL_MS = 5L;

    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        checkMethod(member);
        if (callback == null) {
            throw new NullPointerException("callback must not be null");
        }
        ensureNativeAvailable();
        long hookStart = SystemClock.uptimeMillis();
        HookInfo hookInfo;
        synchronized (HOOK_RECORDS) {
            hookInfo = HOOK_RECORDS.get(member);
            if (hookInfo == null) {
                hookInfo = new HookInfo(member);
                // LSPlant's hook returns the backup method or null; retry briefly to absorb a
                // transient ART deoptimization, but never spin a full second per hook.
                Method backup = null;
                for (int attempt = 1; attempt <= 3 && backup == null; attempt++) {
                    long attemptStart = SystemClock.uptimeMillis();
                    backup = hook0(hookInfo, member, CALLBACK_METHOD);
                    long attemptMs = SystemClock.uptimeMillis() - attemptStart;
                    if (attemptMs > 2) {
                        FileLog.d("XposedBridge.hookMethod: hook0 attempt " + attempt + " for " + member.getName() + " took " + attemptMs + "ms (backup=" + (backup != null) + ")");
                    }
                    if (backup == null && attempt < 3) {
                        SystemClock.sleep(HOOK_BACKUP_POLL_INTERVAL_MS);
                    }
                }
                long totalMs = SystemClock.uptimeMillis() - hookStart;
                FileLog.d("XposedBridge.hookMethod: hooked " + member.getName() + " in " + totalMs + "ms (backup=" + (backup != null) + ")");
                if (backup == null) {
                    String diag = null;
                    try {
                        diag = getInitDiag();
                    } catch (Throwable ignored) {
                    }
                    FileLog.e("XposedBridge.hookMethod: FAILED for " + member.getName() + ". Native init diagnostics:\n" + (diag == null ? "<unavailable>" : diag));
                    throw new IllegalStateException("Failed to hook method (backup unavailable)");
                }
                hookInfo.backup = backup;
                HOOK_RECORDS.put(member, hookInfo);
            }
        }
        hookInfo.callbacks.add(callback);
        return callback.new Unhook(member);
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> cls, String methodName, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                result.add(hookMethod(method, callback));
            }
        }
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> cls, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
            result.add(hookMethod(constructor, callback));
        }
        return result;
    }

    @Deprecated
    public static void unhookMethod(Member member, XC_MethodHook callback) {
        ensureNativeAvailable();
        synchronized (HOOK_RECORDS) {
            HookInfo hookInfo = HOOK_RECORDS.get(member);
            if (hookInfo == null) {
                return;
            }
            hookInfo.callbacks.remove(callback);
            if (hookInfo.callbacks.size() == 0) {
                HOOK_RECORDS.remove(member);
                unhook0(member);
            }
        }
    }

    public static Object invokeOriginalMethod(Member member, Object receiver, Object[] args) {
        if (args == null) {
            args = EMPTY_ARRAY;
        }
        ensureNativeAvailable();
        HookInfo hookInfo = HOOK_RECORDS.get(member);
        try {
            if (hookInfo != null && hookInfo.backup != null) {
                return invokeMethod(hookInfo.backup, receiver, args);
            }
            checkMethod(member);
            return invokeMethod(member, receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to access original method", e);
        } catch (InstantiationException e) {
            throw new IllegalArgumentException("The class this Constructor belongs to is abstract and cannot be instantiated");
        }
    }

    static Object invokeMethod(Member member, Object receiver, Object[] args) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        if (member instanceof Method method) {
            method.setAccessible(true);
            return method.invoke(receiver, args);
        }
        Constructor<?> constructor = (Constructor<?>) member;
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    @SuppressWarnings("unchecked")
    public static <T> T allocateInstance(Class<T> cls) {
        Objects.requireNonNull(cls);
        return (T) allocateInstance0(cls);
    }

    public static <S, T extends S> boolean invokeConstructor(T instance, Constructor<S> constructor, Object... args) {
        Objects.requireNonNull(instance);
        Objects.requireNonNull(constructor);
        if (constructor.isVarArgs()) {
            throw new IllegalArgumentException("varargs parameters are not supported");
        }
        if (args.length == 0) {
            args = null;
        }
        return invokeConstructor0(instance, constructor, args);
    }

    public static final class CopyOnWriteSortedSet<E> {
        private volatile Object[] elements = EMPTY_ARRAY;

        public int size() {
            return elements.length;
        }

        public synchronized boolean add(E element) {
            if (indexOf(element) >= 0) {
                return false;
            }
            Object[] newElements = new Object[elements.length + 1];
            System.arraycopy(elements, 0, newElements, 0, elements.length);
            newElements[elements.length] = element;
            Arrays.sort(newElements);
            elements = newElements;
            return true;
        }

        public synchronized boolean remove(E element) {
            int index = indexOf(element);
            if (index == -1) {
                return false;
            }
            Object[] newElements = new Object[elements.length - 1];
            System.arraycopy(elements, 0, newElements, 0, index);
            System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
            elements = newElements;
            return true;
        }

        private int indexOf(Object element) {
            for (int i = 0; i < elements.length; i++) {
                if (element.equals(elements[i])) {
                    return i;
                }
            }
            return -1;
        }

        public Object[] getSnapshot() {
            return elements;
        }
    }

    public static class HookInfo {
        Member backup;
        final CopyOnWriteSortedSet<XC_MethodHook> callbacks = new CopyOnWriteSortedSet<>();
        private final Member method;
        private final boolean isStatic;
        private final Class<?> returnType;

        public HookInfo(Member method) {
            this.method = method;
            this.isStatic = Modifier.isStatic(method.getModifiers());
            if (method instanceof Method m && !m.getReturnType().isPrimitive()) {
                this.returnType = m.getReturnType();
            } else {
                this.returnType = null;
            }
        }

        public Object callback(Object[] args) throws Throwable {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
            param.method = method;
            if (isStatic) {
                param.thisObject = null;
                param.args = args;
            } else {
                param.thisObject = args[0];
                Object[] methodArgs = new Object[args.length - 1];
                param.args = methodArgs;
                System.arraycopy(args, 1, methodArgs, 0, methodArgs.length);
            }
            Object[] snapshot = callbacks.getSnapshot();
            int length = snapshot.length;
            if (length == 0) {
                try {
                    return XposedBridge.invokeMethod(backup, param.thisObject, param.args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
            int beforeIndex = 0;
            while (beforeIndex < length) {
                try {
                    ((XC_MethodHook) snapshot[beforeIndex]).beforeHookedMethod(param);
                } catch (Throwable t) {
                    log(t);
                    param.setResult(null);
                    param.returnEarly = false;
                }
                beforeIndex++;
                if (param.returnEarly) {
                    break;
                }
            }
            if (!param.returnEarly) {
                try {
                    param.setResult(XposedBridge.invokeMethod(backup, param.thisObject, param.args));
                } catch (InvocationTargetException e) {
                    param.setThrowable(e.getCause());
                }
            }
            for (int i = beforeIndex - 1; i >= 0; i--) {
                Object result = param.getResult();
                Throwable throwable = param.getThrowable();
                try {
                    ((XC_MethodHook) snapshot[i]).afterHookedMethod(param);
                } catch (Throwable t) {
                    log(t);
                    if (throwable == null) {
                        param.setResult(result);
                    } else {
                        param.setThrowable(throwable);
                    }
                }
            }
            Object resultOrThrowable = param.getResultOrThrowable();
            return returnType != null ? returnType.cast(resultOrThrowable) : resultOrThrowable;
        }
    }

    private static void log(Throwable t) {
        Log.e(TAG, "Uncaught Exception", t);
    }
}
