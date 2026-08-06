/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database;

import org.telegram.messenger.FileLog;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 把 Room DAO 包一层：每次调用都在 {@link AyuDataLock} 读锁内<b>现取</b>真实 DAO。
 *
 * <p>解决的问题是 DAO 曾以字段形式缓存，而 {@code closeDatabase()} 会把它们置空——
 * 调用方（尤其 UI 线程上的已删除消息查询）随时可能踩到 null 或已关闭的连接池。
 * 包装后 {@link AyuData} 的 getter 永远返回非 null，读取与关闭窗口互斥，
 * 调用点不再需要判空。
 *
 * <p>DAO 都是接口，因此用一个动态代理覆盖全部方法，省去逐个手写包装类。
 * 数据库确实不可用时（例如构建失败）按返回类型给安全默认值而非抛异常，
 * 保持"已删除消息功能降级、不拖垮聊天界面"的行为。
 */
final class LockedDao {

    private LockedDao() {
    }

    @SuppressWarnings("unchecked")
    static <T> T wrap(Class<T> daoClass, Supplier<T> provider) {
        return (T) Proxy.newProxyInstance(
                daoClass.getClassLoader(),
                new Class<?>[]{daoClass},
                new Handler<>(daoClass, provider)
        );
    }

    private static final class Handler<T> implements InvocationHandler {
        private final Class<T> daoClass;
        private final Supplier<T> provider;

        Handler(Class<T> daoClass, Supplier<T> provider) {
            this.daoClass = daoClass;
            this.provider = provider;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "hashCode":
                    if (args == null) {
                        return System.identityHashCode(proxy);
                    }
                    break;
                case "equals":
                    if (args != null && args.length == 1) {
                        return proxy == args[0];
                    }
                    break;
                case "toString":
                    if (args == null) {
                        return "LockedDao(" + daoClass.getSimpleName() + ")";
                    }
                    break;
                default:
                    break;
            }

            ReentrantReadWriteLock.ReadLock lock = AyuDataLock.LOCK.readLock();
            lock.lock();
            try {
                T dao = provider.get();
                if (dao == null) {
                    // 数据库不可用：降级而不是让调用方崩溃
                    FileLog.e("LockedDao: " + daoClass.getSimpleName() + " unavailable, skipping " + method.getName());
                    return emptyValueFor(method.getReturnType());
                }
                return method.invoke(dao, args);
            } catch (InvocationTargetException e) {
                // 解包，让调用方看到 DAO 本身抛的异常
                throw e.getCause() == null ? e : e.getCause();
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 数据库不可用时按返回类型给出的安全默认值。集合返回空集合而非 null，
     * 与 DAO 正常情况下的约定一致，免得调用方多一层判空。
     */
    private static Object emptyValueFor(Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (returnType == boolean.class || returnType == Boolean.class) {
            return false;
        }
        if (returnType == int.class || returnType == Integer.class) {
            return 0;
        }
        if (returnType == long.class || returnType == Long.class) {
            return 0L;
        }
        if (returnType == float.class || returnType == Float.class) {
            return 0f;
        }
        if (returnType == double.class || returnType == Double.class) {
            return 0d;
        }
        if (returnType == short.class || returnType == Short.class) {
            return (short) 0;
        }
        if (returnType == byte.class || returnType == Byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class || returnType == Character.class) {
            return (char) 0;
        }
        if (List.class.isAssignableFrom(returnType)) {
            // 必须可变：Room 返回的是 ArrayList，调用方存在 addAll 之类的原地修改
            // （如 AyuMessagesController.deleteCurrent），返回不可变空表会抛 UnsupportedOperationException
            return new ArrayList<>();
        }
        return null;
    }
}
