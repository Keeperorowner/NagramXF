cmake_minimum_required(VERSION 3.16)

set(CMAKE_CXX_FLAGS "-std=c++14 -DANDROID -g")
set(CMAKE_C_FLAGS "-w -std=c11 -DANDROID -D_LARGEFILE_SOURCE=1 -g -Wno-error=implicit-function-declaration")
set(CMAKE_ASM_FLAGS "${CFLAGS} -x assembler-with-cpp")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -flto=full")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden -flto=full")
#These link flags apply to the main ${NATIVE_LIB} only (see target_link_options below);
#keeping them out of global CMAKE_SHARED_LINKER_FLAGS lets other shared targets
#(e.g. aliuhook) link without requiring the tmessages static archives.
set(TMESSAGES_EXCLUDE_LIBS "libtgvoip.a,libtgcalls.a,libtgcalls_tp.a,libtgnet.a,libsqlite.a,"
        "${CMAKE_HOME_DIRECTORY}/prebuild/${ANDROID_ABI}/libtlottie.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libswscale.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libavformat.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libavcodec.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libavutil.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libswresample.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libvpx.a,"
        "${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libdav1d.a,"
        "${CMAKE_HOME_DIRECTORY}/boringssl/lib/${ANDROID_ABI}/libssl.a,"
        "${CMAKE_HOME_DIRECTORY}/boringssl/lib/${ANDROID_ABI}/libcrypto.a,"
        "${CMAKE_HOME_DIRECTORY}/tde2e/${ANDROID_ABI}/libtde2e.a,"
        "${CMAKE_HOME_DIRECTORY}/tde2e/${ANDROID_ABI}/libtdutils.a")
string(CONCAT TMESSAGES_EXCLUDE_LIBS ${TMESSAGES_EXCLUDE_LIBS})
set(TMESSAGES_LINKER_FLAGS
        -flto=full
        -Wl,--gc-sections
        -Wl,--icf=safe
        -Wl,-Bsymbolic
        "-Wl,--exclude-libs,${TMESSAGES_EXCLUDE_LIBS}")

