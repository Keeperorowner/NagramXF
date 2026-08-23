#!/usr/bin/env python3
"""Builds the exteraGram-fork chaquopy.so from sdk_chaqsrc sources.

Usage: python Tools/build-chaquopy-so.py <abi> [<out.so>]
  abi: arm64-v8a | armeabi-v7a

Requires: Cython 3.0.11, Android NDK (r27+, clang), and the Chaquopy target zip
(com.chaquo.python:target:3.11.10-1:<abi>) extracted or present in the Gradle cache.

The CPython headers/library come from the target zip so the produced .so is ABI-paired
with the bundled CPython runtime.
"""
import os, subprocess, sys, tempfile, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'sdk_chaqsrc')
GRADLE_TARGET_DIR = r'D:\Android\.gradle\caches\modules-2\files-2.1\com.chaquo.python\target\3.11.10-1'
NDK_CANDIDATES = [
    r'D:\Android\Sdk\ndk\27.2.12479018',
    r'D:\Android\Sdk\ndk\30.0.14206865',
]

TARGET_HASH = {
    'arm64-v8a': '7b14252bb8c2a222d8eb16229512664a09db9d66',
    'armeabi-v7a': 'e5da75c57c4cae156eaa66450fe376af11e4e5de',
}
CLANG_TARGET = {
    'arm64-v8a': 'aarch64-linux-android24',
    'armeabi-v7a': 'armv7a-linux-androideabi24',
}

def find_clang(ndk):
    host = 'windows-x86_64'
    p = os.path.join(ndk, 'toolchains', 'llvm', 'prebuilt', host, 'bin', 'clang.exe')
    return p if os.path.exists(p) else None

def find_ndk():
    for ndk in NDK_CANDIDATES:
        if find_clang(ndk):
            return ndk
    # fall back to ANDROID_NDK_HOME
    env = os.environ.get('ANDROID_NDK_HOME')
    if env and find_clang(env):
        return env
    raise SystemExit('No NDK with clang found')

def extract_target(abi, dest):
    h = TARGET_HASH[abi]
    z = os.path.join(GRADLE_TARGET_DIR, h, f'target-3.11.10-1-{abi}.zip')
    if not os.path.exists(z):
        raise SystemExit(f'target zip missing: {z}')
    subprocess.check_call(['unzip','-o','-q',z,'-d',dest])

def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    abi = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else f'chaquopy-{abi}.so'
    ndk = find_ndk()
    clang = find_clang(ndk)
    work = tempfile.mkdtemp(prefix=f'chaqbuild-{abi}-')
    extract_target(abi, work)
    inc = os.path.join(work, 'include', 'python3.11')
    libs = os.path.join(work, 'jniLibs', abi)

    # 1) cython (generate C into the temp work dir, keep the source tree clean)
    java_dir = os.path.join(SRC, 'java')
    c_file = os.path.join(work, 'chaquopy.c')
    subprocess.check_call([sys.executable, '-m', 'cython', '-Wextra',
                           'chaquopy.pyx', '-o', c_file], cwd=java_dir)

    # 2) clang
    cmd = [clang, f'--target={CLANG_TARGET[abi]}', '-shared', '-fPIC', '-O2', '-DNDEBUG',
           '-I', os.path.join(SRC, 'c'), '-I', inc,
           c_file, '-L', libs, '-lpython3.11', '-ldl', '-llog', '-landroid', '-o', out]
    subprocess.check_call(cmd)
    print('Built:', out)

if __name__ == '__main__':
    main()
