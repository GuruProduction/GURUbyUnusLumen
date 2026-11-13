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
/* __NR_access and syscall(), for the preinit fallback described below. */
#include <sys/syscall.h>
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

/* ==================================================================== */
/* THE NULL-DEREFERENCE FIX, AND WHY IT IS SHAPED THIS WAY (issue 328)  */
/* ==================================================================== */
/*
 * WHAT WAS BROKEN. Every hook below did:
 *
 *     static access_fn real = NULL;
 *     if (!real) real = (access_fn)dlsym(RTLD_NEXT, "access");
 *     return real(SHIM(path, buf), mode);          // <-- no check
 *
 * bionic calls access() on ITSELF during __libc_preinit, before the linker has
 * finished its own work. dlsym needs the linker, so at that moment it answers
 * NULL, and the very next line calls NULL. The process dies with SIGSEGV before
 * main() ever runs, which is why EVERY process this library was preloaded into
 * segfaulted, including /system/bin/echo:
 *
 *     #05 libpathshim.so (access+92)
 *     #06 bionic libc (__libc_safe_arc4random_buf)
 *     #07 bionic libc (__libc_init_setjmp_cookie)
 *     #08 bionic libc (__libc_init_globals)
 *     #09 bionic libc (__libc_preinit_impl)
 *     #10 linker64 (call_constructors)
 *
 * That tombstone is the whole diagnosis: the crash is inside libc's own startup,
 * one frame below our hook, and the address it died on is NULL.
 *
 * THE FIX IS IN TWO PARTS, AND BOTH ARE NEEDED.
 *
 *   1. A resolver that never hands back NULL to a caller. When dlsym cannot
 *      answer, the shim reports the call as UNSUPPORTED and the hook passes the
 *      ORIGINAL, UNREWRITTEN path to the real libc behaviour it can still reach.
 *      Passing through is always safe: an unrewritten path simply behaves the
 *      way it did before this shim existed, which is the failure the shim was
 *      built to improve rather than one it created.
 *
 *   2. A fallback for the startup window specifically. During preinit the only
 *      thing bionic needs from access() is an answer about a path that is never
 *      ours, so the hook uses the kernel directly via syscall(SYS_access) rather
 *      than pretending it has the libc symbol. That keeps the shim transparent
 *      for exactly as long as the linker is not ready, and it is the difference
 *      between "inert" and "kills the host process".
 *
 * WHY NOT JUST DELETE THE access HOOK. access is how dpkg and apt test for a
 * binary before running it, and it is one of the six paths that made the prefix
 * work at all. Removing it would fix the crash by removing the feature.
 */

/*
 * Resolve the real symbol behind a hook, exactly once.
 *
 * Returns 1 and stores the pointer when libc answered, or 0 when it did not.
 * A zero is NOT cached as a permanent answer for the startup window: dlsym
 * legitimately returns NULL during preinit and works a moment later, so a
 * resolver that cached that NULL would leave the shim permanently inert on
 * every process. Only a successful lookup is cached.
 */
static int shim_resolve(void **slot, const char *name) {
    if (*slot) return 1;
    void *found = dlsym(RTLD_NEXT, name);
    if (!found) return 0;
    *slot = found;
    return 1;
}

/*
 * The kernel fallback, for when the linker cannot answer yet.
 *
 * SYS_access is stable across every Android ABI this app ships, and the raw
 * syscall needs none of the linker state that dlsym does. Using it here is not a
 * second implementation of access: it is the same operation, reached by the one
 * route that is open during preinit.
 */
/*
 * access(2)'s syscall number, spelled per architecture.
 *
 * BIONIC DOES NOT PUBLISH __NR_access TO USERSPACE. The first attempt used it and the
 * compiler was blunt: "use of undeclared identifier '__NR_access'". The numbers are part
 * of the kernel ABI rather than of libc, so they are stable and they are written here,
 * with the reason, rather than being fetched from a header that does not carry them.
 *
 * The alternative — inline assembly per ABI — was tried and rejected by the compiler for
 * armv7, which is right: r7 is the frame pointer under Thumb and a syscall number cannot
 * be placed there. syscall() takes the number as an argument and handles the register
 * conventions itself, which is the whole reason it is preferred here.
 */
#if defined(__aarch64__)
#  define SHIM_NR_access 48
#elif defined(__arm__)
#  define SHIM_NR_access 33
#elif defined(__x86_64__)
#  define SHIM_NR_access 21
#elif defined(__i386__)
#  define SHIM_NR_access 33
#else
#  error "no access(2) syscall number for this architecture"
#endif

