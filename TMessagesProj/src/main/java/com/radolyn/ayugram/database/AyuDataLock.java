/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 保护 {@link AyuData} 数据库实例生命周期的读写锁。
 *
 * <p>数据库在导入/导出/清库期间会被关闭并置空，任何持有旧 DAO 的线程此时调用都会
 * 抛 NPE 或 Room 的连接池已关闭异常。约定：
 * <ul>
 *     <li>所有会关闭/替换 database 的操作持<b>写锁</b>（见 {@link AyuData}）；
 *     <li>所有 DAO 调用持<b>读锁</b>（见 {@link LockedDao}）。
 * </ul>
 * 于是 {@code database == null} 的窗口与任何读取互斥，无需在调用点判空。
 *
 * <p>用公平模式，避免持续的 DAO 读取把导入操作饿死。
 *
 * <p><b>注意</b>：锁只在本包内可见，且不要在持有读锁时去取写锁——
 * {@link ReentrantReadWriteLock} 不支持读→写升级，这样做会永久自锁。
 */
final class AyuDataLock {
    static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);

    private AyuDataLock() {
    }
}
