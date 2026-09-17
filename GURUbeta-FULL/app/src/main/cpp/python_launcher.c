/*
 * python_launcher.c — Thin C launcher for the bundled CPython embeddable package.
 *
 * The official python.org Android embeddable packages ship libpython3.X.so
 * and the full standard library, but no standalone python3 binary. This
 * launcher fills that gap: it loads libpython via dlopen, resolves Py_Main,
 * and executes a Python script.
 *
 * Built via CMake alongside rootshell and exploit_runner.
 */

#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char *argv[]) {
    /*
     * The Python shared library is extracted alongside this launcher.
     * The app sets LD_LIBRARY_PATH and PYTHONHOME before calling us,
     * so we just dlopen with the soname and let the linker resolve it.
     */
    void *handle = dlopen("libpython3.14.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        handle = dlopen("libpython3.so", RTLD_NOW | RTLD_GLOBAL);
    }
    if (!handle) {
        fprintf(stderr, "python_launcher: failed to load libpython: %s\n", dlerror());
        return 1;
    }

    /*
     * Py_Main is the official public entry point for embedding CPython.
     * Signature: int Py_Main(int argc, wchar_t **argv) on 3.5-3.12,
     *            int Py_Main(int argc, char **argv) on 3.13+
     * Android embeddable is 3.14+, so we use char**.
     */
    typedef int (*PyMainFunc)(int, char **);
    PyMainFunc Py_Main = (PyMainFunc)dlsym(handle, "Py_Main");
    if (!Py_Main) {
        fprintf(stderr, "python_launcher: failed to resolve Py_Main: %s\n", dlerror());
        dlclose(handle);
        return 1;
    }

    int result = Py_Main(argc, argv);

    dlclose(handle);
    return result;
}
