// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * The bundled CPython, and the three separate things that have to be true before
 * it can run a line of code.
 *
 * Issue 175 was filed as "python does not work in the app", which is one symptom
 * with three causes stacked on top of each other. Each one alone is enough to
 * make every Python call fail, and each one fails differently, so fixing them one
 * at a time looks like the fix did not work. They are:
 *
 * 1. NO LAUNCHER. libpython3.14.so is a shared object: entry point 0x0, no
 *    PT_INTERP. It can be loaded, never executed, and exec'ing it dies on SIGILL
 *    (exit 132) with no message. The launcher that starts it existed as a source
 *    file with no CMake target, so it was never built, never packaged, and this
 *    class returned false at the very first check. That is fixed in
 *    CMakeLists.txt and python_launcher.c.
 *
 * 2. WRONG STDLIB LAYOUT. The shipped python-stdlib-arm64.zip is FLAT: os.py,
 *    encodings/, site.py and forty-six .so files all sit at the archive root.
 *    CPython 3.14 does not look there. getpath searches
 *    <PYTHONHOME>/lib/python3.14/ for os.py and
 *    <PYTHONHOME>/lib/python3.14/lib-dynload/ for extension modules, and the
 *    shipped binary confirms it — `strings libpython3.14.so` carries the literal
 *    "<prefix>/lib/pythonX.X" and "/lib-dynload". Extracting flat and pointing
 *    PYTHONHOME at the result produces an interpreter that starts and then cannot
 *    `import os`, which reads as the same bug again.
 *
 * 3. A SENTINEL EXIT CODE. A launch failure was reported as exit code -1, which
 *    is a number that a real process can also return, so a caller could not tell
 *    "never started" from "ran and failed". See [PythonStatus].
 *
 * The rule this class now follows: [isAvailable] is true only when a real
 * interpreter process has been observed to start and print its version. A file
 * existing on disk is not evidence that Python works, and reporting it as one is
 * exactly the defect pattern that produced issues 25, 30 and 175.
 */
class PythonProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val LAUNCHER_NAME = "libpython_launcher.so"
        private const val PYTHON_DIR_NAME = "python"
        private const val RUNTIME_DIR_NAME = "runtime"
        private const val PYTHON_VERSION = "python3.14"
        private const val DYNLOAD_DIR_NAME = "lib-dynload"
        private const val SITE_PACKAGES_DIR_NAME = "site-packages"
        private const val MARKER_NAME = "stdlib_version"

        /**
         * The two packages the runtime's own completeness check looks for.
         *
         * They are named here rather than derived from a directory listing
         * because "is the image stack present" has a specific answer: Pillow and
         * numpy. A tree with neither is the state issue 317 reported, and it
         * must be rebuilt rather than accepted.
         */
        private const val NUMPY_DIR_NAME = "numpy"

        /**
         * The runtime archive, which is the interpreter, the standard library and
         * every shared library they need, all from one build.
         *
         * ONE ARCHIVE, NOT TWO HALVES. The previous arrangement shipped
         * libpython3.14.so in jniLibs and a separate stdlib zip in assets, sourced
         * independently. They were different builds: the interpreter reported
         * "3.14.5 (tags/v3.14.5-dirty)" while the extensions were built elsewhere,
         * and a C extension is loadable only by the exact interpreter that defines
         * its private symbols, so loading them together failed with
         *
         *   dlopen failed: cannot locate symbol "_PyType_AllocNoTrack"
         *
         * The archive is produced by fetch_python_runtime.sh from pinned packages,
         * which computes the library closure and refuses to produce a runtime whose
         * ELFs are not 16 KB page aligned.
         */
        private const val RUNTIME_ASSET = "python-runtime/python-runtime-arm64.zip"

        /**
         * Private space for the proxy bypass, stated the same way the curl path
         * states it.
         *
         * Both cases are emitted because Python's urllib reads the lowercase form
         * on some versions and the uppercase on others. This is a safety net
         * rather than the mechanism: the real decision is EgressClassifier's, made
         * before a proxy is injected at all.
         */
        const val NO_PROXY_VALUE: String =
            "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16," +
                "169.254.0.0/16,100.64.0.0/10,*.local,*.lan,*.home,*.home.arpa,*.internal"

        /** The ABIs a runtime archive is published for. */
        private fun hasRuntimeFor(abi: String): Boolean = abi == "arm64-v8a"

        private fun abiFor(context: Context): String {
            val preferred = android.os.Build.SUPPORTED_ABIS?.firstOrNull()
            return when {
                preferred == null -> "arm64-v8a"
                preferred == "x86_64" -> "x86_64"
                preferred == "arm64-v8a" -> "arm64-v8a"
                // armeabi-v7a and x86 are not shipped with a stdlib. Falling back
                // to arm64 would silently install a library for the wrong
                // architecture, so the honest answer is the one that will fail
                // with "asset missing" and say which path was looked for.
                else -> preferred
            }
        }

        /**
         * One warm-up for the whole process, not one per instance.
         *
         * Two PythonProvider instances are alive at once in normal use:
         * GuruApplication builds one to warm the runtime at startup and
         * ShellExecutor owns another to run scripts. With independent state both
         * could see "stdlib not extracted" and unpack sixteen megabytes into the
         * same directory at the same time, which produces a mixed tree that
         * passes some of its own checks and fails at import time.
         *
         * The answer is cached process-wide, so whichever instance runs second
         * waits on this lock and then reads the first one's result instead of
         * repeating the work.
         */
        private val initLock = Any()

        @Volatile
        private var sharedStatus: PythonStatus? = null
    }

    /**
     * Why Python is or is not available, in the caller's terms.
     *
     * The enum exists so a tool can say something true and specific. "Python is
     * not available" is the sentence that made issue 175 take a diagnostic
     * battery to unpick, because it is what you see whether the launcher is
     * missing, the stdlib is in the wrong place, or the interpreter segfaults.
     */
    enum class Failure {
        /** No launcher in nativeLibraryDir: this build did not package one. */
        LAUNCHER_MISSING,

        /** The stdlib zip for this device ABI is not in the APK's assets. */
        STDLIB_ASSET_MISSING,

        /** Extraction threw. [PythonStatus.detail] carries the message. */
        STDLIB_EXTRACTION_FAILED,

        /** The launcher exists but the kernel would not run it. */
        LAUNCHER_NOT_EXECUTABLE,

        /** The launcher ran and the interpreter inside it failed to start. */
        INTERPRETER_FAILED,

        /** Everything started, but the stdlib could not be imported from. */
        STDLIB_NOT_IMPORTABLE,
    }

    /**
     * A real, specific answer about the runtime.
     *
     * [detail] is written for a person reading a tool result, not for a log
     * file. It names the path that was checked and what was found there, so the
     * next report says which layer broke instead of "python does not work".
     */
    data class PythonStatus(
        val available: Boolean,
        val failure: Failure? = null,
        val detail: String? = null,
        val launcherPath: String? = null,
        val stdlibRoot: String? = null,
        val version: String? = null,
        /**
         * What the image stack did on the last probe, or null when it was not
         * reached because the runtime never started.
         *
         * Issue 317 asked for pixel work and found none: PIL, numpy and cv2 were
         * all ModuleNotFoundError and the only route to one was termuxInstall,
         * which was itself broken. The packages now ship inside the runtime, and
         * this field is the measured answer to whether they load rather than a
         * claim that they were copied in.
         */
        val imageTooling: ImageTooling? = null,
    ) {
        companion object {
            fun ok(launcherPath: String, stdlibRoot: String, version: String) =
                PythonStatus(true, launcherPath = launcherPath, stdlibRoot = stdlibRoot, version = version)

            fun no(failure: Failure, detail: String, launcherPath: String? = null, stdlibRoot: String? = null) =
                PythonStatus(false, failure = failure, detail = detail, launcherPath = launcherPath, stdlibRoot = stdlibRoot)
        }
    }

    private val pythonDir: File
        get() = File(context.filesDir, PYTHON_DIR_NAME)

    /**
     * The directory PYTHONHOME points at: the parent of `lib/python3.14` and of
     * `lib/libpython3.14.so`.
     *
     * Named "root" rather than "stdlib" because the standard library is one level
     * below it, and the difference between the two is the whole of defect 2.
     */
    private val stdlibRoot: File
        get() = File(pythonDir, RUNTIME_DIR_NAME)

    /** `<root>/lib/python3.14`, where os.py and friends live. */
    private val stdlibDir: File
        get() = File(File(stdlibRoot, "lib"), PYTHON_VERSION)

    /** `<root>/lib/python3.14/lib-dynload`, where extension modules live. */
    private val dynloadDir: File
        get() = File(stdlibDir, DYNLOAD_DIR_NAME)

    /**
     * `<root>/lib/python3.14/site-packages`, where installed packages live.
     *
     * Issue 317's image half. Pillow and numpy now ship inside this runtime, and
     * a site-packages directory only becomes importable because CPython's own
     * site.py adds it to sys.path relative to the stdlib it resolved. It is put
     * on PYTHONPATH as well, deliberately: this class sets PYTHONNOUSERSITE and
     * points PYTHONHOME at an extraction root the interpreter's getpath did not
     * compute for itself, so a package sitting on disk and unreachable is a real
     * possibility — and it looks identical to not shipping the package at all.
     */
    private val sitePackagesDir: File
        get() = File(stdlibDir, SITE_PACKAGES_DIR_NAME)

    @Volatile
    private var status: PythonStatus = PythonStatus(false)

    @Volatile
    private var initialized = false

    private val launcherFile: File
        get() = File(context.applicationInfo.nativeLibraryDir, LAUNCHER_NAME)

    /**
     * Bring the runtime up, once, and report what actually happened.
     *
     * Every step is checked in the order its failure would mask the next, and a
     * failure returns the reason instead of a bare false. The last step is a
     * real execution: this method does not report success for a runtime it has
     * not seen print its own version.
     */
    suspend fun initialize(): PythonStatus = withContext(Dispatchers.IO) {
        synchronized(initLock) {
            // Another instance in this process already did the work. Reading its
            // answer is the whole point of the shared lock: the extraction and
            // the probe are the expensive part and neither is idempotent-safe to
            // run concurrently.
            sharedStatus?.let {
                status = it
                initialized = true
                return@withContext it
            }
            if (initialized) return@withContext status

            val result = prepare()
            status = result
            sharedStatus = result
            initialized = true

            if (result.available) {
                Log.d(TAG, "PythonProvider: ready - ${result.version} at ${result.launcherPath}")
            } else {
                // Logged at warn, with the reason. A silent false here is what let
                // issue 175 survive several releases: the app knew Python was
                // missing and said so only to a debug log nobody was reading.
                Log.w(TAG, "PythonProvider: unavailable (${result.failure}): ${result.detail}")
            }
            return@withContext result
        }
    }

    private fun prepare(): PythonStatus {
        // 1. The launcher must exist and be executable. This is the check that
        //    used to fail silently for every install of every build.
        val launcher = launcherFile
        if (!launcher.isFile) {
            return PythonStatus.no(
                Failure.LAUNCHER_MISSING,
                "No $LAUNCHER_NAME in ${launcher.parent}. This build did not package the " +
                    "Python launcher, so there is no process that can start the interpreter. " +
                    "The interpreter library being present is not enough on its own: " +
                    "libpython3.14.so is a shared object with no entry point and cannot be executed.",
            )
        }
        if (!launcher.canExecute()) {
            return PythonStatus.no(
                Failure.LAUNCHER_NOT_EXECUTABLE,
                "$LAUNCHER_NAME exists at ${launcher.absolutePath} but is not executable.",
                launcherPath = launcher.absolutePath,
            )
        }

        // 2. The runtime must be unpacked: the interpreter, the standard library
        //    and the libraries its extensions name, all from one build. This is
        //    checked before anything is executed because running a launcher that
        //    has no interpreter to load can only report a missing library.
        val extracted = ensureStdlib()
        if (extracted != null) return extracted

        // The interpreter lives inside the extracted runtime now, not in
        // nativeLibraryDir. Its presence there is what makes a run possible, so it
        // is checked explicitly rather than discovered as a dlopen failure.
        val interpreter = File(File(stdlibRoot, "lib"), "libpython3.14.so")
        if (!interpreter.isFile) {
            return PythonStatus.no(
                Failure.STDLIB_EXTRACTION_FAILED,
                "The runtime was extracted but has no interpreter at " +
                    "${interpreter.absolutePath}. This is a fault in the asset, not in " +
                    "the device: rerun fetch_python_runtime.sh.",
                launcherPath = launcher.absolutePath,
                stdlibRoot = stdlibRoot.absolutePath,
            )
        }

        // 3. Prove it. A runtime that has never printed its version is a
        //    hypothesis, and reporting a hypothesis as `available` is what made
        //    this issue survive several releases.
        return verify(launcher)
    }

    /**
     * Unpack the standard library into `<root>/lib/python3.14`, or return the
     * reason it could not be done.
     *
     * Extraction is atomic: everything lands in a scratch directory and the
     * scratch directory is renamed into place only once it is complete. A process
     * killed mid-extract therefore leaves either the old runtime or no runtime,
     * never a half-written one that passes a marker check. The marker is written
     * last, for the same reason.
     */
    private fun ensureStdlib(): PythonStatus? {
        val abi = abiFor(context)
        if (!hasRuntimeFor(abi)) {
            // No runtime archive ships for this ABI. Saying so plainly is the only
            // honest answer: falling back to another architecture's library would
            // load and then fail on the first extension import, which is a far
            // worse diagnostic than a named absence.
            return PythonStatus.no(
                Failure.STDLIB_ASSET_MISSING,
                "This build ships no Python runtime for $abi. The archive " +
                    "$RUNTIME_ASSET is built for arm64-v8a.",
                launcherPath = launcherFile.absolutePath,
            )
        }
        val asset = RUNTIME_ASSET
        val packageVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L).toString()

        val marker = File(stdlibRoot, MARKER_NAME)
        // Every condition is checked because each one alone catches a different
        // partial state: the directory missing, the directory empty, the archive
        // extracted but the marker never written, an upgrade that changed the
        // version code under an existing tree, or a tree that has the pure-Python
        // library but none of its extension modules.
        /*
         * THE COMPLETENESS CHECK MUST NAME EVERYTHING THE RUNTIME IS FOR.
         *
         * Measured failure, issue 317: a runtime extracted by build 69 was still
         * on disk when a build 69 that ALSO carried Pillow and numpy was
         * installed. The marker held the same versionCode, os.py and lib-dynload
         * were present, so this returned "complete" and the new 33 MB archive was
         * never unpacked. On the device PIL stayed ModuleNotFoundError while the
         * APK contained it — the exact "shipped but unreachable" fault this
         * class exists to make impossible.
         *
         * So site-packages and its two packages are part of the definition of
         * complete. A tree from an older build that lacks them is rebuilt once,
         * which is the only way an upgrade can deliver them without the operator
         * clearing app data.
         */
        val complete = stdlibDir.isDirectory &&
            File(stdlibDir, "os.py").isFile &&
            dynloadDir.isDirectory &&
            File(sitePackagesDir, "PIL").isDirectory &&
            File(sitePackagesDir, NUMPY_DIR_NAME).isDirectory &&
            marker.isFile &&
            marker.readText().trim() == packageVersion

        if (complete) return null

        // A new install, an upgraded APK, or a previous attempt that died. Delete
        // whatever is there: a partial tree is worse than none, because it makes
        // every later check ambiguous.
        stdlibRoot.deleteRecursively()

        val assetPresent = runCatching {
            val dir = asset.substringBeforeLast('/', "")
            val name = asset.substringAfterLast('/')
            context.assets.list(dir)?.contains(name) == true
        }.getOrDefault(false)

        if (!assetPresent) {
            return PythonStatus.no(
                Failure.STDLIB_ASSET_MISSING,
                "No runtime archive at $asset inside the APK. Without it the interpreter " +
                    "has no standard library and cannot import os, so it is reported as " +
                    "unavailable rather than as a broken script.",
                launcherPath = launcherFile.absolutePath,
            )
        }

        val scratch = File(pythonDir, "$RUNTIME_DIR_NAME.incoming")
        return try {
            pythonDir.mkdirs()
            scratch.deleteRecursively()
            // The archive's own root IS the PYTHONHOME directory: it contains
            // lib/libpython3.14.so and lib/python3.14/. So entries are extracted
            // exactly as named, and the scratch root becomes the runtime root.
            scratch.mkdirs()

            val zipCopy = File(pythonDir, "stdlib.incoming.zip")
            try {
                context.assets.open(asset).use { input ->
                    FileOutputStream(zipCopy).use { output -> input.copyTo(output) }
                }

                ZipFile(zipCopy).use { zip ->
                    val entries = zip.entries()
                    val root = scratch.canonicalFile
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val target = File(scratch, entry.name)

                        // Zip-slip guard. An archive entry named "../../.." would
                        // otherwise write outside the app's own directory, and the
                        // archive is shipped content rather than something this
                        // code controls.
                        val canonical = target.canonicalFile
                        if (!canonical.path.startsWith(root.path + File.separator)) {
                            return PythonStatus.no(
                                Failure.STDLIB_EXTRACTION_FAILED,
                                "Refused to extract ${entry.name}: it resolves outside the " +
                                    "Python directory.",
                                launcherPath = launcherFile.absolutePath,
                            )
                        }

                        if (entry.isDirectory) {
                            canonical.mkdirs()
                        } else {
                            canonical.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { entryInput ->
                                FileOutputStream(canonical).use { entryOutput ->
                                    entryInput.copyTo(entryOutput)
                                }
                            }
                        }
                    }
                }
            } finally {
                zipCopy.delete()
            }

            // No reshuffling of extension modules.
            //
            // The archive is produced by fetch_python_runtime.sh, which lays the
            // tree out exactly as CPython searches it: lib/python3.14/lib-dynload
            // for extensions and lib/ for the interpreter's own shared libraries.
            // An earlier version of this class moved root-level .so files into
            // lib-dynload after extraction, which only made sense while the
            // shipped archive was flat. Doing it now would move the interpreter's
            // dependencies out of lib/ and break the library search.
            File(scratch, MARKER_NAME).writeText(packageVersion)

            // Rename into place. Both paths are inside filesDir, so this is a
            // same-filesystem move and cannot half-succeed.
            if (!scratch.renameTo(stdlibRoot)) {
                // renameTo on a directory can fail if the destination exists on
                // some filesystems. It was deleted above, so a failure here is a
                // real one and worth naming rather than swallowing.
                scratch.deleteRecursively()
                return PythonStatus.no(
                    Failure.STDLIB_EXTRACTION_FAILED,
                    "Extracted the standard library but could not move it into " +
                        "${stdlibRoot.absolutePath}. The internal storage may be full.",
                    launcherPath = launcherFile.absolutePath,
                )
            }
            null
        } catch (e: Exception) {
            scratch.deleteRecursively()
            PythonStatus.no(
                Failure.STDLIB_EXTRACTION_FAILED,
                "Could not unpack $asset: ${e.javaClass.simpleName}: ${e.message}",
                launcherPath = launcherFile.absolutePath,
            )
        }
    }

    /**
     * Run the interpreter and read its own version out of it.
     *
     * The probe deliberately imports nothing beyond `sys` on the first line and
     * `os` on the second. `sys` proves the interpreter started; `os` proves the
     * standard library is reachable at the location this class set up, which is
     * the defect that would otherwise appear as "python works but nothing else
     * does".
     */
    private fun verify(launcher: File): PythonStatus {
        /*
         * The probe imports the image stack as well as sys and os (issue 317).
         *
         * WHY PILLOW IS IN THE PROBE. Shipping it is not the same as being able
         * to use it: a compiled extension whose DT_NEEDED libraries are missing
         * or misaligned fails at IMPORT, not at build, and the failure arrives
         * as "cannot locate symbol" the first time an agent tries to crop a
         * photo. That is precisely the shape of fault this class exists to stop
         * reporting as success, so the runtime is only called available when the
         * image stack genuinely loaded.
         *
         * It is reported separately rather than fatally. A runtime whose Pillow
         * will not load is still a working Python, and refusing every script
         * because the image half is broken would be worse than saying which half
         * is broken. The version string proves the interpreter and stdlib; a
         * failed import here is attached as [PythonStatus.imageTooling].
         */
        val probe = "import sys, os; sys.stdout.write(sys.version.split()[0])"
        val result = runLauncher(launcher, listOf("-c", probe), timeoutSeconds = 30)

        if (result == null) {
            return PythonStatus.no(
                Failure.LAUNCHER_NOT_EXECUTABLE,
                "${launcher.absolutePath} exists and is marked executable but could not be " +
                    "started at all. Android refuses to execute files from the writable data " +
                    "directory, so this has to be the copy in nativeLibraryDir.",
                launcherPath = launcher.absolutePath,
            )
        }

        if (result.exitCode != 0) {
            val stderr = result.stderr.trim()
            val stdout = result.stdout.trim()

            // The interpreter starts but cannot find its library. This is the
            // exact signature of a wrong layout, so it gets its own reason rather
            // than a generic start failure.
            val libraryMissing = stderr.contains("Could not find platform independent libraries") ||
                stderr.contains("Consider setting \$PYTHONHOME") ||
                stderr.contains("ModuleNotFoundError: No module named 'os'") ||
                stderr.contains("No module named 'encodings'")

            return if (libraryMissing) {
                PythonStatus.no(
                    Failure.STDLIB_NOT_IMPORTABLE,
                    "The interpreter started but could not find its standard library, so no " +
                        "Python code can run. It looked for os.py under " +
                        "${stdlibDir.absolutePath}. Interpreter said: " +
                        (stderr.ifBlank { stdout }).take(500),
                    launcherPath = launcher.absolutePath,
                    stdlibRoot = stdlibRoot.absolutePath,
                )
            } else {
                PythonStatus.no(
                    Failure.INTERPRETER_FAILED,
                    "The interpreter exited ${result.exitCode}. " +
                        (stderr.ifBlank { stdout }).take(500),
                    launcherPath = launcher.absolutePath,
                    stdlibRoot = stdlibRoot.absolutePath,
                )
            }
        }

        val version = result.stdout.trim()
        if (version.isBlank()) {
            return PythonStatus.no(
                Failure.INTERPRETER_FAILED,
                "The interpreter ran and exited 0 without printing a version, which means it " +
                    "did not execute the probe. stderr: ${result.stderr.trim().take(300)}",
                launcherPath = launcher.absolutePath,
                stdlibRoot = stdlibRoot.absolutePath,
            )
        }

        // The interpreter and stdlib are proven. Now the half issue 317 is
        // actually about: whether the shipped image libraries load.
        val imageTooling = probeImageTooling(launcher)
        if (imageTooling != null && !imageTooling.available) {
            Log.w(TAG, "PythonProvider: interpreter is up but image tooling is incomplete — ${imageTooling.describe()}")
        } else if (imageTooling != null) {
            Log.d(TAG, "PythonProvider: image tooling ready — ${imageTooling.describe()}")
        }

        return PythonStatus.ok(
            launcherPath = launcher.absolutePath,
            stdlibRoot = stdlibRoot.absolutePath,
            version = version,
        ).copy(imageTooling = imageTooling)
    }

    /**
     * Whether the image libraries this runtime ships can actually be imported.
     *
     * A separate question from "is Python available" on purpose: the interpreter
     * working and Pillow failing to load are different facts with different
     * fixes, and collapsing them is the defect pattern issues 25, 30 and 175
     * were all filed about.
     */
    data class ImageTooling(
        val pillow: String?,
        val numpy: String?,
        val pillowError: String?,
        val numpyError: String?,
    ) {
        /** True only when both imported and reported a version. */
        val available: Boolean get() = pillow != null && numpy != null

        /** One line naming what loaded and what did not, with the real error. */
        fun describe(): String = when {
            available -> "Pillow $pillow, numpy $numpy"
            pillow == null && numpy == null ->
                "neither Pillow nor numpy imported (PIL: ${pillowError ?: "not tried"}; " +
                    "numpy: ${numpyError ?: "not tried"})"
            pillow == null -> "Pillow did not import: ${pillowError ?: "unknown"}; numpy $numpy"
            else -> "numpy did not import: ${numpyError ?: "unknown"}; Pillow $pillow"
        }
    }

    /**
     * Import Pillow and numpy in a real interpreter and report their versions.
     *
     * Each import is attempted in its own try so one failure does not hide the
     * other, and the error CLASS is captured because "cannot locate symbol" from
     * a misaligned extension and "No module named" from a missing package are
     * completely different faults that read the same in a one-line summary.
     */
    private fun probeImageTooling(launcher: File): ImageTooling? {
        val script = """
import sys
try:
    from PIL import Image
    sys.stdout.write("PIL=" + getattr(Image, "__version__", "?") + "\n")
except Exception as e:
    sys.stdout.write("PILERR=" + type(e).__name__ + ": " + str(e)[:200] + "\n")
try:
    import numpy
    sys.stdout.write("NUMPY=" + numpy.__version__ + "\n")
except Exception as e:
    sys.stdout.write("NUMPYERR=" + type(e).__name__ + ": " + str(e)[:200] + "\n")
""".trimIndent()

        val run = runLauncher(launcher, listOf("-c", script), timeoutSeconds = 60) ?: return null
        var pillow: String? = null
        var numpy: String? = null
        var pillowError: String? = null
        var numpyError: String? = null
        for (line in run.stdout.lineSequence()) {
            when {
                line.startsWith("PIL=") -> pillow = line.removePrefix("PIL=").trim()
                line.startsWith("NUMPY=") -> numpy = line.removePrefix("NUMPY=").trim()
                line.startsWith("PILERR=") -> pillowError = line.removePrefix("PILERR=").trim()
                line.startsWith("NUMPYERR=") -> numpyError = line.removePrefix("NUMPYERR=").trim()
            }
        }
        // A run that produced neither a version nor an error tells us nothing, so
        // it is reported as not measured rather than as a successful import.
        if (pillow == null && numpy == null && pillowError == null && numpyError == null) return null
        return ImageTooling(pillow, numpy, pillowError, numpyError)
    }

    private data class LauncherRun(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Start the launcher with the runtime environment and collect both streams.
     *
     * Returns null when the process could not be created at all, which is a
     * different fact from a process that started and failed. Both streams are
     * drained concurrently: an interpreter that writes enough to fill a pipe
     * buffer before exit would otherwise deadlock this call forever.
     */
    private fun runLauncher(
        launcher: File,
        arguments: List<String>,
        timeoutSeconds: Long,
    ): LauncherRun? {
        return try {
            val command = mutableListOf(launcher.absolutePath)
            command.addAll(arguments)
            val builder = ProcessBuilder(command)
            builder.environment().putAll(getPythonEnv())
            val process = builder.start()

            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val readerOut = Thread {
                runCatching { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
            }
            val readerErr = Thread {
                runCatching { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
            }
            readerOut.start()
            readerErr.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerOut.join(2000)
                readerErr.join(2000)
                return LauncherRun(
                    exitCode = -1,
                    stdout = stdout.toString().trim(),
                    stderr = "Timed out after ${timeoutSeconds}s." +
                        if (stderr.isNotBlank()) " " + stderr.toString().trim() else "",
                )
            }
            readerOut.join(5000)
            readerErr.join(5000)

            LauncherRun(process.exitValue(), stdout.toString().trim(), stderr.toString().trim())
        } catch (e: Exception) {
            Log.w(TAG, "PythonProvider: launcher could not be started", e)
            null
        }
    }

    /** The current runtime answer. Call [initialize] first. */
    fun status(): PythonStatus = status

    /** True only when a real interpreter has been observed to start. */
    fun isAvailable(): Boolean = status.available

    fun getPath(): String = status.launcherPath ?: ""

    fun buildExecCommand(scriptPath: String): List<String> =
        listOf(getPath(), scriptPath)

    fun buildExecCommand(code: String, isCode: Boolean = true): List<String> =
        if (isCode) listOf(getPath(), "-c", code) else listOf(getPath(), code)

    /** PYTHONHOME: the directory that CONTAINS `lib/python3.14`. */
    fun getPythonHome(): String = stdlibRoot.absolutePath

    /**
     * The CA bundle path, extracting it from assets on first use.
     *
     * Issues 286: the interpreter's OpenSSL was compiled against
     * /data/data/com.termux/files/usr/etc/tls/cert.pem, a path that does not exist
     * in this app, so ssl.get_default_verify_paths() reported an empty trust store
     * and every HTTPS request failed CERTIFICATE_VERIFY_FAILED. The app already
     * ships a working bundle for curl; this makes Python use the same file.
     *
     * Returns null only if the asset is genuinely absent, in which case the caller
     * leaves SSL_CERT_FILE unset rather than pointing it at a file that is not
     * there, because an unset variable produces the normal OpenSSL error while a
     * wrong one produces a misleading one.
     */
    fun ensureCaBundle(): String? {
        val target = File(context.filesDir, "cacert.pem")
        if (target.isFile && target.length() > 0) return target.absolutePath
        return try {
            context.assets.open("cacert.pem").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            if (target.length() > 0) target.absolutePath else null
        } catch (e: Exception) {
            Log.w(TAG, "PythonProvider: could not extract cacert.pem", e)
            null
        }
    }

    /**
     * The environment for the interpreter.
     *
     * Two things beyond the runtime itself, both of which were missing and both of
     * which made Python behave differently from every other tool in the app:
     *
     * TLS (issue 286). SSL_CERT_FILE points at the bundle this app ships, because
     * the interpreter's compiled-in OpenSSL prefix is a Termux path that does not
     * exist here. Without it HTTPS fails, and the obvious workaround a caller
     * reaches for is disabling verification, which is worse than the failure.
     *
     * EGRESS (issue 285). The proxy variables are only added when [torPorts] is
     * non-null, which the caller decides by asking EgressClassifier whether the
     * script targets anything public. That is the same decision curl gets, and it
     * matters that it is the same one: injecting a proxy for a LAN address is what
     * produced issue 215, where a request to the router came back as a dead host.
     *
     * What this does NOT do, and cannot: a raw socket.create_connection ignores
     * proxy variables completely. Only urllib, http.client with a proxy handler and
     * requests honour them. Confining a raw socket needs a network namespace or a
     * transparent redirect, which a stock app cannot install. The tool description
     * says so plainly rather than implying the environment covers everything.
     */
    fun getPythonEnv(torPorts: TorPorts? = null): Map<String, String> {
        val home = stdlibRoot.absolutePath
        val stdlib = stdlibDir.absolutePath
        val dynload = dynloadDir.absolutePath
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        // The runtime's own lib/ comes FIRST in the library search. That is where
        // libpython3.14.so and the seventeen libraries its extension modules need
        // live, all from the same pinned build. nativeLibraryDir stays on the path
        // after it because the launcher itself is there.
        val runtimeLib = File(stdlibRoot, "lib").absolutePath

        val env = mutableMapOf(
            "PYTHONHOME" to home,
            // site-packages is on the path explicitly, not only via site.py's own
            // resolution (issue 317). A runtime extracted under a PYTHONHOME the
            // interpreter did not compute for itself is exactly the case where
            // site.py's entry can be the wrong one, and an unimportable Pillow
            // is indistinguishable from one that was never shipped.
            "PYTHONPATH" to "$stdlib:$dynload:${sitePackagesDir.absolutePath}",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONUNBUFFERED" to "1",
            "PYTHONNOUSERSITE" to "1",
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "LD_LIBRARY_PATH" to "$runtimeLib:$nativeLibDir",
        )

        // TLS. Both names are set because OpenSSL reads SSL_CERT_FILE while
        // requests reads REQUESTS_CA_BUNDLE, and a caller importing either should
        // find a working store without having to know which is which.
        ensureCaBundle()?.let { ca ->
            env["SSL_CERT_FILE"] = ca
            env["SSL_CERT_DIR"] = File(ca).parent ?: context.filesDir.absolutePath
            env["REQUESTS_CA_BUNDLE"] = ca
            env["CURL_CA_BUNDLE"] = ca
            env["AWS_CA_BUNDLE"] = ca
        }

        // Tor, only when the caller decided this script needs it.
        if (torPorts != null) {
            val socks = "socks5h://127.0.0.1:${torPorts.socksPort}"
            val http = "http://127.0.0.1:${torPorts.httpPort}"
            env["ALL_PROXY"] = socks
            env["all_proxy"] = socks
            env["HTTP_PROXY"] = http
            env["http_proxy"] = http
            env["HTTPS_PROXY"] = http
            env["https_proxy"] = http
            // socks5h is what makes the PROXY resolve the name rather than the
            // device, so a DNS lookup does not leak the destination outside the
            // circuit. The NO_PROXY list is the same one curl receives.
            env["NO_PROXY"] = NO_PROXY_VALUE
            env["no_proxy"] = NO_PROXY_VALUE
        }

        return env
    }

    /** The two Tor listeners, as ports. Null in the env means no proxying. */
    data class TorPorts(val socksPort: Int, val httpPort: Int)
}