if (${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    enable_language(ASM)
else()
    enable_language(ASM_NASM)
endif()

set(CAN_COMPILE_SME FALSE CACHE INTERNAL "Disable libyuv SME" FORCE)
add_subdirectory(
        third_party/libyuv
        ${CMAKE_CURRENT_BINARY_DIR}/libyuv
        EXCLUDE_FROM_ALL)

add_library(avutil STATIC IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libavutil.a)

add_library(avformat STATIC IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libavformat.a)

add_library(avcodec STATIC IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libavcodec.a)

add_library(swresample STATIC IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libswresample.a)

add_library(swscale STATIC IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libswscale.a)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/boringssl/lib/${ANDROID_ABI}/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/boringssl/lib/${ANDROID_ABI}/libssl.a)

add_library(libvpx STATIC IMPORTED)
set_target_properties(libvpx PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libvpx.a)

add_library(libdav1d STATIC IMPORTED)
set_target_properties(libdav1d PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libdav1d.a)

add_library(libopus STATIC IMPORTED)
set_target_properties(libopus PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/ffmpeg/${ANDROID_ABI}/libopus.a)

add_library(tde2e STATIC IMPORTED)
set_target_properties(tde2e PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/tde2e/${ANDROID_ABI}/libtde2e.a)

add_library(tdutils STATIC IMPORTED)
set_target_properties(tdutils PROPERTIES IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/tde2e/${ANDROID_ABI}/libtdutils.a)

#tgnet
add_library(tgnet STATIC
        tgnet/ApiScheme.cpp
        tgnet/BuffersStorage.cpp
        tgnet/ByteArray.cpp
        tgnet/ByteStream.cpp
        tgnet/Connection.cpp
        tgnet/ConnectionSession.cpp
        tgnet/ConnectionsManager.cpp
        tgnet/ConnectionSocket.cpp
        tgnet/Datacenter.cpp
        tgnet/EventObject.cpp
        tgnet/FileLog.cpp
        tgnet/MTProtoScheme.cpp
        tgnet/NativeByteBuffer.cpp
        tgnet/Request.cpp
        tgnet/Timer.cpp
        tgnet/TLObject.cpp
        tgnet/ProxyCheckInfo.cpp
        tgnet/Handshake.cpp
        tgnet/Config.cpp)
target_compile_options(tgnet PUBLIC
        -Wall -frtti -finline-functions -ffast-math -Oz)
set_target_properties(tgnet PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(tgnet PUBLIC
        HAVE_PTHREAD)
target_include_directories(tgnet PUBLIC
        boringssl/include/)
target_link_libraries(tgnet
        crypto)

# tlottie (prebuilt Rust static library)
add_library(tlottie STATIC IMPORTED)
set_target_properties(tlottie PROPERTIES
        IMPORTED_LOCATION ${CMAKE_HOME_DIRECTORY}/prebuild/${ANDROID_ABI}/libtlottie.a)

#sqlite
add_library(sqlite STATIC
        sqlite/sqlite3.c)
target_compile_options(sqlite PUBLIC
        -fno-strict-aliasing -Oz)
set_target_properties(sqlite PROPERTIES
        ANDROID_ARM_MODE arm)
target_compile_definitions(sqlite PUBLIC
        NULL=0 SOCKLEN_T=socklen_t LOCALE_NOT_USED ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE HAVE_STRCHRNUL=0 ANDROID_ARMV6_IDCT)

#breakpad
add_library(breakpad STATIC
        third_party/breakpad/src/client/linux/crash_generation/crash_generation_client.cc
        third_party/breakpad/src/client/linux/handler/exception_handler.cc
        third_party/breakpad/src/client/linux/handler/minidump_descriptor.cc
        third_party/breakpad/src/client/linux/log/log.cc
        third_party/breakpad/src/client/linux/dump_writer_common/thread_info.cc
        third_party/breakpad/src/client/linux/dump_writer_common/seccomp_unwinder.cc
        third_party/breakpad/src/client/linux/dump_writer_common/ucontext_reader.cc
        third_party/breakpad/src/client/linux/microdump_writer/microdump_writer.cc
        third_party/breakpad/src/client/linux/minidump_writer/linux_dumper.cc
        third_party/breakpad/src/client/linux/minidump_writer/linux_ptrace_dumper.cc
        third_party/breakpad/src/client/linux/minidump_writer/minidump_writer.cc
        third_party/breakpad/src/client/minidump_file_writer.cc
        third_party/breakpad/src/common/android/breakpad_getcontext.S
        third_party/breakpad/src/common/convert_UTF.c
        third_party/breakpad/src/common/md5.cc
        third_party/breakpad/src/common/string_conversion.cc
        third_party/breakpad/src/common/linux/elfutils.cc
        third_party/breakpad/src/common/linux/file_id.cc
        third_party/breakpad/src/common/linux/guid_creator.cc
        third_party/breakpad/src/common/linux/linux_libc_support.cc
        third_party/breakpad/src/common/linux/memory_mapped_file.cc
        third_party/breakpad/src/common/linux/safe_readlink.cc)
set_target_properties(breakpad PROPERTIES ANDROID_ARM_MODE arm)
set_property(SOURCE third_party/breakpad/src/common/android/breakpad_getcontext.S PROPERTY LANGUAGE C)
target_include_directories(breakpad PUBLIC
        third_party/breakpad/src/common/android/include
        third_party/breakpad/src)


#voip
include(${CMAKE_HOME_DIRECTORY}/voip/CMakeLists.txt)

set(NATIVE_LIB "tmessages.49")

#tmessages
add_library(${NATIVE_LIB} SHARED
        jni.c
        audio.c
        webm_encoder.c
        image.cpp
        intro/IntroRenderer.c
        SqliteWrapper.cpp
        utilities.cpp
        gifvideo.cpp
        lottie.cpp
        TgNetWrapper.cpp
        exoplayer/ffmpeg_jni.cc
        fast-edge.cpp
        genann.c
        secureid_ocr.cpp
        tde2e/bridge.cpp
)

target_compile_options(${NATIVE_LIB} PUBLIC
        -ffast-math -Oz -funroll-loops -ffast-math -fno-strict-aliasing -fno-math-errno)

target_compile_definitions(${NATIVE_LIB} PUBLIC
        BSD=1 NULL=0 SOCKLEN_T=socklen_t ANDROID_NDK DISABLE_IMPORTGL AVOID_TABLES ANDROID_TILE_BASED_DECODE __STDC_CONSTANT_MACROS ANDROID_ARMV6_IDCT OPUS_BUILD FIXED_POINT USE_ALLOCA restrict= __EMX__ LOCALE_NOT_USED HAVE_LRINT HAVE_LRINTF)

target_sources(${NATIVE_LIB} PRIVATE

        third_party/xiph/opusfile/src/info.c
        third_party/xiph/opusfile/src/internal.c
        third_party/xiph/opusfile/src/opusfile.c
        third_party/xiph/opusfile/src/stream.c
        third_party/xiph/ogg/src/bitwise.c
        third_party/xiph/ogg/src/framing.c)

target_include_directories(${NATIVE_LIB} PUBLIC
        third_party/xiph/opus/include
        third_party/xiph/opus/silk
        third_party/xiph/opus/silk/fixed
        third_party/xiph/opus/celt
        third_party/xiph/opus
        third_party/xiph/opusfile/include
        third_party/xiph/ogg/include
        third_party/libyuv/include
        boringssl/include
        ffmpeg/include
        emoji
        exoplayer/include
        intro
        tlottie/include
        tgcalls/
        webrtc/
        tde2e/include
)

target_link_options(${NATIVE_LIB} PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(${NATIVE_LIB} PRIVATE "-Wl,-z,common-page-size=16384")
target_link_options(${NATIVE_LIB} PRIVATE ${TMESSAGES_LINKER_FLAGS})
target_link_options(${NATIVE_LIB} PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map")
target_link_libraries(${NATIVE_LIB}
        -Wl,--whole-archive rnnoise openh264 voipandroid -Wl,--no-whole-archive
        tgvoip
        tgcalls
        tgcalls_tp
        tgnet
        tlottie
        sqlite
        swscale
        avformat
        avcodec
        swresample
        libvpx
        libdav1d
        libopus
        tde2e
        tdutils
        yuv
        avutil
        ssl
        crypto
        jnigraphics
        log
        z
        GLESv2
        EGL
        android
        OpenSLES
        cpufeatures
        breakpad)

#aliuhook: ART hook runtime for plugins (LSPlant + shadowhook), arm/arm64 only.
if (NOT DEFINED ENABLE_PLUGINS)
    set(ENABLE_PLUGINS ON)
endif ()
if (ENABLE_PLUGINS AND (${ANDROID_ABI} STREQUAL "arm64-v8a" OR ${ANDROID_ABI} STREQUAL "armeabi-v7a"))
    set(LSPLANT_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
    set(DEX_BUILDER_BUILD_SHARED OFF CACHE INTERNAL "" FORCE)
    add_subdirectory(third_party/lsplant/lsplant/src/main/jni)

    if (${ANDROID_ABI} STREQUAL "arm64-v8a")
        set(SHADOWHOOK_ARCH arm64)
    else ()
        set(SHADOWHOOK_ARCH arm)
    endif ()

    set(SHADOWHOOK_DIR ${CMAKE_HOME_DIRECTORY}/third_party/shadowhook/shadowhook/src/main/cpp)
    file(GLOB SHADOWHOOK_SOURCES
            ${SHADOWHOOK_DIR}/*.c
            ${SHADOWHOOK_DIR}/arch/${SHADOWHOOK_ARCH}/*.c
            ${SHADOWHOOK_DIR}/arch/${SHADOWHOOK_ARCH}/*.S
            ${SHADOWHOOK_DIR}/common/*.c
            ${SHADOWHOOK_DIR}/third_party/xdl/*.c)
    #sh_jni.c carries shadowhook's own JNI_OnLoad/Java bindings - we have our own.
    list(FILTER SHADOWHOOK_SOURCES EXCLUDE REGEX "sh_jni\\.c$")

    add_library(shadowhook STATIC ${SHADOWHOOK_SOURCES})
    target_include_directories(shadowhook PRIVATE
            ${SHADOWHOOK_DIR}
            ${SHADOWHOOK_DIR}/include
            ${SHADOWHOOK_DIR}/arch/${SHADOWHOOK_ARCH}
            ${SHADOWHOOK_DIR}/common
            ${SHADOWHOOK_DIR}/third_party/xdl
            ${SHADOWHOOK_DIR}/third_party/bsd
            ${SHADOWHOOK_DIR}/third_party/lss)

    add_library(aliuhook SHARED
            aliuhook/aliuhook.cpp)
    set_target_properties(aliuhook PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
    target_include_directories(aliuhook PRIVATE
            ${CMAKE_HOME_DIRECTORY}/third_party/lsplant/lsplant/src/main/jni/include
            ${SHADOWHOOK_DIR}/include)
    target_compile_options(aliuhook PRIVATE -fvisibility=hidden)
    target_link_options(aliuhook PRIVATE "-Wl,-z,max-page-size=16384")
    #sources are compiled with the global -flto=full, so the link needs it too
    target_link_options(aliuhook PRIVATE -flto=full -Wl,--gc-sections -Wl,--icf=safe)
    target_link_libraries(aliuhook
            lsplant_static
            shadowhook
            log
            z
            dl)
endif ()

#if (${ANDROID_ABI} STREQUAL "x86" OR ${ANDROID_ABI} STREQUAL "x86_64")
#    target_link_libraries(${NATIVE_LIB}
#            -Wl,--whole-archive vpxasm -Wl,--no-whole-archive
#            c)
#endif()

#AndroidNdkModules.cmake was removed from current NDKs; build cpufeatures directly instead.
include_directories(${ANDROID_NDK}/sources/android/cpufeatures)
add_library(cpufeatures ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c)
