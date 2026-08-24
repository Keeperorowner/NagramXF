from os.path import dirname, exists
from shutil import which
from . import config

from libc.stdint cimport uintptr_t


cdef extern from 'dlfcn.h':
    void* dlopen(const char *filename, int flag)
    char *dlerror()
    void *dlsym(void *handle, const char *symbol)
    int dlclose(void *handle)
    unsigned RTLD_LAZY, RTLD_NOW, RTLD_GLOBAL, RTLD_LOCAL, RTLD_DEFAULT, RTLD_NEXT


cdef JavaVM *jvm = NULL

cdef JNIEnv *get_jnienv() except NULL:
    if jvm == NULL:
        raise Exception("JVM not set")

    # See comment in jni.pxd.
    cdef Attach_JNIEnv *env = NULL
    ret = jvm[0].AttachCurrentThread(jvm, &env, NULL)
    if ret != JNI_OK:
        raise Exception("AttachCurrentThread failed: {}".format(ret))
    return env


# See CQPEnv.FindClass
cdef GlobalRef j_class_loader
cdef jmethodID mid_forName = NULL

cdef _bootstrap_exception_summary(exc):
    """one-line summary of a bootstrap exception."""
    try:
        return f"{type(exc).__name__}({exc})"
    except Exception:
        return "Unknown bootstrap exception"


cdef _bootstrap_trace():
    """compact traceback listing for a bootstrap failure."""
    import traceback as tb_mod
    try:
        ei = sys.exc_info()
        frames = []
        tb = ei[2]
        while tb is not None:
            frame = tb.tb_frame
            code = frame.f_code
            frames.append(f"File {code.co_filename} line {tb.tb_lineno} in {code.co_name}")
            tb = tb.tb_next
        return "; ".join(frames)
    except Exception:
        return "<traceback unavailable>"


cdef _bootstrap_stage_error(prefix):
    """raises a diagnostic exception for a failed bootstrap stage."""
    raise Exception(f"{prefix}: {_bootstrap_exception_summary(sys.exc_info()[1])}")


cdef set_jvm(JavaVM *new_jvm):
    global jvm
    if jvm != NULL:
        raise Exception("set_jvm cannot be called more than once")
    jvm = new_jvm
    config.vm_running = True

    # Wrap each phase so failures identify the phase.
    try:
        setup_bootstrap_classes()
    except Exception as e:
        raise Exception(_bootstrap_error_message(
            "set_jvm failed in setup_bootstrap_classes")) from e
    try:
        set_import_enabled(True)
    except Exception as e:
        raise Exception(_bootstrap_error_message(
            "set_jvm failed in set_import_enabled")) from e

    global j_class_loader, mid_forName
    try:
        env = CQPEnv()
        j_class_loader = jclass("com.chaquo.python.Python").getClass().getClassLoader()._chaquopy_this
        mid_forName = env.GetStaticMethodID(
            Class._chaquopy_j_klass, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;")
    except Exception as e:
        raise Exception(_bootstrap_error_message(
            "set_jvm failed while resolving bootstrap reflection handles")) from e


cdef JavaVM *start_jvm() except NULL:
    lib_path = jvm_lib_path()
    cdef void *handle = dlopen(str_for_c(lib_path), RTLD_NOW | RTLD_GLOBAL)
    if handle == NULL:
        raise Exception("dlopen: {0}: {1}".format(lib_path, dlerror()))

    cdef void *jniCreateJVM = dlsym(handle, b"JNI_CreateJavaVM")
    if jniCreateJVM == NULL:
       raise Exception("dlfcn: JNI_CreateJavaVM: {0}".format(dlerror()))

    optarr = [str_for_c(opt) for opt in config.options]
    optarr.append(str_for_c("-Djava.class.path=" + config.expand_classpath()))
    cdef JavaVMOption *options = <JavaVMOption*>alloca(sizeof(JavaVMOption) * len(optarr))
    for i, opt in enumerate(optarr):
        options[i].optionString = opt
        options[i].extraInfo = NULL

    cdef JavaVMInitArgs args
    args.version = JNI_VERSION_1_6
    args.options = options
    args.nOptions = len(optarr)
    args.ignoreUnrecognized = JNI_FALSE

    cdef JavaVM* jvm = NULL
    cdef JNIEnv *env = NULL
    ret = (<jint (*)(JavaVM **pvm, void **penv, void *args)> jniCreateJVM) \
          (&jvm, <void **>&env, &args)
    if ret != JNI_OK:
        raise Exception("JNI_CreateJavaVM failed: {0}".format(ret))

    return jvm


cdef jvm_lib_path():
    try:
        java_home = os.environ['JAVA_HOME']
    except KeyError:
        java_executable = which("java")
        if not java_executable:
            raise Exception("JAVA_HOME is not set, and 'java' is not on the PATH")
        java_home = dirname(dirname(java_executable))
    else:
        if not os.path.exists(java_home):
            raise Exception(f"JAVA_HOME ({java_home}) does not exist")

    if sys.platform.startswith("darwin"):
        subdirs = ["jre/lib"]
        filename = "libjvm.dylib"
    elif sys.platform.startswith("win") or sys.platform.startswith("cygwin"):
        subdirs = ["jre/bin",  # Java 8
                   "bin"]      # Java 11
        filename = "jvm.dll"
    else:  # Assume Linux
        # TODO #1201: untested
        import platform
        machine2cpu = {
            "amd64": "amd64",
            "x86_64": "amd64",
            "arm": "arm",
            "armv7l": "arm",
            "i386": "i386",
            "i686": "i386",
            "x86": "i386",
        }
        subdirs =[f"jre/lib/{machine2cpu[platform.machine().lower()]}",  # Java 8
                  "lib"]                                                 # Java 11
        filename = "libjvm.so"

    for subdir in subdirs:
        abs_filename = f"{java_home}/{subdir}/server/{filename}"
        if exists(abs_filename):
            return abs_filename
    else:
        raise Exception(f"Couldn't find {filename} in {java_home}")


cpdef detach():
    """Detaches the current thread from the Java VM. This is done automatically on exit for threads
    created via the :any:`threading` module. Any other non-Java-created thread which uses the
    `java` module must call `detach` before the thread exits, or the process may crash.
    """
    jvm[0].DetachCurrentThread(jvm)
    # Ignore return value, because we call this automatically for all threads, including those
    # which were never attached.