static int shim_raw_access(const char *path, int mode) {
    /*
     * THE PORTABLE FORM, NOT HAND-WRITTEN ASSEMBLY.
     *
     * The first attempt wrote the syscall instruction inline per architecture, and the
     * armv7 build refused it outright:
     *
     *   error: write to reserved register 'R7'
     *
     * which is correct of the compiler: r7 is the frame pointer under the Thumb ABI this
     * target uses, so a syscall number cannot live there. Writing it as assembly also means
     * getting three calling conventions right by hand for a single call, which is three
     * chances to be wrong for no gain.
     *
     * `syscall()` from <unistd.h> is the same operation, already correct on every ABI the
     * NDK targets, and bionic implements it without needing the linker to have finished.
     * That last property is the entire reason this function exists.
     */
    return (int)syscall(SHIM_NR_access, path, mode);
}

/*
 * One sentence for a hook that could not resolve its real symbol and therefore
 * declined to rewrite. Logged once per symbol so a diagnosis is one line rather
 * than one line per file open.
 */
static void shim_report_unresolved(const char *name) {
    LOGI("pathshim: %s unresolved, passing paths through unrewritten", name);
}

/*
 * What a hook does when the real symbol cannot be resolved.
 *
 * EVERY ONE OF THESE IS A RETURN, NOT A CRASH, and that is the entire point of
 * issue 328. Before this file had them, an unresolved symbol was called anyway.
 *
 * THE SHAPE IS CHOSEN PER FUNCTION so each one tells the truth about what it did:
 *
 *  - access() has a working kernel route, so it USES it and answers correctly even
 *    during preinit. Returning ENOSYS here would break bionic's own startup, which
 *    is the one caller guaranteed to run before the linker is ready.
 *  - The rest report ENOSYS or NULL, because a path that cannot be given to libc
 *    cannot be rewritten, and inventing a success is worse than an honest failure.
 *
 * A caller that receives ENOSYS learns the shim could not help. A caller that
 * receives a fabricated success would act on a file that was never opened.
 */
