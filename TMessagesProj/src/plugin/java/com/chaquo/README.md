# Chaquopy Java runtime (plugin flavor)

This tree (`com/chaquo/python`) is the Java side of the plugin engine's Python runtime. It is
compiled from source into the `plugin` flavor only — the `normal` flavor never sees it.

## Provenance

These classes are a reconstruction of the Chaquopy fork shipped by the exteraGram plugin engine
(previously vendored as `libs/chaquopy_fork.jar`, a 28-class D8-desugared class jar extracted
from the reference APK). The fork differs from upstream Chaquopy in several ways:

- **`Reflector`** is a large extension of upstream (all/non-all member maps, property
  getter/setter machinery, `dir(boolean)` / `dirAll()`).
- **`PyIterator` / `PyObject.entrySet`** return `null` (with a log) when an iterator is
  exhausted instead of throwing `NoSuchElementException`; the plugin system relies on this.
- **`AndroidPlatform` / `GenericPlatform` / `Common`** log-and-continue (or log-and-crash)
  instead of throwing, and `Common` is a fork-generated configuration class (single Python
  3.11 version, custom asset names) that upstream generates per-build.

Where a class matched upstream Chaquopy 16.1.0 semantically, the upstream source was used;
where the fork diverged, the fork's decompiled bytecode was reconstructed. All 27 native
method declarations were cross-checked against the `libchaquopy_java.so` JNI exports — the
pairing is exact, so the vendored native bridge binds correctly at runtime.

The `$$ExternalSyntheticBUOutline0` references that plagued the old jar are gone: those were
D8 desugaring artifacts baked into the pre-dexed jar. Compiling from source regenerates them
with the current toolchain, which is why the R8 `-dontwarn` rules were removable.

## License

Upstream Chaquopy is MIT-licensed (Copyright (c) Chaquo Ltd.); the fork-derived classes carry
the same licensing context as their origin in the exteraGram plugin engine.
