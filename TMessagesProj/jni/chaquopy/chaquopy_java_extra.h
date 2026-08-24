#ifndef CHAQUOPY_JAVA_EXTRA_H
#define CHAQUOPY_JAVA_EXTRA_H
#include <Python.h>
/* Declared here (rather than by Cython) so init_module can call it before the module's own
 * translation unit finishes registering it. */
PyMODINIT_FUNC PyInit_chaquopy_java(void);
#endif
