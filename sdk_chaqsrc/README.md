# sdk_chaqsrc — Chaquopy Java bridge 源码（exteraGram fork 还原）

本目录包含 `chaquopy.so`（Cython 扩展模块，插件系统的 Java↔Python 桥）的完整源码，
从 exteraGram fork 的预编译二进制逆向还原，基于上游 Chaquopy 16.1.0。

## 结构

- `java/` — Cython 源码：`chaquopy.pyx` 主文件 + 12 个 `.pxi` include + `.py` 辅助。
  与上游 16.1.0 的差异即 exteraGram fork 的全部定制（见下）。
- `chaquopy_java.pyx` — JNI 桥 `libchaquopy_java.so` 的源码，上游 16.1.0 原样
  （fork 未修改；构建时 cimport 本目录 `java/` 下的 fork 版 `chaquopy.pxd`）。
- `c/` — Cython 生成代码引用的 C 头/源（`chaquopy_extra.h` 等，取自上游仓库同版本）。

## Fork 定制内容（相对上游 16.1.0）

全部位于 Python 层，Java 侧 Reflector 已在 `TMessagesProj/src/plugin/java/com/chaquo/python/Reflector.java`
还原并扩展（`dirAll`/`getMethodsAll`/`getFieldAll`/`getNestedClassAll`/`getPropertyGetters`/
`getPropertySetters`）。

1. **性能计时**（`class.pxi` 头部）：`_chaquopy_timing_now_ms` / `_chaquopy_should_timing_log`
   / `_chaquopy_timing_log`；dunder 方法（get/set/dir）带计时包装。默认关闭
   （`j_timing_enabled=False`），零开销。
2. **修饰符机制**：实例/类属性访问拦截 `JAccessAll`(JA) / `JNotAccessAll`(JNA) /
   `JUseGetterAndSetter`(JGS) / `JNGS` 等名字，切换对象的 `_chaquopy_j_access_all` /
   `_chaquopy_j_use_getter_setter` 标志；scoped 版（JIgnoreResult/JIR、JSafe/JS…）
   只作用于当前调用链。
3. **链式调用**：`_ChaquopyJChain`（`.value/.ignore_result/.safe/.receiver` 四字段 +
   `_with/_target`），`__getattr__` 解析普通属性时经 `_chaquopy_chain_getattr` 继续成链；
   safe 模式吞 AttributeError 返回 `JNone`。`_ChaquopyJNone` 单例 `JNone`：
   repr="JNone"、falsy、属性/调用返回自身、赋值静默丢弃 —— Java null 的安全替身。
4. **getter/setter 计划缓存**：`_chaquopy_build_getattr_plan/_build_setattr_plan` 按
   `(kind, name, access_all, use_gs, static_filter)` 缓存到
   `cls.__dict__["_chaquopy_j_lookup_cache"]`；property 方法经 Reflector 的
   `getPropertyGetters/Setters(name, accessAll, staticMode)` 查询（含 JavaBean
   `isXxx→xxx` 别名）。命中 getter 时安装 `_ChaquopyJChain` 风格的
   `JavaPropertyAlias` 描述符到类上。
5. **CharSequence 转换**（`conversion.pxi` + `JavaObject.__str__`）：非 String 的
   CharSequence 经 `new StringBuilder(cs).toString()` 转 Python str，避免对非 String
   对象误用 GetStringChars 快速路径。
6. **bootstrap 容错**（`jvm.pxi`/`class.pxi`）：`set_jvm`/`setup_bootstrap_classes`
   各阶段 try/except 包装，失败信息标注阶段名；`Throwable_str` 在 Throwable 类未就绪时
   回退为类全名。
7. 其它：`Reflector.dir` 带 `(Z)` 参数签名；`special_attrs` 扩展了 fork 自身属性名；
   移除了已废弃的 `PyEval_InitThreads()` 调用。

## 构建

构建已集成进 Gradle（`TMessagesProj/build.gradle` + buildSrc 的 `org.telegram.chaquopy` 任务），
构建 plugin flavor 时自动完成：Cython → NDK clang（目标 API 27，即 app minSdk），产出
`chaquopy.so` 与 `libchaquopy_java.so`，同时生成与当次产物哈希一致的 `assets/chaquopy/build.json`。

要求：host 上有 Python 3.11+ 且 `pip install Cython==3.0.11`；NDK 见根 `build.gradle` 的
`ndkVersion`。CPython 头文件与 `libpython3.11.so` 来自 `com.chaquo.python:target:3.11.10-1`
构件（按 ABI 分别解包，`pyconfig.h` 等头文件随 ABI 不同），保证与 assets 内运行时 ABI 配对。

`Tools/build-chaquopy-so.py` 是早期的独立构建原型（含本机硬编码路径），仅留作参考，
正式构建不经过它。

## 还原方法与验证

以差分为主：上游 16.1.0 源码为基线，用 IDA Pro 反编译 vendored `chaquopy.so`
（arm64 未 strip，Cython 符号全保留），按 `__pyx_pf_/pw_/f_/k_` 符号与 py 行号逐函数
对照还原。fork 相对上游无删除、仅新增（3 个新类、约 80 个新函数）。

验证（arm64，Cython 3.0.11 编译产物 vs 原版 .so）：
- `__pyx_n_s_*` 标识符常量集合：完全一致（0 差异）；
- `__pyx_pw_*`（方法 wrapper）语义集合：完全一致（0 差异）；
- `__pyx_pf_*`（函数体）：一致（解析层面前缀差异除外）；
- 少量内部 cdef helper 因优化内联符号不可见（O0 下可见），不影响行为。

原版构建参数未知；本目录用 `-O2` 产出更小的 .so，行为等价性以上述符号集验证为准。
