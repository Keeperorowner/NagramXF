/*
 * aliuhook - native runtime for the plugin subsystem (Xposed-style Java method hooking).
 *
 * Port of exteraGram's embedded "exteraHook" runtime (found inside their
 * libtmessages.49.so, JNI bindings dev.exterahook.runtime.bridge.JniBridgeBindings),
 * rebuilt on top of the same upstream components:
 *   - LSPlant (https://github.com/LSPosed/LSPlant, LGPL-3.0)  - ART method hooking
 *   - shadowhook (https://github.com/bytedance/android-inline-hook, MIT) - inline hooks
 *
 * Provides the natives declared by:
 *   - de.robv.android.xposed.XposedBridge   (8 methods)
 *   - com.exteragram.messenger.plugins.utils.NativeCrashHandler (1 method)
 *
 * Only built for arm64-v8a / armeabi-v7a (shadowhook has no x86 support).
 */

#include <jni.h>

#include <android/api-level.h>
#include <android/log.h>

#include <cerrno>
#include <climits>
#include <csignal>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <string_view>
#include <unordered_map>

#include <fcntl.h>
#include <link.h>
#include <stdint.h>
#include <sys/mman.h>
#include <unistd.h>

#include <lsplant.hpp>
#include <shadowhook.h>

#define LOG_TAG "AliuHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_vm = nullptr;

