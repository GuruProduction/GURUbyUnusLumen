cmake_minimum_required(VERSION 3.22.1)
project("guru")

# 16 KB PAGE ALIGNMENT, APPLIED BY ONE FUNCTION (issue 13).
#
# WHY THIS IS A FUNCTION RATHER THAN FOUR COPIES OF THE SAME TWO FLAGS. The flags
# were already written out twice, on pathshim and python_launcher, and the targets
# that did NOT get them are exactly the ones the compatibility warning named:
# libquickjs.so and libquickjs_bridge.so were linked at 4 KB. Copying the block a
# third and fourth time is how the fifth target gets forgotten.
#
# WHAT IT DOES. The NDK defaults a shared library's LOAD segments to 4096-byte
# alignment. Android on 16 KB-page hardware refuses to load a library aligned
# below the page size, and reports the app as not 16 KB compatible. Both flags are
# needed: max-page-size sets the alignment the linker emits, and common-page-size
# keeps the result loadable on 4 KB devices too, so one APK serves both.
#
# MEASURED, NOT ASSUMED. Before this change, 38 of the 66 native libraries in the
# release APK carried 0x1000 LOAD alignment, including these two. The prebuilt
# third-party libraries are a separate problem: they have to be rebuilt upstream,
# and issue 13 tracks that half.
function(guru_align_16kb target)
    target_link_options(${target} PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endfunction()

# Root shell daemon - runs commands as root via Unix socket
add_executable(rootshell
    rootshell.c
)

# Kernel exploit runner - attempts privilege escalation
add_executable(exploit_runner
    exploit_runner.c
)

target_link_libraries(rootshell log)
target_link_libraries(exploit_runner log)

set_target_properties(rootshell PROPERTIES LINKER_LANGUAGE C)
set_target_properties(exploit_runner PROPERTIES LINKER_LANGUAGE C)

# An executable carries LOAD segments exactly like a shared object, so the same
# alignment rule applies. These two are not packaged into the APK today, which is
# why nobody noticed, but a target built here should be correct by construction.
guru_align_16kb(rootshell)
guru_align_16kb(exploit_runner)

# QuickJS JavaScript engine - shared library for JNI loading
add_library(quickjs SHARED
    quickjs/quickjs.c
    quickjs/libregexp.c
    quickjs/libunicode.c
    quickjs/cutils.c
    quickjs/libbf.c
)

target_include_directories(quickjs PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/quickjs
)

target_compile_definitions(quickjs PRIVATE
    _GNU_SOURCE
    CONFIG_BIGNUM
    CONFIG_VERSION="2024-01-13"
)

target_compile_options(quickjs PRIVATE
    -fwrapv
)

target_link_libraries(quickjs log m)

set_target_properties(quickjs PROPERTIES LINKER_LANGUAGE C)

# Issue 13: this target was 0x1000 aligned and Android named it by name.
guru_align_16kb(quickjs)

# QuickJS JNI bridge - shared library that links against quickjs
add_library(quickjs_bridge SHARED
    quickjs_bridge.c
)

target_include_directories(quickjs_bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/quickjs
)

target_link_libraries(quickjs_bridge
    quickjs
    log
    android
)

set_target_properties(quickjs_bridge PROPERTIES LINKER_LANGUAGE C)

# Issue 13: named by Android as one of the misaligned libraries.
guru_align_16kb(quickjs_bridge)

# pathshim - LD_PRELOAD library that rewrites the upstream com.termux prefix at
# the libc boundary, so binaries with a compiled-in path work without root and
# without adb. See pathshim.c for the full reasoning.
add_library(pathshim SHARED
    pathshim.c
)

target_link_libraries(pathshim log)

set_target_properties(pathshim PROPERTIES LINKER_LANGUAGE C)

# 16 KB page-size compatibility, set explicitly on the LINK.
#
# The NDK defaults shared libraries to 4 KB segment alignment. This device runs
# 16 KB pages, and Android's linker refuses to load a library whose alignment is
# smaller than the page size:
#
#   "libpathshim.so" program alignment (4096) cannot be smaller than system
#   page size (16384)  ->  CANNOT LINK EXECUTABLE .../bash
#
# Because the shim is in LD_PRELOAD, that refusal took the whole preload chain
# down and broke `sh`, `apt-key` and every helper — a library meant to fix path
# handling instead broke the shell. Both flags are needed: the linker needs the
# larger max page size, and the common page size keeps the shared library usable
# on 4 KB devices as well, so one APK serves both.
guru_align_16kb(pathshim)

# python_launcher — the executable that starts the bundled CPython.
#
# WHY THIS IS AN EXECUTABLE AND NOT A SHARED LIBRARY (issue 175)
#
# libpython3.14.so is a shared object: `llvm-readelf -h` reports entry point 0x0
# and `llvm-readelf -l` finds no PT_INTERP. execve() on it maps the segments and
# jumps to address zero, which the kernel reports as SIGILL — exit 132, no output,
# no message. PythonProvider has always looked for libpython_launcher.so in
# nativeLibraryDir, and this file was never given a target, so it was never built
# and every Python call short-circuited at "not found".
#
# HOW IT ENDS UP IN nativeLibraryDir WITH A .so NAME
#
# Android will not execute from the app's writable data directory, so
# nativeLibraryDir is the only place a packaged binary can run. The packager only
# extracts `lib/<abi>/lib*.so` from the APK, so a runnable ELF has to wear that
# name. Every one of the seventeen bundled tools (toybox, jq, curl, git, ...) is
# shipped exactly this way, and the toybox entry in the device diagnostic is proof
# it works: it executed from nativeLibraryDir and got as far as dispatching on its
# own argv[0].
#
# The name is decided by OUTPUT_NAME plus PREFIX/SUFFIX rather than by renaming a
# file after the build, because a rename in a Gradle doLast task is invisible to
# the packaging step: the packager runs off the CMake target's declared output, so
# a file renamed afterwards simply never reaches the APK. Setting the properties
# on the target is what makes Gradle copy it at all.
#
# PIE is explicit. An add_executable in CMake 3.22 targeting Android already
# defaults to a PIE, but the whole point of this target is that the ELF must be
# executable, so the property is stated rather than inherited. Without ENABLE_PIE
# the linker emits ET_EXEC, which the packager's strip step and the 16 KB
# alignment requirement both handle differently.
#
# The 16 KB link options are repeated here deliberately. A PIE executable carries
# LOAD segments exactly like a shared object, and Android 15+ devices with 16 KB
# pages require every loadable segment to be aligned to that page size. The
# bundled prebuilts predate the rule and happen to carry 4 KB alignment, which
# still loads today, but anything this project compiles now should be correct by
# construction rather than by luck.
add_executable(python_launcher
    python_launcher.c
)

set_target_properties(python_launcher PROPERTIES
    OUTPUT_NAME "python_launcher"
    PREFIX "lib"
    SUFFIX ".so"
    POSITION_INDEPENDENT_CODE ON
    LINKER_LANGUAGE C
)

# libdl for dlopen/dlsym/dlerror. There is no need to link libpython itself: the
# launcher resolves it at run time, so the launcher keeps working whichever of
# libpython3.14.so or libpython3.so it finds.
target_link_libraries(python_launcher dl)

guru_align_16kb(python_launcher)

# No post-build copy is needed, and the reason is worth recording because the
# first attempt here added one and it caused a duplicate-file failure.
#
# AGP packages an add_executable target into the APK when its OUTPUT NAME looks
# like a shared library. `rootshell` and `exploit_runner` are dropped because
# they are named `rootshell` and `exploit_runner`; this target is named
# `libpython_launcher.so` by the properties above, so AGP merges it into
# lib/<abi>/ exactly like a shared library and it reaches nativeLibraryDir.
#
# The evidence is the failure that proved it: with an extra jniLibs source
# directory also carrying the file, mergeDebugNativeLibs reported
# "2 files found with path 'lib/x86_64/libpython_launcher.so'", one from
# intermediates/cxx/.../obj/x86_64/ (AGP's own copy of the CMake target) and one
# from the hand-declared directory. The naming is the whole mechanism.
