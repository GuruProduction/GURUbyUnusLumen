/*
 * GURU by Unus Lumen - an AI framework that gives a language model real hands
 * on an Android phone.
 * Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd,
 * Bristol UK.
 * Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE
 * file.
 *
 * ============================================================================
 * PATHSHIM - makes upstream-prefixed binaries work without root and without
 * adb.
 * ============================================================================
 *
 * THE PROBLEM.
 *
 * Every binary built for Termux has `/data/data/com.termux/files/usr` compiled
 * into it. A font directory, a share directory, a config path, a plugin path.
 * That string is not text in the file, it is a constant in the machine code, so
 * it cannot be rewritten in place. On this device that path does not exist and
 * cannot be created, because it belongs to another application's sandbox.
 *
 * `figlet` is the smallest example: its font lookup is
 * `/data/data/com.termux/files/usr/share/figlet`, printed by `figlet -I3`. The
 * fonts are present in the real prefix and the binary cannot see them.
 *
 * WHAT WAS TRIED FIRST, AND WHY IT IS NOT THE ANSWER.
 *
 *   1. A root symlink /data/data/com.termux -> our prefix. Requires root.
 *      Nobody roots their phone, so this is not a fix.
 *   2. proot, the ptrace chroot Termux uses on unrooted devices. Requires
 *      ptrace, and Android's seccomp filter blocks ptrace for ordinary apps.
 *      Measured here: proot cannot execve even /system/bin/sh. It works on a
 *      debug harness and never on a user's phone.
 *
 * THE ANSWER.
 *
 * Intercept the path at the libc boundary. Every file the process opens goes
 * through open/openat/stat/access/execve. This shim rewrites a leading
 * `/data/data/com.termux/files` to the real prefix and hands the result to the
 * real libc function. The kernel never sees the upstream path, so no path
 * outside our sandbox is ever needed.
 *
 * This works for every package at once, needs no root, no adb and no ptrace,
 * and the precedent is already in this codebase: termux-exec's LD_PRELOAD is
 * loaded and functional in this exact environment, which is what makes `sh`,
 * `apt-key` and the git helpers run at all.
 *
 * HOW IT KNOWS THE REAL PREFIX.
 *
 * Read from the environment at first use:
 *   GURU_SHIM_FROM   the upstream prefix to rewrite (default the com.termux one)
 *   GURU_SHIM_TO     the real prefix to substitute
 *
 * Both are set by the app when it builds the environment, so the shim contains
 * no hardcoded package id and the same binary works in debug and release.
 *
 * SAFETY.
 *
 * It rewrites a PREFIX, never a substring and never a path that merely contains
 * the upstream string. `/data/data/com.termux/files` matches.
 * `/data/data/com.termux/filesx` does not, because the next character must be
 * '/' or the end. A path that does not start with the prefix is passed through
 * untouched, so ordinary Android paths, the system linker and the app's own
 * storage are all unaffected.
 */

#define _GNU_SOURCE

#include <dlfcn.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "guru"
#define LOGI(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* Buffer big enough for any rewritten path. PATH_MAX on Android is 4096. */
#define PATHBUF (PATH_MAX + 256)

/*
 * The prefix pair. Initialised once, on first call, from the environment.
 *
 * Deliberately lazy and thread-safe-by-construction: a wrong or missing
 * environment means the shim is INERT (from == NULL), which is the safe
 * failure. A shim that guessed a prefix could redirect the wrong files;
 * a shim that does nothing just leaves the old behaviour.
 */
static char g_from[PATHBUF];
static char g_to[PATHBUF];
static size_t g_from_len = 0;
static int g_ready = 0;

