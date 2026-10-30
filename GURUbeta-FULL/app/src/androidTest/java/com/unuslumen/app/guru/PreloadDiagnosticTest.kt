// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Diagnostic: what does the termux-exec preload actually do inside the app process?
 *
 * This exists because a shell-level test cannot answer that question: adb runs in
 * a different SELinux domain where Android's W^X rule does not apply, so the
 * preload's whole purpose is invisible there. Everything below runs as the app.
 *
 * It prints, rather than asserts, because its job is to produce evidence. The
 * assertions live in GitRuntimeDomainTest.
 */
@RunWith(AndroidJUnit4::class)
class PreloadDiagnosticTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun run(cmd: List<String>, env: Map<String, String>): String {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(30, TimeUnit.SECONDS)
            "exit=${p.exitValue()}\n$out"
        } catch (e: Exception) {
            "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Test
    fun reportPreloadBehaviourInAppDomain() {
        val files = context.filesDir.absolutePath
        val root = "$files/termux"
        val prefix = "$root/usr"
        val preload = "$prefix/lib/libtermux-exec-ld-preload.so"
        val domain = runCatching {
            File("/proc/self/attr/current").readText().trim().trim('\u0000')
        }.getOrDefault("unknown")

        println("=== DOMAIN ===")
        println(domain)
        println("=== PRELOAD PRESENT ===")
        println(File(preload).let { "${it.exists()} len=${it.length()}" })

        val base = mapOf(
            "HOME" to "$root/git-home",
            "PATH" to "$prefix/libexec/git-core:$prefix/bin:/system/bin",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "GIT_EXEC_PATH" to "$prefix/libexec/git-core",
        )

        val full = base + mapOf(
            "LD_PRELOAD" to preload,
            "TERMUX__PREFIX" to prefix,
            "TERMUX__ROOTFS" to root,
            "TERMUX_APP__DATA_DIR" to files,
            "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
            "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "1",
            "ANDROID__BUILD_VERSION_SDK" to android.os.Build.VERSION.SDK_INT.toString(),
            "TERMUX__SE_PROCESS_CONTEXT" to domain,
            "TERMUX_EXEC__LOG_LEVEL" to "debug",
        )

        println("=== A: exec a helper DIRECTLY, no preload (expect EACCES = W^X) ===")
        println(run(listOf("$prefix/libexec/git-core/git-remote-http"), base))

        println("=== B: same, WITH preload and all inputs (preload should route via linker) ===")
        println(run(listOf("$prefix/libexec/git-core/git-remote-http"), full))

        println("=== C: same, preload WITHOUT the inputs (isolates which inputs matter) ===")
        println(run(listOf("$prefix/libexec/git-core/git-remote-http"), base + mapOf("LD_PRELOAD" to preload)))

        println("=== D: linker64 explicitly, no preload (the manual route) ===")
        println(run(listOf("/system/bin/linker64", "$prefix/libexec/git-core/git-remote-http"), base))

        println("=== E: git --version with full env ===")
        println(run(listOf("$prefix/bin/git", "--version"), full))

        println("=== F: git ls-remote with full env (the real target) ===")
        println(
            run(
                listOf("$prefix/bin/git", "ls-remote", "https://github.com/octocat/Hello-World.git"),
                full + mapOf("GIT_CONFIG_COUNT" to "2",
                    "GIT_CONFIG_KEY_0" to "http.proxy", "GIT_CONFIG_VALUE_0" to "socks5h://127.0.0.1:9050",
                    "GIT_CONFIG_KEY_1" to "https.proxy", "GIT_CONFIG_VALUE_1" to "socks5h://127.0.0.1:9050"),
            )
        )

        println("=== G: linker64 + preload + all vars, git ls-remote (THE decisive test) ===")
        println(
            run(
                listOf("/system/bin/linker64", "$prefix/bin/git", "ls-remote",
                    "https://github.com/octocat/Hello-World.git"),
                full + mapOf(
                    "GIT_CONFIG_COUNT" to "2",
                    "GIT_CONFIG_KEY_0" to "http.proxy", "GIT_CONFIG_VALUE_0" to "socks5h://127.0.0.1:9050",
                    "GIT_CONFIG_KEY_1" to "https.proxy", "GIT_CONFIG_VALUE_1" to "socks5h://127.0.0.1:9050",
                    "GIT_TRACE" to "1",
                ),
            )
        )

        println("=== H: linker64 WITHOUT preload, git ls-remote (control) ===")
        println(
            run(
                listOf("/system/bin/linker64", "$prefix/bin/git", "ls-remote",
                    "https://github.com/octocat/Hello-World.git"),
                base + mapOf(
                    "GIT_CONFIG_COUNT" to "2",
                    "GIT_CONFIG_KEY_0" to "http.proxy", "GIT_CONFIG_VALUE_0" to "socks5h://127.0.0.1:9050",
                    "GIT_CONFIG_KEY_1" to "https.proxy", "GIT_CONFIG_VALUE_1" to "socks5h://127.0.0.1:9050",
                ),
            )
        )
    }
}
