# Chaquopy Python/JNI bridge source

This directory holds the Cython sources for the plugin flavor's Python bridge:

- `java/` — the `chaquopy` Cython extension module (`chaquopy.pyx` + `*.pxi`), built into
  `chaquopy.so`. It is the Python-facing `java` package that drives JNI class reflection,
  method dispatch and the modifier/lookup-plan machinery.
- `chaquopy_java.pyx` — the JNI entry module, built into `libchaquopy_java.so`. It provides
  `Java_com_chaquo_python_Python_startNative` and the `PyObject` JNI bindings.
- `android_platform.c` — `redirectStdioToLogcat` (stdout/stderr -> logcat).
- `chaquopy_extra.h` / `chaquopy_java_extra.h` — compile-time shims for the Cython output.

## Build

`cythonizeChaquopy` (Gradle) runs Cython to produce `chaquopy.c` / `chaquopy_java.c` under
`build/generated/chaquopy-cython/`, and CMake compiles them into the two shared libraries
(targets `chaquopy` and `chaquopy_java` in `../CMakeLists.txt`, plugin flavor + ARM only).

**Cython must be exactly 0.29.37** (`pip install Cython==0.29.37`). Both libraries are generated
against the same `java/chaquopy.pxd`, which describes the in-memory layout of the
`JNIRef`/`GlobalRef`/`LocalRef` cdef classes that the two `.so` files share across their boundary.
Cython 0.29 and 3.x emit different class layouts and module-init (PEP489) code; mixing a bridge
`.so` built by one major version with `libpython`/`libchaquopy_java` built for the other corrupts
the CPython garbage collector (`PyObject_GC_Del` crash) as soon as `Python.start()` runs.

The per-ABI `libpython3.11.so` and its `pyconfig.h` come from the `com.chaquo.python:target`
artifacts (unpacked by `prepareChaquopyTargetHeaders` into `build/chaquopy-target/<abi>/`). The
headers are word-size specific (SIZEOF_LONG=4 on armeabi-v7a, 8 on arm64-v8a), so each ABI is
compiled against its own include tree.

After the APK packages `chaquopy.so` into `assets/chaquopy/bootstrap-native/<abi>/java/`, the
`prepareChaquopyBootstrapNative` task refreshes the matching SHA-1 entries in
`src/plugin/assets/chaquopy/build.json` so the runtime asset validation accepts the new module.

## Provenance

`java/` is a source reconstruction of the Chaquopy fork bundled with the exteraGram plugin
engine; `chaquopy_java.pyx` and `android_platform.c` track upstream Chaquopy (MIT, Copyright
(c) Chaquo Ltd.).
