// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
//
// python_launcher.c — the process entry point for the bundled CPython.
//
// WHY THIS FILE EXISTS, AND WHY IT WAS DEAD FOR SO LONG
//
// CPython's official Android artifact is libpython3.14.so: a shared object with
// a null entry point and no PT_INTERP segment. It can be loaded, never executed.
// execve() on it maps the segments and jumps to address zero, which the kernel
// reports as SIGILL (exit 132) with no diagnostic at all. That is the whole
// reason "python does not work in the app".
//
// A separate executable is required to start it. PythonProvider.kt has always
// looked for libpython_launcher.so in nativeLibraryDir and has always failed to
// find one, because this file was never given a CMake target. See
// CMakeLists.txt: the target exists now.
//
// HOW IT IS PACKAGED, AND WHY IT IS NAMED lib*.so
//
// Android 10 and later refuse execve() on anything in the app's writable data
// directory. nativeLibraryDir is the one place an app can execute from, and the
// packager only extracts files there that are named lib*.so under lib/<abi>/ in
// the APK. So this is built as a real PIE executable and named like a shared
// object. The name is an Android packaging convention, not a statement about the
// ELF type, and every one of the seventeen bundled tools (toybox, jq, curl,
// git ...) is shipped exactly the same way. `llvm-readelf -h` on any of them
// shows Type DYN with a real entry point and a PT_INTERP naming
// /system/bin/linker64, which is what makes them executable; it is the missing
// entry point and PT_INTERP on libpython3.14.so that makes that file unexecutable.
//
// RESOLVING libpython, WITHOUT TRUSTING LD_LIBRARY_PATH
//
// The obvious dlopen("libpython3.14.so") depends on LD_LIBRARY_PATH containing
// nativeLibraryDir. It does, but that is one more thing which can be wrong for a
// reason three layers away from the symptom. /proc/self/exe is the launcher's own
// path and libpython sits beside it, so the absolute path is derived and dlopen
// receives a path that cannot be misresolved. The bare soname is kept as a
// fallback for the case where the absolute path does not resolve.
//
// EXIT CODE AND TEARDOWN
//
// Py_Main's return value is handed straight back through main(). There is no
// dlclose: unloading the interpreter while its atexit handlers and extension
// modules are still registered is how a clean exit becomes a segfault. The
// process exits and the kernel reclaims everything, which is the correct
// teardown for a one-shot launcher.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* Py_BytesMain, NOT Py_Main. This distinction cost hours and is the whole
 * reason this file exists in its present shape.
 *
 * Py_Main still exists in 3.14 and still links, so calling it looks correct and
 * compiles cleanly. On Android it starts the interpreter and then dies:
 *
 *   Fatal Python error: memory allocation failed
 *   Python runtime state: core initialized
 *
 * Py_Main was deprecated in 3.13 and is no longer the supported entry point.
 * Py_BytesMain is, it is what CPython's own Android packaging uses (the Termux
 * python3.14 stub's only Python import is Py_BytesMain), and it starts the same
 * library on the same device with the same environment and works.
 *
 * Measured on an API 37 arm64 emulator, 16 KB pages, against CPython 3.14.8:
 *   Py_Main       -> "memory allocation failed", exit 1, no output
 *   Py_BytesMain  -> 3.14.8 (main, ...), prints the version
 *
 * Both symbols are exported by the shipped libpython, so this is a real choice
 * rather than a workaround for a missing symbol. */
typedef int (*PyBytesMainFunc)(int, char **);

/* The launcher's own directory, or a zero-length string if it cannot be read.
 *
 * readlink does not NUL-terminate, so the byte count is trusted rather than the
 * terminator. A truncated result is rejected instead of used, because half a
 * path resolves to nothing and reports a confusing dlopen error. */
static void own_directory(char *out, size_t out_size) {
    char exe[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (len <= 0 || (size_t)len >= sizeof(exe) - 1) {
        out[0] = '\0';
        return;
    }
    exe[len] = '\0';

    char *slash = strrchr(exe, '/');
    if (slash == NULL) {
        out[0] = '\0';
        return;
    }
    *slash = '\0';

    if (strlen(exe) >= out_size) {
        out[0] = '\0';
        return;
    }
    strcpy(out, exe);
}

/* Load libpython, trying the absolute path beside this binary first.
 *
 * RTLD_NOW because a lazily bound interpreter fails at an arbitrary later point
 * inside user code, where the error cannot be read. Failing at load time names
 * the missing symbol instead. */
static void *load_python(const char *dir) {
    static const char *const names[] = {"libpython3.14.so", "libpython3.so"};
    const size_t count = sizeof(names) / sizeof(names[0]);

    if (dir[0] != '\0') {
        for (size_t i = 0; i < count; i++) {
            char candidate[PATH_MAX];
            int written = snprintf(candidate, sizeof(candidate), "%s/%s", dir, names[i]);
            if (written <= 0 || (size_t)written >= sizeof(candidate)) {
                continue;
            }
            void *handle = dlopen(candidate, RTLD_NOW | RTLD_GLOBAL);
            if (handle != NULL) {
                return handle;
            }
        }
    }

    /* Fallback: let the linker search its own paths, which includes
     * nativeLibraryDir when LD_LIBRARY_PATH carries it. */
    for (size_t i = 0; i < count; i++) {
        void *handle = dlopen(names[i], RTLD_NOW | RTLD_GLOBAL);
        if (handle != NULL) {
            return handle;
        }
    }
    return NULL;
}

int main(int argc, char *argv[]) {
    char dir[PATH_MAX];
    own_directory(dir, sizeof(dir));

    void *handle = load_python(dir);
    if (handle == NULL) {
        /* dlerror() is the only thing that says which library was missing or
         * which symbol failed to resolve. Dropping it turns a precise failure
         * into "python does not work", which is the class of report that needed
         * a whole diagnostic battery to unpick. */
        const char *why = dlerror();
        fprintf(stderr, "python_launcher: cannot load libpython (looked in %s): %s\n",
                dir[0] != '\0' ? dir : "<unknown>", why != NULL ? why : "no detail");
        return 127;
    }

    /* Py_BytesMain first, Py_Main only as a fallback. An interpreter older than
     * 3.13 has no Py_BytesMain, and a future one may drop Py_Main, so trying
     * both keeps this launcher working across the range rather than pinning it
     * to one API generation. */
    PyBytesMainFunc entry = (PyBytesMainFunc)dlsym(handle, "Py_BytesMain");
    if (entry == NULL) {
        entry = (PyBytesMainFunc)dlsym(handle, "Py_Main");
    }
    if (entry == NULL) {
        const char *why = dlerror();
        fprintf(stderr, "python_launcher: libpython exports neither Py_BytesMain nor Py_Main: %s\n",
                why != NULL ? why : "no detail");
        /* Deliberately no dlclose: returning is enough, and unloading a
         * half-initialised interpreter is the risk this file exists to avoid. */
        return 126;
    }

    /* argv is passed through untouched, so argv[0] stays the launcher's real
     * path. sys.executable then names a file that exists and can be re-executed
     * to start another interpreter, which is what code expecting a python
     * binary relies on. */
    return entry(argc, argv);
}