// Routes diagnostics into Telegram's FileLog so they land in the exported .txt
// logs (the AliuHook logcat tag is usually filtered out of user exports).
static void NativeLog(const char *fmt, ...) {
    if (g_vm == nullptr) {
        return;
    }
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);

    JNIEnv *env = nullptr;
    bool attached = false;
    jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attached = true;
    }
    if (env == nullptr) {
        return;
    }
    jclass cls = env->FindClass("org/telegram/messenger/FileLog");
    if (cls != nullptr) {
        jmethodID mid = env->GetStaticMethodID(cls, "d", "(Ljava/lang/String;)V");
        if (mid != nullptr) {
            jstring jmsg = env->NewStringUTF(buf);
            if (jmsg != nullptr) {
                env->CallStaticVoidMethod(cls, mid, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        }
        env->DeleteLocalRef(cls);
    }
    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

// Accumulated native-init diagnostics, surfaced later via getInitDiag() once
// FileLog is ready (FileLog is not initialized during JNI_OnLoad).
std::string g_init_diag;

static void AppendDiag(const char *fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    g_init_diag += buf;
    g_init_diag += "\n";
}

// Exposed to LSPlant (lsplant.cc) so its Init failure diagnostics also reach
// FileLog instead of only the (usually filtered) LSPlant logcat tag.
extern "C" void AliuHookLog(const char *msg) {
    NativeLog("%s", msg);
    AppendDiag("%s", msg);
}

namespace {

// ---------------------------------------------------------------------------
// shadowhook glue (target -> stub map so LSPlant's unhook-by-target works)
// ---------------------------------------------------------------------------

std::mutex g_stub_mutex;
std::unordered_map<void *, void *> g_hook_stubs;

void *InlineHooker(void *target, void *hooker) {
    void *backup = nullptr;
    void *stub = shadowhook_hook_func_addr(target, hooker, &backup);
    if (stub == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_hook_func_addr failed for %p: %d - %s", target, err,
             shadowhook_to_errmsg(err));
        NativeLog("[AliuHook] inline hook failed for %p: %d - %s", target, err,
                  shadowhook_to_errmsg(err));
        AppendDiag("inline hook failed for %p: %d - %s", target, err, shadowhook_to_errmsg(err));
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(g_stub_mutex);
        g_hook_stubs[target] = stub;
    }
    return backup;
}

bool InlineUnhooker(void *target) {
    void *stub = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_stub_mutex);
        auto it = g_hook_stubs.find(target);
        if (it != g_hook_stubs.end()) {
            stub = it->second;
            g_hook_stubs.erase(it);
        }
    }
    if (stub == nullptr) {
        LOGW("No shadowhook stub registered for %p", target);
        return false;
    }
    if (int r = shadowhook_unhook(stub); r != 0) {
        int err = shadowhook_get_errno();
        LOGE("shadowhook_unhook failed for %p: %d - %s", target, err, shadowhook_to_errmsg(err));
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// libart symbol resolution
// ---------------------------------------------------------------------------

void *g_art_handle = nullptr;

void *ResolveArtSymbol(std::string_view name) {
    if (g_art_handle == nullptr) {
        return nullptr;
    }
    std::string sym(name);
    if (void *p = shadowhook_dlsym(g_art_handle, sym.c_str()); p != nullptr) {
        return p;
    }
    return shadowhook_dlsym_symtab(g_art_handle, sym.c_str());
}

// Best-effort prefix search over libart.so's .dynsym (fallback used by LSPlant
// only for a couple of versioned symbols, e.g. GetMethodShorty).
void *ResolveArtSymbolPrefix(std::string_view prefix) {
    if (prefix.empty()) {
        return nullptr;
    }
    struct Context {
        uintptr_t base = 0;
        const ElfW(Sym) *symtab = nullptr;
        const char *strtab = nullptr;
        size_t symcount = 0;
    } ctx;

    auto cb = [](struct dl_phdr_info *info, size_t, void *data) -> int {
        if (info->dlpi_name == nullptr || strstr(info->dlpi_name, "libart.so") == nullptr) {
            return 0;
        }
        auto *c = static_cast<Context *>(data);
        c->base = info->dlpi_addr;
        const ElfW(Dyn) *dyn = nullptr;
        for (size_t i = 0; i < info->dlpi_phnum; ++i) {
            if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
                dyn = reinterpret_cast<const ElfW(Dyn) *>(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
                break;
            }
        }
        if (dyn == nullptr) {
            return 1;
        }
        const ElfW(Word) *hash = nullptr;
        for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; ++d) {
            switch (d->d_tag) {
                case DT_SYMTAB:
                    c->symtab = reinterpret_cast<const ElfW(Sym) *>(info->dlpi_addr + d->d_un.d_ptr);
                    break;
                case DT_STRTAB:
                    c->strtab = reinterpret_cast<const char *>(info->dlpi_addr + d->d_un.d_ptr);
                    break;
                case DT_HASH:
                    hash = reinterpret_cast<const ElfW(Word) *>(info->dlpi_addr + d->d_un.d_ptr);
                    break;
                default:
                    break;
            }
        }
        if (hash != nullptr) {
            c->symcount = hash[1];  // nchain
        }
        return 1;
    };
    dl_iterate_phdr(cb, &ctx);

    if (ctx.symtab == nullptr || ctx.strtab == nullptr || ctx.symcount == 0) {
        return nullptr;
    }
    for (size_t i = 0; i < ctx.symcount; ++i) {
        const ElfW(Sym) &sym = ctx.symtab[i];
        if (sym.st_name == 0 || sym.st_value == 0 || ELF64_ST_BIND(sym.st_info) == STB_LOCAL) {
            continue;
        }
        const char *name = ctx.strtab + sym.st_name;
        if (strncmp(name, prefix.data(), prefix.size()) == 0) {
            return reinterpret_cast<void *>(ctx.base + sym.st_value);
        }
    }
    return nullptr;
}

// ---------------------------------------------------------------------------
// invokeConstructor0 helper cache (primitive unboxing)
// ---------------------------------------------------------------------------

struct InvokeCtorCache {
    jclass integer_clazz = nullptr;
    jclass long_clazz = nullptr;
    jclass short_clazz = nullptr;
    jclass character_clazz = nullptr;
    jclass boolean_clazz = nullptr;
    jclass byte_clazz = nullptr;
    jclass float_clazz = nullptr;
    jclass double_clazz = nullptr;

    jclass int_type = nullptr;
    jclass long_type = nullptr;
    jclass short_type = nullptr;
    jclass char_type = nullptr;
    jclass boolean_type = nullptr;
    jclass byte_type = nullptr;
    jclass float_type = nullptr;
    jclass double_type = nullptr;

    jmethodID int_value = nullptr;
    jmethodID long_value = nullptr;
    jmethodID short_value = nullptr;
    jmethodID char_value = nullptr;
    jmethodID boolean_value = nullptr;
    jmethodID byte_value = nullptr;
    jmethodID float_value = nullptr;
    jmethodID double_value = nullptr;

    jmethodID get_parameter_types = nullptr;
};

InvokeCtorCache g_ctor_cache;

bool CacheWrapperClass(JNIEnv *env, const char *name, const char *value_name, const char *value_sig,
                       jclass *clazz_out, jclass *type_out, jmethodID *value_out) {
    jclass local = env->FindClass(name);
    if (local == nullptr) {
        return false;
    }
    jclass global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    jfieldID type_field = env->GetStaticFieldID(global, "TYPE", "Ljava/lang/Class;");
    if (type_field == nullptr) {
        return false;
    }
    jobject type_local = env->GetStaticObjectField(global, type_field);
    *type_out = static_cast<jclass>(env->NewGlobalRef(type_local));
    env->DeleteLocalRef(type_local);
    *value_out = env->GetMethodID(global, value_name, value_sig);
    *clazz_out = global;
    return *value_out != nullptr;
}

bool InitInvokeConstructorCache(JNIEnv *env) {
    bool ok = true;
    ok &= CacheWrapperClass(env, "java/lang/Integer", "intValue", "()I", &g_ctor_cache.integer_clazz,
                            &g_ctor_cache.int_type, &g_ctor_cache.int_value);
    ok &= CacheWrapperClass(env, "java/lang/Long", "longValue", "()J", &g_ctor_cache.long_clazz,
                            &g_ctor_cache.long_type, &g_ctor_cache.long_value);
    ok &= CacheWrapperClass(env, "java/lang/Short", "shortValue", "()S", &g_ctor_cache.short_clazz,
                            &g_ctor_cache.short_type, &g_ctor_cache.short_value);
    ok &= CacheWrapperClass(env, "java/lang/Character", "charValue", "()C", &g_ctor_cache.character_clazz,
                            &g_ctor_cache.char_type, &g_ctor_cache.char_value);
    ok &= CacheWrapperClass(env, "java/lang/Boolean", "booleanValue", "()Z", &g_ctor_cache.boolean_clazz,
                            &g_ctor_cache.boolean_type, &g_ctor_cache.boolean_value);
    ok &= CacheWrapperClass(env, "java/lang/Byte", "byteValue", "()B", &g_ctor_cache.byte_clazz,
                            &g_ctor_cache.byte_type, &g_ctor_cache.byte_value);
    ok &= CacheWrapperClass(env, "java/lang/Float", "floatValue", "()F", &g_ctor_cache.float_clazz,
                            &g_ctor_cache.float_type, &g_ctor_cache.float_value);
    ok &= CacheWrapperClass(env, "java/lang/Double", "doubleValue", "()D", &g_ctor_cache.double_clazz,
                            &g_ctor_cache.double_type, &g_ctor_cache.double_value);

    jclass executable = env->FindClass("java/lang/reflect/Executable");
    if (executable != nullptr) {
        g_ctor_cache.get_parameter_types =
            env->GetMethodID(executable, "getParameterTypes", "()[Ljava/lang/Class;");
        env->DeleteLocalRef(executable);
    }
    ok &= g_ctor_cache.get_parameter_types != nullptr;
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return ok;
}

bool UnboxArgs(JNIEnv *env, jobject constructor, jobjectArray args, jsize count, jvalue *out) {
    auto param_types = static_cast<jobjectArray>(
        env->CallObjectMethod(constructor, g_ctor_cache.get_parameter_types));
    if (env->ExceptionCheck() || param_types == nullptr) {
        return false;
    }
    if (env->GetArrayLength(param_types) != count) {
        env->DeleteLocalRef(param_types);
        return false;
    }
    for (jsize i = 0; i < count; ++i) {
        auto type = static_cast<jclass>(env->GetObjectArrayElement(param_types, i));
        jobject arg = env->GetObjectArrayElement(args, i);
        jvalue v{};
        v.l = arg;
        if (type != nullptr && arg != nullptr) {
            if (env->IsSameObject(type, g_ctor_cache.int_type)) {
                v.i = env->CallIntMethod(arg, g_ctor_cache.int_value);
            } else if (env->IsSameObject(type, g_ctor_cache.long_type)) {
                v.j = env->CallLongMethod(arg, g_ctor_cache.long_value);
            } else if (env->IsSameObject(type, g_ctor_cache.short_type)) {
                v.s = env->CallShortMethod(arg, g_ctor_cache.short_value);
            } else if (env->IsSameObject(type, g_ctor_cache.char_type)) {
                v.c = env->CallCharMethod(arg, g_ctor_cache.char_value);
            } else if (env->IsSameObject(type, g_ctor_cache.boolean_type)) {
                v.z = env->CallBooleanMethod(arg, g_ctor_cache.boolean_value);
            } else if (env->IsSameObject(type, g_ctor_cache.byte_type)) {
                v.b = env->CallByteMethod(arg, g_ctor_cache.byte_value);
            } else if (env->IsSameObject(type, g_ctor_cache.float_type)) {
                v.f = env->CallFloatMethod(arg, g_ctor_cache.float_value);
            } else if (env->IsSameObject(type, g_ctor_cache.double_type)) {
                v.d = env->CallDoubleMethod(arg, g_ctor_cache.double_value);
            }
        }
        out[i] = v;
        if (type != nullptr) {
            env->DeleteLocalRef(type);
        }
        if (arg != nullptr) {
            env->DeleteLocalRef(arg);
        }
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(param_types);
            return false;
        }
    }
    env->DeleteLocalRef(param_types);
    return true;
}

