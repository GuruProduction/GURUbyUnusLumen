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
