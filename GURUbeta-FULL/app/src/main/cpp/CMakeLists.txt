cmake_minimum_required(VERSION 3.22.1)
project("guru")

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
target_link_options(pathshim PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