static void shim_init(void) {
    if (g_ready) return;
    g_ready = 1;

    const char *from = getenv("GURU_SHIM_FROM");
    const char *to = getenv("GURU_SHIM_TO");
    if (!from || !to || !*from || !*to) {
        LOGI("pathshim: inert (GURU_SHIM_FROM/TO not set)");
        return;
    }
    size_t fl = strlen(from);
    size_t tl = strlen(to);
    if (fl >= sizeof(g_from) || tl >= sizeof(g_to)) {
        LOGI("pathshim: inert (prefix too long)");
        return;
    }
    /* Strip a trailing slash from both so matching is exact and predictable. */
    while (fl > 0 && from[fl - 1] == '/') fl--;
    while (tl > 0 && to[tl - 1] == '/') tl--;

    memcpy(g_from, from, fl);
    g_from[fl] = '\0';
    memcpy(g_to, to, tl);
    g_to[tl] = '\0';
    g_from_len = fl;

    /*
     * A rewrite from a prefix to itself would be a no-op loop. If the prefixes
     * are equal the shim stays inert, because there is nothing to translate and
     * rewriting would be wasted work on every call.
     */
    if (strcmp(g_from, g_to) == 0) {
        g_from_len = 0;
        LOGI("pathshim: inert (from == to)");
        return;
    }
    LOGI("pathshim: active %s -> %s", g_from, g_to);
}

/*
 * Rewrite one path into `out` when it begins with the upstream prefix.
 * Returns `out` when it rewrote, or `path` itself when it did not.
 *
 * The boundary rule is the whole safety story: after the prefix the next
 * character must be '/' or NUL. Without that check, "/data/data/com.termux/
 * filesystem-notes" would be mangled.
 */
static const char *shim_path(const char *path, char *out, size_t outsz) {
    if (!path) return path;
    shim_init();
    if (g_from_len == 0) return path;

    if (strncmp(path, g_from, g_from_len) != 0) return path;
    char next = path[g_from_len];
    if (next != '\0' && next != '/') return path;

    const char *rest = path + g_from_len; /* starts with '/' or is empty */
    int n = snprintf(out, outsz, "%s%s", g_to, rest);
    if (n < 0 || (size_t)n >= outsz) return path; /* too long: leave as-is */
    return out;
}

/* Convenience wrapper for the very common two-argument shape. */
#define SHIM(path, buf) shim_path((path), (buf), sizeof(buf))

/* ------------------------------------------------------------------ */
/* Intercepted entry points                                            */
/* ------------------------------------------------------------------ */

typedef int (*open_fn)(const char *, int, ...);
int open(const char *path, int flags, ...) {
    static open_fn real = NULL;
    if (!real) real = (open_fn)dlsym(RTLD_NEXT, "open");
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATHBUF];
    return real(SHIM(path, buf), flags, mode);
}

typedef int (*openat_fn)(int, const char *, int, ...);
int openat(int dirfd, const char *path, int flags, ...) {
    static openat_fn real = NULL;
    if (!real) real = (openat_fn)dlsym(RTLD_NEXT, "openat");
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    char buf[PATHBUF];
    /*
     * Only absolute paths are rewritten. A relative path is resolved against
     * dirfd by the kernel and has no prefix to match.
     */
    const char *p = (path && path[0] == '/') ? SHIM(path, buf) : path;
    return real(dirfd, p, flags, mode);
}

typedef int (*stat_fn)(const char *, struct stat *);
int stat(const char *path, struct stat *st) {
    static stat_fn real = NULL;
    if (!real) real = (stat_fn)dlsym(RTLD_NEXT, "stat");
    char buf[PATHBUF];
    return real(SHIM(path, buf), st);
}

typedef int (*lstat_fn)(const char *, struct stat *);
int lstat(const char *path, struct stat *st) {
    static lstat_fn real = NULL;
    if (!real) real = (lstat_fn)dlsym(RTLD_NEXT, "lstat");
    char buf[PATHBUF];
    return real(SHIM(path, buf), st);
}

typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    static fstatat_fn real = NULL;
    if (!real) real = (fstatat_fn)dlsym(RTLD_NEXT, "fstatat");
    char buf[PATHBUF];
    const char *p = (path && path[0] == '/') ? SHIM(path, buf) : path;
    return real(dirfd, p, st, flags);
}

