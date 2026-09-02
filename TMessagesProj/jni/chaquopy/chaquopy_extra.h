#ifndef CHAQUOPY_EXTRA_H
#define CHAQUOPY_EXTRA_H
/* Compile-time config for the generated chaquopy.c. */
#include <jni.h>
/* The NDK's JNIEnv is const-qualified in C++, but AttachCurrentThread takes a non-const
 * env out-param; use a distinct typedef for that call site. */
typedef JNIEnv Attach_JNIEnv;
#endif
