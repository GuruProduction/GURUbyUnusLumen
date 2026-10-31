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
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
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
 * mkdir is deliberately NOT intercepted.
 *
 * Package installs write through dpkg, whose paths are handled by the
 * compatibility root and instdir. Directories a program creates at an upstream
 * path are rare, and rewriting mkdir would let a stray write create structure
 * inside our prefix that nothing expects. Reading and executing are the
 * behaviours that were broken; writing new trees is not.
 */