typedef int (*access_fn)(const char *, int);
int access(const char *path, int mode) {
    static access_fn real = NULL;
    if (!real) real = (access_fn)dlsym(RTLD_NEXT, "access");
    char buf[PATHBUF];
    return real(SHIM(path, buf), mode);
}

typedef int (*faccessat_fn)(int, const char *, int, int);
int faccessat(int dirfd, const char *path, int mode, int flags) {
    static faccessat_fn real = NULL;
    if (!real) real = (faccessat_fn)dlsym(RTLD_NEXT, "faccessat");
    char buf[PATHBUF];
    const char *p = (path && path[0] == '/') ? SHIM(path, buf) : path;
    return real(dirfd, p, mode, flags);
}

typedef char *(*realpath_fn)(const char *, char *);
char *realpath(const char *path, char *resolved) {
    static realpath_fn real = NULL;
    if (!real) real = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
    char buf[PATHBUF];
    return real(SHIM(path, buf), resolved);
}

typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
ssize_t readlink(const char *path, char *out, size_t outsz) {
    static readlink_fn real = NULL;
    if (!real) real = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    char buf[PATHBUF];
    return real(SHIM(path, buf), out, outsz);
}

/*
 * execve is here for completeness: a program the payload spawns by upstream
 * path is a real case (apt-key's helpers, git's remote helpers). The shim
 * rewrites it and the kernel gets a path that exists.
 */
typedef int (*execve_fn)(const char *, char *const[], char *const[]);
int execve(const char *path, char *const argv[], char *const envp[]) {
    static execve_fn real = NULL;
    if (!real) real = (execve_fn)dlsym(RTLD_NEXT, "execve");
    char buf[PATHBUF];
    return real(SHIM(path, buf), argv, envp);
}

typedef int (*execv_fn)(const char *, char *const[]);
int execv(const char *path, char *const argv[]) {
    static execv_fn real = NULL;
    if (!real) real = (execv_fn)dlsym(RTLD_NEXT, "execv");
    char buf[PATHBUF];
    return real(SHIM(path, buf), argv);
}

/*
 * execvp and execvpe search PATH themselves, inside libc.
 *
 * That matters here: the search happens before any path is handed to a function
 * this shim intercepts, so an absolute upstream path passed to execvp is resolved
 * by bionic's own open/access calls and never reaches the execve hook. Defining
 * these and delegating to it keeps the rewrite in one place rather than
 * reimplementing a PATH walk.
 *
 * A bare name ("apt-key") still goes through the PATH search, which is correct:
 * PATH in this app's environment already points at the real prefix's bin.
 */
typedef int (*execvp_fn)(const char *, char *const[]);
int execvp(const char *file, char *const argv[]) {
    static execvp_fn real = NULL;
    if (!real) real = (execvp_fn)dlsym(RTLD_NEXT, "execvp");
    char buf[PATHBUF];
    return real(SHIM(file, buf), argv);
}

typedef int (*execvpe_fn)(const char *, char *const[], char *const[]);
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    static execvpe_fn real = NULL;
    if (!real) real = (execvpe_fn)dlsym(RTLD_NEXT, "execvpe");
    char buf[PATHBUF];
    return real(SHIM(file, buf), argv, envp);
}