static int shim_unresolved_open(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_openat(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_stat(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_lstat(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_fstatat(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_faccessat(void) { errno = ENOSYS; return -1; }
static char *shim_unresolved_realpath(void) { errno = ENOSYS; return NULL; }
static ssize_t shim_unresolved_readlink(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_execve(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_execv(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_execvp(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_execvpe(void) { errno = ENOSYS; return -1; }
static DIR *shim_unresolved_opendir(void) { errno = ENOSYS; return NULL; }
static FILE *shim_unresolved_fopen(void) { errno = ENOSYS; return NULL; }
static FILE *shim_unresolved_fopen64(void) { errno = ENOSYS; return NULL; }
static int shim_unresolved_scandir(void) { errno = ENOSYS; return -1; }
static int shim_unresolved_scandir64(void) { errno = ENOSYS; return -1; }

/*
 * access() IS THE ONE THAT MUST NOT FAIL, because it is the one bionic calls
 * during its own preinit. The kernel route needs no linker state, so the answer
 * is still correct, and the shim stays transparent for exactly the window in
 * which it used to kill the process.
 */
/*
 * NOTE there is no shim_unresolved_access() here.
 *
 * access() cannot delegate its failure to a no-argument stub, because a refusal
 * and a truth are different answers and bionic's preinit needs the truth: it asks
 * whether a path exists, and gets a wrong answer if the shim says no on its
 * behalf. So the hook itself takes the syscall route with the real path, which is
 * where the fix for issue 328 actually lives. See access() below.
 */

/* ------------------------------------------------------------------ */
/* Intercepted entry points                                            */
/* ------------------------------------------------------------------ */

typedef int (*open_fn)(const char *, int, ...);
int open(const char *path, int flags, ...) {
    static open_fn real = NULL;
    if (!shim_resolve((void **)&real, "open")) {
        static int reported = 0;
        if (!reported) { reported = 1; shim_report_unresolved("open"); }
        /* No way to reach libc's open: report the operation as unsupported rather than
         * dereferencing NULL. EAGAIN is wrong, ENOSYS is the truth. */
        errno = ENOSYS;
        return -1;
    }
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
    if (!shim_resolve((void **)&real, "openat")) return shim_unresolved_openat();
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
    if (!shim_resolve((void **)&real, "stat")) return shim_unresolved_stat();
    char buf[PATHBUF];
    return real(SHIM(path, buf), st);
}

typedef int (*lstat_fn)(const char *, struct stat *);
int lstat(const char *path, struct stat *st) {
    static lstat_fn real = NULL;
    if (!shim_resolve((void **)&real, "lstat")) return shim_unresolved_lstat();
    char buf[PATHBUF];
    return real(SHIM(path, buf), st);
}

typedef int (*fstatat_fn)(int, const char *, struct stat *, int);
int fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    static fstatat_fn real = NULL;
    if (!shim_resolve((void **)&real, "fstatat")) return shim_unresolved_fstatat();
    char buf[PATHBUF];
    const char *p = (path && path[0] == '/') ? SHIM(path, buf) : path;
    return real(dirfd, p, st, flags);
}

/*
 * access, THE HOOK ISSUE 328 IS ABOUT.
 *
 * bionic calls access() during __libc_preinit, before the linker can answer a
 * dlsym. The old code called the NULL that dlsym returned and every preloaded
 * process died with SIGSEGV before main(). The tombstone named this frame exactly:
 *
 *     #05  libpathshim.so (access+92)
 *     #06  bionic libc (__libc_safe_arc4random_buf)
 *     #07  bionic libc (__libc_init_setjmp_cookie)
 *     #08  bionic libc (__libc_init_globals)
 *     #09  bionic libc (__libc_preinit_impl)
 *
 * SO THIS ONE DOES NOT DELEGATE ITS FAILURE, IT USES THE KERNEL. When dlsym cannot
 * answer, the raw syscall still can, and it answers the same question with the
 * same path. During preinit the path is never one of ours, so no rewrite is
 * needed and the correct answer comes back; later, when the linker is ready, the
 * libc symbol is used as before.
 */
typedef int (*access_fn)(const char *, int);
int access(const char *path, int mode) {
    static access_fn real = NULL;
    if (!shim_resolve((void **)&real, "access")) {
        static int reported = 0;
        if (!reported) { reported = 1; shim_report_unresolved("access"); }
        /* The kernel route: same operation, same path, no linker state needed. */
        char buf[PATHBUF];
        return shim_raw_access(SHIM(path, buf), mode);
    }
    char buf[PATHBUF];
    return real(SHIM(path, buf), mode);
}

typedef int (*faccessat_fn)(int, const char *, int, int);
int faccessat(int dirfd, const char *path, int mode, int flags) {
    static faccessat_fn real = NULL;
    if (!shim_resolve((void **)&real, "faccessat")) return shim_unresolved_faccessat();
    char buf[PATHBUF];
    const char *p = (path && path[0] == '/') ? SHIM(path, buf) : path;
    return real(dirfd, p, mode, flags);
}

typedef char *(*realpath_fn)(const char *, char *);
char *realpath(const char *path, char *resolved) {
    static realpath_fn real = NULL;
    if (!shim_resolve((void **)&real, "realpath")) return shim_unresolved_realpath();
    char buf[PATHBUF];
    return real(SHIM(path, buf), resolved);
}

typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
ssize_t readlink(const char *path, char *out, size_t outsz) {
    static readlink_fn real = NULL;
    if (!shim_resolve((void **)&real, "readlink")) return shim_unresolved_readlink();
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
    if (!shim_resolve((void **)&real, "execve")) return shim_unresolved_execve();
    char buf[PATHBUF];
    return real(SHIM(path, buf), argv, envp);
}

typedef int (*execv_fn)(const char *, char *const[]);
int execv(const char *path, char *const argv[]) {
    static execv_fn real = NULL;
    if (!shim_resolve((void **)&real, "execv")) return shim_unresolved_execv();
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
    if (!shim_resolve((void **)&real, "execvp")) return shim_unresolved_execvp();
    char buf[PATHBUF];
    return real(SHIM(file, buf), argv);
}

typedef int (*execvpe_fn)(const char *, char *const[], char *const[]);
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    static execvpe_fn real = NULL;
    if (!shim_resolve((void **)&real, "execvpe")) return shim_unresolved_execvpe();
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
    if (!shim_resolve((void **)&real, "opendir")) return shim_unresolved_opendir();
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
    if (!shim_resolve((void **)&real, "fopen")) return shim_unresolved_fopen();
    char buf[PATHBUF];
    return real(SHIM(path, buf), mode);
}

FILE *fopen64(const char *path, const char *mode) {
    static fopen_fn real = NULL;
    if (!shim_resolve((void **)&real, "fopen64")) return shim_unresolved_fopen64();
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
    if (!shim_resolve((void **)&real, "scandir")) return shim_unresolved_scandir();
    char buf[PATHBUF];
    return real(SHIM(path, buf), namelist, filter, compar);
}

int scandir64(const char *path, struct dirent64 ***namelist,
              int (*filter)(const struct dirent64 *),
              int (*compar)(const struct dirent64 **, const struct dirent64 **)) {
    static scandir_fn real = NULL;
    if (!shim_resolve((void **)&real, "scandir64")) return shim_unresolved_scandir64();
    char buf[PATHBUF];
    return real(SHIM(path, buf), (struct dirent ***)namelist,
                 (int (*)(const struct dirent *))filter,
                 (int (*)(const struct dirent **, const struct dirent **))compar);
}