// ---------------------------------------------------------------------------
// Runtime init (shadowhook + LSPlant)
// ---------------------------------------------------------------------------

std::once_flag g_init_once;
bool g_initialized = false;

bool DisableHiddenApiRestrictionsInternal(JNIEnv *env);

void InitRuntime(JNIEnv *env) {
    int sdk = android_get_device_api_level();
    LOGI("Initializing native runtime for SDK %d", sdk);
    NativeLog("[AliuHook] InitRuntime: SDK %d", sdk);
    AppendDiag("InitRuntime: SDK %d", sdk);

    // Probe whether W+X anonymous mappings are permitted (SELinux execmem).
    {
        void *probe = mmap(nullptr, 4096, PROT_READ | PROT_WRITE | PROT_EXEC,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (probe == MAP_FAILED) {
            NativeLog("[AliuHook] mmap W+X denied: errno=%d (%s)", errno, strerror(errno));
            AppendDiag("mmap W+X denied: errno=%d (%s)", errno, strerror(errno));
        } else {
            NativeLog("[AliuHook] mmap W+X ok");
            AppendDiag("mmap W+X ok");
            munmap(probe, 4096);
        }
    }

    int r = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (r != 0) {
        LOGE("shadowhook init failed: %d - %s", r, shadowhook_to_errmsg(r));
        NativeLog("[AliuHook] shadowhook_init failed: %d - %s", r, shadowhook_to_errmsg(r));
        AppendDiag("shadowhook_init failed: %d - %s", r, shadowhook_to_errmsg(r));
        return;
    }
    NativeLog("[AliuHook] shadowhook_init ok");
    AppendDiag("shadowhook_init ok");

    g_art_handle = shadowhook_dlopen("libart.so");
    if (g_art_handle == nullptr) {
        LOGE("Failed to open libart.so");
        NativeLog("[AliuHook] failed to open libart.so");
        AppendDiag("failed to open libart.so");
        return;
    }
    NativeLog("[AliuHook] libart.so opened");
    AppendDiag("libart.so opened");

    // LSPlant's InitJNI resolves hidden APIs (e.g. Class.accessFlags), so the
    // hidden API exemptions must be applied BEFORE lsplant::Init.
    if (!DisableHiddenApiRestrictionsInternal(env)) {
        NativeLog("[AliuHook] disableHiddenApiRestrictions failed");
        AppendDiag("disableHiddenApiRestrictions failed");
    } else {
        AppendDiag("disableHiddenApiRestrictions ok");
    }

    lsplant::InitInfo init_info{
        .inline_hooker = InlineHooker,
        .inline_unhooker = InlineUnhooker,
        .art_symbol_resolver = ResolveArtSymbol,
        .art_symbol_prefix_resolver = ResolveArtSymbolPrefix,
    };
    if (!lsplant::Init(env, init_info)) {
        LOGE("lsplant::Init returned false");
        NativeLog("[AliuHook] lsplant::Init returned false");
        AppendDiag("lsplant::Init returned false");
        return;
    }
    LOGI("lsplant init finished");
    NativeLog("[AliuHook] lsplant init finished");
    AppendDiag("lsplant init finished");

    if (!InitInvokeConstructorCache(env)) {
        LOGE("invoke_constructor init failed");
        NativeLog("[AliuHook] invoke_constructor init failed");
        AppendDiag("invoke_constructor init failed");
        return;
    }
    g_initialized = true;
    NativeLog("[AliuHook] InitRuntime: g_initialized = true");
    AppendDiag("g_initialized = true");
}

bool EnsureInitialized(JNIEnv *env) {
    std::call_once(g_init_once, InitRuntime, env);
    return g_initialized;
}

// ---------------------------------------------------------------------------
// ProfileSaver disabler
// ---------------------------------------------------------------------------

void ProfileSaverNoop() {}

bool DisableProfileSaverInternal() {
    static std::mutex mu;
    static void *hooked = nullptr;
    std::lock_guard<std::mutex> lock(mu);
    if (hooked != nullptr) {
        LOGW("disableProfileSaver called multiple times - It is already disabled.");
        return true;
    }
    int sdk = android_get_device_api_level();
    void *target = ResolveArtSymbol("_ZN3art12ProfileSaver20ProcessProfilingInfoEbPtb");
    if (target == nullptr) {
        if (sdk >= 31) {
            target = ResolveArtSymbol("_ZN3art12ProfileSaver20ProcessProfilingInfoEbbPt");
        }
        if (target == nullptr) {
            target = ResolveArtSymbol("_ZN3art12ProfileSaver20ProcessProfilingInfoEbPt");
        }
    }
    if (target == nullptr) {
        LOGE("Failed to disable ProfileSaver: ProfileSaver::ProcessProfilingInfo not found");
        return false;
    }
    void *backup = nullptr;
    void *stub = shadowhook_hook_func_addr(target, reinterpret_cast<void *>(&ProfileSaverNoop), &backup);
    if (stub == nullptr) {
        int err = shadowhook_get_errno();
        LOGE("Failed to disable ProfileSaver: %d - %s", err, shadowhook_to_errmsg(err));
        return false;
    }
    hooked = stub;
    LOGI("Successfully disabled ProfileSaver");
    return true;
}

bool DisableHiddenApiRestrictionsInternal(JNIEnv *env) {
    if (android_get_device_api_level() < 29) {
        return true;
    }
    auto setter = reinterpret_cast<void (*)(JNIEnv *, jclass, jobjectArray)>(ResolveArtSymbol(
        "_ZN3artL32VMRuntime_setHiddenApiExemptionsEP7_JNIEnvP7_jclassP13_jobjectArray"));
    if (setter == nullptr) {
        LOGE("HiddenAPI: Didn't find setHiddenApiExemptions");
        AppendDiag("hidden api: setHiddenApiExemptions symbol not found");
        return false;
    }
    jclass string_clazz = env->FindClass("java/lang/String");
    if (string_clazz == nullptr) {
        AppendDiag("hidden api: String class not found");
        return false;
    }
    jstring exemption = env->NewStringUTF("L");
    jobjectArray exemptions = env->NewObjectArray(1, string_clazz, exemption);
    setter(env, string_clazz, exemptions);
    env->DeleteLocalRef(exemptions);
    env->DeleteLocalRef(exemption);
    env->DeleteLocalRef(string_clazz);
    return true;
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI bindings: de.robv.android.xposed.XposedBridge
// ---------------------------------------------------------------------------

extern "C" {

JNIEXPORT jobject JNICALL Java_de_robv_android_xposed_XposedBridge_hook0(
    JNIEnv *env, jclass, jobject callback, jobject member, jobject method) {
    if (!EnsureInitialized(env)) {
        NativeLog("[AliuHook] hook0: native runtime NOT initialized (g_initialized=false)");
        return nullptr;
    }
    jobject backup = lsplant::Hook(env, member, callback, method);
    if (backup == nullptr) {
        LOGE("lsplant::Hook returned null (hook failed) for member=%p", member);
        NativeLog("[AliuHook] hook0: lsplant::Hook returned null for member=%p", member);
    }
    return backup;
}

JNIEXPORT jstring JNICALL Java_de_robv_android_xposed_XposedBridge_getInitDiag(
    JNIEnv *env, jclass) {
    return env->NewStringUTF(g_init_diag.c_str());
}

JNIEXPORT jboolean JNICALL Java_de_robv_android_xposed_XposedBridge_unhook0(
    JNIEnv *env, jclass, jobject member) {
    if (!EnsureInitialized(env)) {
        return JNI_FALSE;
    }
    return lsplant::UnHook(env, member) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_de_robv_android_xposed_XposedBridge_deoptimize0(
    JNIEnv *env, jclass, jobject member) {
    if (!EnsureInitialized(env)) {
        return JNI_FALSE;
    }
    return lsplant::Deoptimize(env, member) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobject JNICALL Java_de_robv_android_xposed_XposedBridge_allocateInstance0(
    JNIEnv *env, jclass, jclass cls) {
    if (!EnsureInitialized(env)) {
        return nullptr;
    }
    return env->AllocObject(cls);
}

JNIEXPORT jboolean JNICALL Java_de_robv_android_xposed_XposedBridge_invokeConstructor0(
    JNIEnv *env, jclass, jobject instance, jobject constructor, jobjectArray args) {
    if (!EnsureInitialized(env)) {
        return JNI_FALSE;
    }
    jmethodID mid = env->FromReflectedMethod(constructor);
    if (mid == nullptr || env->ExceptionCheck()) {
        return JNI_FALSE;
    }
    if (args == nullptr) {
        env->CallVoidMethod(instance, mid);
        return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
    }
    jsize count = env->GetArrayLength(args);
    if (count < 0) {
        return JNI_FALSE;
    }
    auto *jargs = new (std::nothrow) jvalue[count == 0 ? 1 : count];
    if (jargs == nullptr) {
        return JNI_FALSE;
    }
    bool ok = UnboxArgs(env, constructor, args, count, jargs);
    if (ok) {
        env->CallVoidMethodA(instance, mid, jargs);
        ok = !env->ExceptionCheck();
    }
    delete[] jargs;
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_de_robv_android_xposed_XposedBridge_makeClassInheritable0(
    JNIEnv *env, jclass, jclass cls) {
    if (!EnsureInitialized(env)) {
        return JNI_FALSE;
    }
    return lsplant::MakeClassInheritable(env, cls) ? JNI_TRUE : JNI_FALSE;
}

// Forces the one-time native runtime initialization (shadowhook + LSPlant).
// Used by AliuHookInitProvider to warm the runtime before Application.onCreate.
JNIEXPORT void JNICALL Java_de_robv_android_xposed_XposedBridge_initRuntime(
    JNIEnv *env, jclass) {
    EnsureInitialized(env);
    LOGI("initRuntime: native runtime ready");
}

JNIEXPORT jboolean JNICALL Java_de_robv_android_xposed_XposedBridge_disableHiddenApiRestrictions(
    JNIEnv *env, jclass) {
    if (!EnsureInitialized(env)) {
        return JNI_FALSE;
    }
    return DisableHiddenApiRestrictionsInternal(env) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_de_robv_android_xposed_XposedBridge_disableProfileSaver(
    JNIEnv *env, jclass) {
    if (!EnsureInitialized(env)) {
        return JNI_FALSE;
    }
    return DisableProfileSaverInternal() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

// ---------------------------------------------------------------------------
// JNI binding: com.exteragram.messenger.plugins.utils.NativeCrashHandler
// Writes a flag file on fatal signals so the plugin subsystem can enter
// safe mode on the next launch, then re-raises through the previous handler.
// ---------------------------------------------------------------------------

namespace {

char g_crash_flag_path[PATH_MAX] = {};
struct sigaction g_old_handlers[64] = {};
constexpr int kHandledSignals[] = {SIGSEGV, SIGABRT, SIGFPE, SIGILL, SIGBUS, SIGTRAP};
volatile sig_atomic_t g_crash_handler_installed = 0;

void CrashSignalHandler(int signo) {
    if (g_crash_flag_path[0] != 0) {
        int fd = open(g_crash_flag_path, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (fd != -1) {
            ssize_t unused = write(fd, "1", 1);
            (void)unused;
            fsync(fd);
            close(fd);
        }
    }
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, signo);
    if (signo >= 0 && signo < 64 &&
        sigaction(signo, &g_old_handlers[signo], nullptr) == 0) {
        sigprocmask(SIG_UNBLOCK, &set, nullptr);
        raise(signo);
    }
    _exit(signo | 0x80);
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_exteragram_messenger_plugins_utils_NativeCrashHandler_init(
    JNIEnv *env, jclass, jstring flag_path) {
    if (g_crash_handler_installed != 0) {
        return;
    }
    if (flag_path == nullptr) {
        LOGW("Invalid native crash flag path.");
        return;
    }
    jsize len = env->GetStringUTFLength(flag_path);
    if (len <= 0 || static_cast<size_t>(len) >= sizeof(g_crash_flag_path)) {
        LOGW("Invalid native crash flag path.");
        return;
    }
    const char *chars = env->GetStringUTFChars(flag_path, nullptr);
    if (chars == nullptr) {
        LOGW("Failed to get native crash flag path.");
        return;
    }
    memcpy(g_crash_flag_path, chars, static_cast<size_t>(len));
    g_crash_flag_path[len] = 0;
    env->ReleaseStringUTFChars(flag_path, chars);

    struct sigaction sa = {};
    sa.sa_handler = CrashSignalHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_ONSTACK | SA_RESTART;

    for (int signo : kHandledSignals) {
        if (sigaction(signo, &sa, &g_old_handlers[signo]) == -1) {
            LOGW("Failed to set signal handler for signal %d", signo);
        }
    }
    g_crash_handler_installed = 1;
    LOGI("Native crash handler initialized.");
}

}  // extern "C"

// ---------------------------------------------------------------------------
// Library entry
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        LOGE("GetEnv failed in JNI_OnLoad");
        return JNI_ERR;
    }
    g_vm = vm;
    // Deliberately do NOT initialize the runtime here. JNI_OnLoad runs with an
    // "unknown" caller context, so LSPlant's InitJNI (which resolves hidden APIs
    // like Class.accessFlags) would be rejected. Initialization is deferred to
    // XposedBridge.ensureInitialized() -> initRuntime(), which runs from a Java
    // caller (XposedBridge) that the "L" hidden-API exemption covers.
    return JNI_VERSION_1_6;
}