/*
 * DIRECTORY READING — the hooks apt actually needs (issue 193).
 *
 * The hooks above cover open/openat/stat/lstat/fstatat/access/realpath/readlink
 * and execve. That set made `dash`, `grep`, `sed`, `ls` and `apt --version` run,
 * and it was still not enough for a package operation:
 *
 *   E: Unable to read /data/data/com.termux/files/usr/etc/apt/apt.conf.d/
 *      - opendir (2: No such file or directory)
 *
 * apt enumerates its config tree with opendir/readdir, not with open. An
 * unrewritten opendir therefore fails on a path that does not exist, and apt
 * refuses to run at all: "Unable to determine a suitable packaging system type".
 * Every hook in the list above was irrelevant to that failure because the call
 * that failed was never intercepted.
 *
 * fopen matters for the same reason one level down. A great deal of code opens
 * a FILE* rather than a descriptor, and bionic implements fopen as
 * __sfvwrite/__srefill against its OWN open, so a hook on open() alone does not
 * always see it. Intercepting fopen closes that gap without double-rewriting:
 * if the path is rewritten here it no longer starts with the upstream prefix by
 * the time bionic's open is called, and the open hook passes it through.
 *
 * scandir and its 64-bit alias are the same class again. They are separate
 * symbols, not wrappers around opendir, so a program that calls scandir directly
 * bypasses an opendir hook.
 *
 * WHAT IS STILL NOT INTERCEPTED, AND WHY.
 *
 * mkdir, rename, unlink and their relatives stay untouched. Reads and executes
 * were the broken behaviours; rewriting writes would let a stray call create
 * structure inside our prefix that nothing expects. dpkg handles its own paths
 * through the compatibility root, which is a separate mechanism built for that
 * purpose.
 */

typedef DIR *(*opendir_fn)(const char *);
DIR *opendir(const char *path) {
    static opendir_fn real = NULL;
    if (!real) real = (opendir_fn)dlsym(RTLD_NEXT, "opendir");
    char buf[PATHBUF];
    return real(SHIM(path, buf));
}

/*
 * fopen and its 64-bit alias.
 *
 * The types come from <stdio.h>, not from a guess. bionic declares
 * `FILE *fopen(const char *, const char *)`, and a hook returning void* is a
 * conflicting declaration that the compiler rejects outright — which is how the
 * first attempt at this file failed to build.
 *
 * On 64-bit Android fopen and fopen64 are distinct symbols even though FILE is
 * the same type, so both are defined. A program linked against fopen64 must not
 * fall through to a hook that only knows the plain name.
 */
typedef FILE *(*fopen_fn)(const char *, const char *);
FILE *fopen(const char *path, const char *mode) {
    static fopen_fn real = NULL;
    if (!real) real = (fopen_fn)dlsym(RTLD_NEXT, "fopen");
    char buf[PATHBUF];
    return real(SHIM(path, buf), mode);
}

FILE *fopen64(const char *path, const char *mode) {
    static fopen_fn real = NULL;
    if (!real) real = (fopen_fn)dlsym(RTLD_NEXT, "fopen64");
    char buf[PATHBUF];
    return real(SHIM(path, buf), mode);
}

/*
 * scandir, in both the plain and the 64-bit form.
 *
 * Again the real signature from <dirent.h>: the selector receives a
 * `const struct dirent *` and the comparator receives `const struct dirent **`.
 * Spelling those as void* is a conflicting declaration, not a lenient one.
 *
 * scandir does not wrap opendir: it is a separate libc symbol with its own
 * directory walk, so a program that calls it directly bypasses an opendir hook
 * entirely. That is why it is here rather than being assumed covered.
 */
typedef int (*scandir_fn)(const char *, struct dirent ***,
                          int (*)(const struct dirent *),
                          int (*)(const struct dirent **, const struct dirent **));
int scandir(const char *path, struct dirent ***namelist,
            int (*filter)(const struct dirent *),
            int (*compar)(const struct dirent **, const struct dirent **)) {
    static scandir_fn real = NULL;
    if (!real) real = (scandir_fn)dlsym(RTLD_NEXT, "scandir");
    char buf[PATHBUF];
    return real(SHIM(path, buf), namelist, filter, compar);
}

int scandir64(const char *path, struct dirent64 ***namelist,
              int (*filter)(const struct dirent64 *),
              int (*compar)(const struct dirent64 **, const struct dirent64 **)) {
    static scandir_fn real = NULL;
    if (!real) real = (scandir_fn)dlsym(RTLD_NEXT, "scandir64");
    char buf[PATHBUF];
    return real(SHIM(path, buf), (struct dirent ***)namelist,
                 (int (*)(const struct dirent *))filter,
                 (int (*)(const struct dirent **, const struct dirent **))compar);
}
