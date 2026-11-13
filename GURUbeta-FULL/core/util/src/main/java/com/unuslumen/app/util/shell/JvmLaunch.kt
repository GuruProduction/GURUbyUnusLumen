// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * How the bundled JVM is launched inside this prefix.
 *
 * WHY THIS IS A SEPARATE, ANDROID-FREE, OBJECT (issue 292).
 *
 * The bundled JDK is real: `libjvm.so` is 20 MB, `libjli.so` is 59 KB, and the 6160-byte
 * `bin/java` is a legitimate PIE launcher rather than a stub. What was missing was one entry in
 * `LD_LIBRARY_PATH`.
 *
 * `libjli.so` is the launcher's first dependency and it lives in the JVM's OWN lib directory,
 * which is three levels below the prefix lib. The exec environment only added the prefix lib, so
 * the loader could not find it and the process died before `libjvm.so` was ever reached. Adding
 * the JVM directory fixes it completely — measured on a handset, where `java -version` then
 * printed `openjdk version "21.0.13"` and `javac` compiled a class.
 *
 * WHY THE ORDER IS WHAT IT IS. `lib/server` holds the server VM, which is a separate shared object
 * from the client VM and is the one a JDK package actually ships. It comes before `lib` because a
 * name that exists in both must resolve to the server build. The prefix lib comes last so the
 * JVM's own copies of a library win over an older copy the bootstrap happens to carry, which is
 * the same version-skew fault that made aapt fail in issue 291.
 *
 * This is a pure object over paths so the ordering rule can be proved on the JVM rather than only
 * by launching a 20 MB library on a phone.
 */
object JvmLaunch {

    /** Where the JVM lands, relative to the prefix. Must match the packaged archive. */
    const val JVM_HOME_RELATIVE = "lib/jvm/java-21-openjdk"

    /**
     * The library directories the loader needs, in search order.
     *
     * Returns an empty list when the JVM is not installed, so a prefix without it does not get
     * three dead paths prepended to its loader search. A dead entry in `LD_LIBRARY_PATH` is not
     * harmless in theory — it costs a failed stat per lookup — and it is also a lie about the
     * device, which is what this codebase keeps being bitten by.
     *
     * @param prefixPath the absolute path of the prefix's `usr` directory.
     * @param exists tells the function whether a path is really there, injected so the rule can be
     *   tested with no filesystem.
     */
    fun libraryPath(prefixPath: String, exists: (String) -> Boolean = { java.io.File(it).exists() }): List<String> {
        val prefix = prefixPath.trimEnd('/')
        val jvmHome = "$prefix/$JVM_HOME_RELATIVE"
        val lib = "$jvmHome/lib"
        val server = "$lib/server"
        if (!exists(jvmHome)) return emptyList()
        // The VM directory first, then the JVM lib, then the prefix lib as the fallback that
        // already worked.
        return listOf(server, lib, "$prefix/lib").filter { exists(it) }
    }

    /**
     * The full value for `LD_LIBRARY_PATH`, given what the exec path already had.
     *
     * THE JVM ENTRIES GO FIRST, BEFORE THE PREFIX LIB, and that is the whole fix. The prefix lib
     * is already on the path for every other binary in the tree; putting the JVM directories in
     * front means its own libraries win, while a prefix with no JVM installed keeps exactly the
     * value it had. Nothing else about the environment changes.
     *
     * Duplicates are removed while order is preserved, because a caller may already have added one
     * of these and a repeated entry is a second failed stat per lookup for no gain.
     */
    fun libraryPathValue(prefixPath: String, existing: List<String>, jvmDirs: List<String>): String {
        val merged = (jvmDirs + existing)
            .map { it.trimEnd('/') }
            .filter { it.isNotEmpty() }
            .distinct()
        return merged.joinToString(":")
    }

    /**
     * Whether a `java` invocation needs the JVM's own directories, from the path alone.
     *
     * Used by the toolchain probe so it can say WHY a launch failed rather than reporting a
     * generic load failure. A binary whose path is under the JVM home needs them; one under `bin`
     * that happens to be a symlink into it does too, which is why the resolved path is what
     * matters rather than the name that was typed.
     */
    fun needsJvmLibraries(resolvedBinaryPath: String): Boolean =
        resolvedBinaryPath.contains("/$JVM_HOME_RELATIVE/")

    /**
     * The exact launch the JVM needs on Android, as an argument list.
     *
     * THE LINKER MUST BE THE PROGRAM, AND THAT IS NOT A WORKAROUND. Every bundled binary here is
     * a PIE whose `PT_INTERP` is `/system/bin/linker64`, and the kernel runs the linker first and
     * hands it the real binary as `argv[1]`. Running the binary directly is what produced the
     * ticket's "Usage: /system/bin/linker64" output: the linker read `-version` as a program to
     * load, failed to resolve it, and printed its own usage. So the linker is named explicitly,
     * exactly as every other exec in this class does.
     */
    fun launch(linkerPath: String, javaBinaryPath: String, args: List<String>): List<String> =
        listOf(linkerPath, javaBinaryPath) + args

    /**
     * Whether an exec result is the linker complaining rather than Java answering.
     *
     * THE TRAP THE TICKET FELL INTO. `Usage: /system/bin/linker64 ...` on stdout looks like a
     * broken launcher and is really the linker saying it was invoked wrongly. Distinguishing it
     * matters because the two have opposite fixes: this one is fix the invocation, a load failure
     * is fix the library path.
     */
    fun isLinkerUsageOutput(output: String): Boolean =
        output.contains("Usage:") && output.contains("linker64")

    /**
     * One sentence naming what a failed JVM launch actually means.
     *
     * The three causes are kept apart because their fixes differ, and the ticket's history is
     * three separate rounds of diagnosing the wrong one.
     */
    fun failureSentence(output: String, resolvedPath: String, jvmDirsOnPath: Boolean): String = when {
        isLinkerUsageOutput(output) ->
            "The Android linker printed its own usage, which means the binary was invoked " +
                "directly instead of through $resolvedPath's interpreter. Run it as " +
                "`linker64 <binary> <args>`."
        output.contains("libjli.so") || output.contains("CANNOT LINK EXECUTABLE") ->
            "The JVM launcher could not resolve its own libraries. Its lib directories are " +
                (if (jvmDirsOnPath) "on LD_LIBRARY_PATH, so the archive is missing a file rather than the path being wrong."
                else "NOT on LD_LIBRARY_PATH, which is the measured cause of this failure.")
        else ->
            "The JVM launcher did not run. Its own output was: " +
                output.lineSequence().firstOrNull { it.isNotBlank() }?.take(200).orEmpty()
    }
}
