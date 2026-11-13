// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Manages a bundled Termux Linux environment extracted from a bootstrap rootfs
 * archive embedded in the APK assets.
 *
 * DIRECTORY LAYOUT (final, after extract + canonicalise):
 *   The zip arrives with bin/, lib/, etc/ at termux ROOT; canonicalise moves
 *   every package dir inside usr/ so the final tree is Termux-standard:
 *   files/termux/home/     <- user home (stays at termux ROOT, never moves)
 *   files/termux/usr/      <- THE PREFIX. TERMUX_PREFIX resolves here
 *   files/termux/usr/bin/  <- all 250+ binaries (bash, apt, git, curl, etc.)
 *   files/termux/usr/lib/  <- all shared libraries (.so files)
 *   files/termux/usr/etc/  <- config files, apt sources, keyrings
 *
 * EXECUTION MODEL:
 *   We do NOT pipe commands through /system/bin/sh because Android's sandbox
 *   blocks the system shell from executing app-internal ELF binaries. Instead:
 *   - For simple commands: ProcessBuilder with env vars set
 *   - First word is the binary name, looked up in termux/usr/bin/
 *   - LD_LIBRARY_PATH includes termux/usr/lib/ so all .so deps resolve
 */
class TermuxProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val ASSET_BOOTSTRAP = "termux/termux-bootstrap.zip"
        private const val HOME_DIR = "home"

        /**
         * Termux git 2.56.0 runtime, packaged by fetch_git_termux.sh: bin/git,
         * the real libexec/git-core/git-remote-http helper, its
         * git-remote-https symlink, and share/git-core/templates. Every
         * library it needs (libcurl, libssl, libcrypto, libpcre2-8, libz,
         * libiconv, libnghttp2/3, libngtcp2, libssh2) already lives in this
         * prefix's lib/ from the bootstrap — the zip carries no libraries.
         */
        private const val ASSET_GIT_RUNTIME = "git-termux/git-termux.zip"

        /**
         * Done-marker for the git runtime install. Written ONLY after
         * bin/git really executes and prints a version, so a partial tree
         * retries on the next init.
         */
        private const val GIT_RUNTIME_MARKER = ".git-runtime-ok"

        /**
         * The bundled Java and reverse-engineering toolchain, packaged by
         * fetch_re_toolchain.sh from pinned Termux packages.
         *
         * WHY IT EXISTS (issue 200). GURU advertises a complete on-device
         * reverse-engineering chain and shipped without a Java runtime, so
         * apktool, jadx, dex2jar, apksigner and every `java -jar` in the app
         * could not run, and `signApk` could only report that no signing tool
         * existed. The archive carries a trimmed openjdk-21, aapt, aapt2,
         * zipalign and apktool with the exact shared-library closure their ELF
         * dynamic sections name.
         */
        private const val ASSET_RE_TOOLCHAIN = "re-toolchain/re-toolchain.zip"

        /**
         * The manifest inside that archive. java.util.zip cannot report a ZIP
         * entry's unix mode, so a symlink packed by `zip -y` arrives as a small
         * regular file whose CONTENT is the link target — a 32-byte "java" the
         * linker refuses. The manifest names every symlink so this class can
         * recreate them properly instead of guessing from file size.
         */
        private const val RE_TOOLCHAIN_MANIFEST = "re-toolchain-manifest.json"

        /**
         * Done-marker for the toolchain install, and its version.
         *
         * Written ONLY after java, aapt, aapt2, zipalign and apktool have each
         * genuinely executed, because issue 200's whole complaint is a surface
         * that reports tools present when they are not. Bumping the version
         * forces a reinstall over a tree left by an older build, which is what
         * makes a corrected archive actually replace a broken one on a handset
         * that already holds the old extract.
         */
        private const val RE_TOOLCHAIN_MARKER = ".re-toolchain-ok"

        /**
         * Version 2 repairs the thirteen dangling JDK launcher links (issue 292).
         *
         * WHY THE BUMP IS THE FIX, and not just bookkeeping. installReToolchain returns early
         * when the marker matches, so a handset that already ran version 1 keeps the relative
         * links it wrote — which point at bin/lib/jvm/... and resolve to nothing. Without this
         * bump the repair never runs on the devices that need it, which is every device that
         * has installed the toolchain before.
         */
        private const val RE_TOOLCHAIN_VERSION = 2

        /** Where the JVM lands, relative to the prefix. Matches the archive. */
        private const val JVM_HOME_RELATIVE = "lib/jvm/java-21-openjdk"

        /**
         * Version of the prefix-relocation pass. Bump it whenever the rewrite
         * rules change, so a tree installed by an older build re-runs the pass
         * rather than keeping the old, broken result forever.
         */
        private const val PREFIX_RELOCATION_VERSION = 1

        /** The prefix every shipped script and dpkg row was built against. */
        private const val UPSTREAM_FILES_ROOT = "/data/data/com.termux/files"

        /**
         * Done-marker for the prefix-wide RPATH sweep, and its version.
         *
         * The sweep runs one patchelf per ELF across the whole of usr/bin and
         * usr/libexec, which is several hundred processes. Without a marker it
         * would repeat on every cold start where the post-install latch had been
         * cleared. The marker records what the pass achieved, and bumping the
         * version re-runs it on a tree swept by an older build.
         */
        private const val RPATH_SWEEP_MARKER = ".rpath-swept"
        private const val RPATH_SWEEP_VERSION = 1

        /**
         * Record of the last time this prefix was proven to EXECUTE, and its
         * version.
         *
         * WHY A SECOND MARKER EXISTS AT ALL (issue 317). [isAvailable] answers
         * "is a prefix installed" from a populated bin directory, and that is
         * the correct answer to that question. It is NOT the answer to "can this
         * prefix run anything", and until now nothing anywhere asked the second
         * question on a tree that already had files.
         *
         * The measured consequence, reported against v3.45 as exit 139 on bash,
         * git, curl and apt with 416 binaries present. The payload on disk was
         * fine — a clean extract of the same bootstrap ran all four — so the
         * install on the handset had drifted into a state where the files were
         * there and the loader refused them, and no code path would ever notice
         * because `initialize()` only rebuilds when usr/bin holds nothing at all.
         *
         * This marker is written only after the whole prefix has been proven by
         * execution, and it records the app's versionCode, so an upgrade
         * re-proves the tree rather than trusting the one an older build left.
         */
        private const val EXEC_HEALTH_MARKER = ".prefix-executes"
        private const val EXEC_HEALTH_VERSION = 1

        /**
         * The exit status a process gets when the dynamic linker refuses it:
         * 128 + SIGSEGV (11). Measured on the handset as the shape of EVERY
         * failure in issue 317, and the one number that decides the fallback in
         * [execWithEnv].
         */
        private const val EXIT_SIGSEGV = 139

        /**
         * How many consecutive recovery attempts a single process may make.
         *
         * A re-extract is the expensive recovery: 29 MB of bootstrap, several
         * hundred chmods and a few hundred patchelf processes. If the first one
         * does not fix the tree, a second will not either, and looping would
         * turn a broken install into a boot storm. One attempt, then the surface
         * reports the failure with its real output.
         */
        private const val MAX_RECOVERY_ATTEMPTS = 1

        /*
         * Exact modes, as issue 175 requires. Kotlin has no octal literal, so
         * each is written in binary grouped three bits to one octal digit —
         * 111 101 101 is 7 5 5.
         *
         * Binaries are 0755 because the dynamic linker must READ an ELF's
         * header and program headers before mapping it; the 0711 the old
         * set* convenience calls produced gave execute without read, which is
         * why every binary in the prefix failed with "Permission denied".
         * Libraries are 0644 because a .so is mapped, never executed.
         */
        private const val MODE_BINARY = 0b111_101_101          // 0755 rwxr-xr-x
        private const val MODE_LIBRARY = 0b110_100_100         // 0644 rw-r--r--
        private const val MODE_READABLE_FILE = 0b110_100_100   // 0644 rw-r--r--
        private const val MODE_DIRECTORY = 0b111_101_101       // 0755 rwxr-xr-x

        /**
         * Tor's SOCKS port, which is the one the egress path uses (issue 335).
         *
         * The same value ShellToolExecutor derives from TorManager, stated here so the prefix's
         * environment does not have to reach into the Tor layer to build itself.
         */
        private const val TOR_SOCKS_PORT = 9050
    }

    private val termuxRoot: File
        get() = File(context.filesDir, "termux")

    /**
     * The app's data directory: the PARENT of `files`, which is what libtermux-exec wants.
     *
     * ISSUE 329. libtermux-exec-ld-preload reads TERMUX_APP__DATA_DIR to decide where it is
     * allowed to redirect an exec. This used to be `context.filesDir`, and the ticket's own
     * matrix shows that value FAILING where its parent succeeds:
     *
     *     TERMUX_APP__DATA_DIR=/data/user/0/<pkg>         git version 2.56.0   works
     *     TERMUX_APP__DATA_DIR=/data/user/0/<pkg>/files   Permission denied
     *
     * Every other variable people expect to matter, TERMUX__PREFIX and TERMUX__ROOTFS, is
     * insufficient on its own: with both set correctly and this one absent, every forked
     * child still dies with "Permission denied". So one wrong value here silently broke
     * every exec that crosses a fork, which is most of what a package manager does.
     *
     * DERIVED FROM filesDir RATHER THAN HARDCODED. `filesDir.parentFile` is the data
     * directory on every Android version and every install type, including a debug build
     * with a separate package id, so nothing here names a package or a user id.
     */
    private val appDataDir: File
        get() = context.filesDir.parentFile ?: context.filesDir

    private val usrPrefix: File
        get() = File(termuxRoot, "usr")

    private val binDir: File
        get() = File(usrPrefix, "bin")
    private val libDir: File
        get() = File(usrPrefix, "lib")
    private val etcDir: File
        get() = File(usrPrefix, "etc")
    private val usrDir: File
        get() = usrPrefix
    private val homeDir: File
        get() = File(termuxRoot, HOME_DIR)
    private val tmpDir: File
        get() = File(usrPrefix, "tmp")

    private var initialized = false
    private var available = false
    private var symlinksCreated = false

    /** How many recovery re-extracts this process has already performed. */
    private var recoveryAttempts = 0

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext available

        try {
            /*
             * Read the tree's state BEFORE any pass touches it.
             *
             * Every step below can legitimately repair the prefix — copyBundledLibs
             * restores missing libraries, createLibrarySymlinks recreates the
             * versioned links — so a measurement taken after them cannot tell a
             * healthy tree from one that was just repaired into looking healthy.
             * The device test caught exactly that: libraries were removed, the
             * passes put them back, the marker matched, the probe was skipped and
             * the damaged tree was never measured.
             */
            val libsAtEntry = libraryFingerprint()

            termuxRoot.mkdirs()

            // Corruption detection (the audit's stage-3 wiped tree): canonicalised tree
            // whose usr/bin holds zero executable files and whose SYMLINKS.txt is gone.
            // Old canonicaliser destroyed the payload (self-merge-then-delete through a
            // usr/bin -> ../bin symlink). Recovery: clean re-extract from the bundled
            // asset zip, which is the only copy of the dead binaries on the device.
            val binHasExecutables = binDir.exists() &&
                binDir.walkTopDown().any { it.isFile && it.canExecute() && it.length() > 0 }
            val treeWiped = binDir.exists() &&
                java.io.File(termuxRoot, "SYMLINKS.txt").exists().not() &&
                !binHasExecutables

            if (treeWiped) {
                Log.w(TAG, "TermuxProvider: wiped tree detected (no executable in usr/bin, SYMLINKS.txt consumed) — clean re-extract")
                fullCleanAndExtract()
            }

            val binHasFiles = binDir.exists() && binDir.listFiles()?.isNotEmpty() == true
            if (!binHasFiles) {
                Log.d(TAG, "TermuxProvider: extracting bootstrap from assets")
                if (!fullCleanAndExtract()) {
                    Log.w(TAG, "TermuxProvider: bootstrap not bundled or extraction failed")
                    initialized = true
                    return@withContext false
                }
            } else {
                Log.d(TAG, "TermuxProvider: found existing bootstrap, bin has ${binDir.listFiles()?.size ?: 0} files")
            }

            // Canonicalise the extracted tree into the Termux-standard layout.
            // The bootstrap zip delivers bin/, lib/, etc/ at termux ROOT; Termux ELFs
            // (bash, apt, dpkg) are compiled against a prefix ending in /usr and some
            // carry $ORIGIN-relative dependencies that want them under usr/. Every
            // top-level package dir (bin, lib, etc, share, var, libexec, ...) moves
            // under usr/ once. Runs on fresh extracts AND on legacy device trees
            // (devices that extracted before this fix) — no rebuild required.
            canonicaliseTreeLayout()

            // Bootstrap-wide symlinks apply AFTER canonicalisation: link names and
            // com.termux-absolute targets both resolve against the final usr/ tree.
            applyBootstrapSymlinks()

            // Ensure essential directories exist
            homeDir.mkdirs()
            usrDir.mkdirs()
            File(usrDir, "tmp").mkdirs()
            File(usrDir, "var").mkdirs()
            File(etcDir, "apt").mkdirs()

            // Copy bundled libs and tools (libexpat, libpng, zlib, patchelf, libc++_shared).
            // Every post-extract step is idempotent and NO step latches success unless its
            // outcome is real: copyBundledLibs skips existing non-empty files; library
            // symlinks skip existing links; RPATH patch runs only when patchelf verifies
            // as executable — and the latch below flips true only when the tree then
            // holds a REAL readable executable bash binary. A partially-run pass
            // re-runs in full on the next init (the audit's stage-5 latch defect).
            if (!symlinksCreated) {
                copyBundledLibs()
                createLibrarySymlinks()
                copyLibsToReToolsDir()
                patchElfBinaries()
                val bashBinary = File(binDir, "bash")
                symlinksCreated = bashBinary.exists() && bashBinary.length() > 0 && bashBinary.canRead()
                if (!symlinksCreated) {
                    Log.w(TAG, "TermuxProvider: post-install chain incomplete (bash missing) — will re-run next init")
                }
            }

            // Git runtime goes in AFTER copyBundledLibs/patchElfBinaries: the
            // patchelf it needs is copied by copyBundledLibs, and the RPATH it
            // points at is this prefix's lib/. Marker-gated on bin/git actually
            // running, so a tree half-extracted by a killed process retries.
            installGitRuntime()

            /*
             * The Java and reverse-engineering toolchain (issue 200).
             *
             * It runs AFTER installGitRuntime for two reasons. patchelf, which
             * the JVM's libjvm and the JVM launchers need their RUNPATH repointed
             * by, arrives with copyBundledLibs above. And the prefix has to be
             * complete before this pass because it verifies by EXECUTION: a
             * missing shared library shows up as a non-zero `java -version`, and
             * a partial tree would be measured as a broken install.
             *
             * Before relocatePrefixPaths on purpose. apktool's wrapper is a text
             * file carrying the absolute com.termux prefix in its libdir, and the
             * relocation pass rewrites exactly that, so the toolchain must be on
             * disk before it runs rather than being skipped until the next init.
             */
            installReToolchain()

            // Relocate every text file in the prefix onto the real prefix path. The
            // payload records /data/data/com.termux/files in script shebangs, dpkg's
            // status and .list rows, and apt's helper scripts. Left alone, apt-key's
            // shebang points at a shell that does not exist on this device, which is
            // what made apt unable to verify a single repository.
            relocatePrefixPaths()

            // The bootstrap's layout IS the real bin path (usr/bin) — no redirect
            // shims wanted. Substructure (tmp, var, apt dirs) above; done.

            // The mapping that lets dpkg install upstream-prefixed packages into
            // this tree. Built before apt config because apt references it.
            ensureCompatibilityRoot()

            // The other half of that mapping: a compiled-in upstream path cannot
            // be rewritten inside an ELF, so it is redirected at the libc
            // boundary by libpathshim.so, loaded through LD_PRELOAD in
            // buildEnv(). That is the unrooted answer and needs no adb either.

            // apt config — write at init so any termuxExec "apt ..." works
            initAptConfig()

            // Ensure apt transport methods + https variant exist under the prefix
            fixAptMethods()

            // Remove any old wrapper scripts that were created by previous versions
            // They don't work because Android SELinux blocks shebangs from app-private storage
            removeOldAptWrappers()

            /*
             * Exact modes across the whole prefix, LAST.
             *
             * It has to be last because every step above writes files — the
             * bundled libs, the library symlink targets, the git runtime, the
             * apt config, the restored wrappers — and a pass that runs before
             * them leaves the newest files at whatever mode the copy produced.
             * Issue 175 measured exactly that: 0711 on all 400 binaries, which
             * gives execute without read, and the loader cannot map what it
             * cannot read.
             */
            applyPrefixPermissions()

            available = binDir.exists() && binDir.listFiles()?.isNotEmpty() == true
            initialized = true

            /*
             * Prove the tree by EXECUTION, and rebuild it once if it cannot run
             * (issue 317).
             *
             * This runs last because every step above can leave the tree in a
             * state where files exist and nothing loads; measuring earlier would
             * measure a half-installed prefix. It is skipped entirely when the
             * marker already records a proven tree for this versionCode, because
             * the probe costs a few process spawns and the answer cannot change
             * until the prefix or the build does.
             *
             * A failure here is NOT reported as "Termux is not installed" —
             * [isAvailable] keeps answering the install question truthfully. What
             * changes is that the tree is rebuilt from the bundled payload instead
             * of being trusted forever, and the probe result is kept on
             * [execHealth] so the status surface can say what actually happened.
             */
            if (!execHealthIsCurrent(libsAtEntry)) {
                val measured = probePrefixExecution()
                execHealth = measured
                if (measured.allRan) {
                    writeExecHealthMarker(measured)
                } else {
                    Log.w(TAG, "TermuxProvider: prefix is installed but CANNOT EXECUTE — ${measured.describe()}")
                    recoverOnce()
                }
            }

            Log.d(TAG, "TermuxProvider: initialized — available=$available, bin=${binDir.absolutePath}, lib=${libDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: initialization failed", e)
            initialized = true
        }
        available
    }

    /**
     * What a real execution of the prefix's core binaries showed.
     *
     * The four names are the four the issue measured, and they are chosen for a
     * reason: each one fails for a different missing library. bash needs
     * libreadline and libandroid-support, curl needs libssl and libnghttp2, apt
     * needs its whole config tree, and git needs libcurl and libpcre2. A library
     * the sweep missed shows up in at least one of them.
     */
    data class ExecHealth(
        val allRan: Boolean,
        val bash: CommandProbeResult?,
        val git: CommandProbeResult?,
        val curl: CommandProbeResult?,
        val apt: CommandProbeResult?,
    ) {
        /** One line a person can act on, naming only what was observed. */
        fun describe(): String {
            val broken = listOf("bash" to bash, "git" to git, "curl" to curl, "apt" to apt)
                .filter { (_, probe) -> probe != null && probe.state != CommandProbe.RUNS }
            if (broken.isEmpty()) return "every probed binary ran"
            return broken.joinToString("; ") { (name, probe) ->
                "$name=${probe?.state} exit=${probe?.exitCode} ${probe?.output?.take(120).orEmpty()}"
            }
        }
    }

    /** The last measured execution health, or null before the first probe. */
    @Volatile private var execHealth: ExecHealth? = null

    /**
     * The measured execution health, NEVER null once the prefix is installed.
     *
     * THE BUG THIS FIXES, and it is exactly what the v3.46 handset report showed:
     * every binary reported RUNS, and prefixExecutes was null.
     *
     * The marker gate exists so a healthy tree is not re-probed on every cold
     * start. But when the marker IS current, the probe is skipped — and `execHealth`
     * is an INSTANCE field, so a provider constructed after the marker was written
     * has never measured anything and answers null. TermuxToolExecutor builds its
     * own provider, so on a warm install the status surface got null, reported it,
     * and the field vanished from the JSON.
     *
     * "I did not measure it" and "it does not work" are different answers, and
     * neither belongs in a field whose whole job is to say whether the tree runs.
     * So a caller asking for the verdict gets one: if this instance has not
     * measured the tree, measure it now. The four probes are a few milliseconds
     * and a status call is not a hot path.
     */
    fun executionHealth(): ExecHealth {
        execHealth?.let { return it }
        val measured = probePrefixExecution()
        execHealth = measured
        if (measured.allRan) writeExecHealthMarker(measured)
        return measured
    }

    /**
     * The versionCode this APK belongs to, used to decide whether a proven tree
     * belongs to this build.
     *
     * A marker recording a tree proven by an OLDER build is not evidence about
     * the tree this build is running against, because an upgrade can change the
     * bootstrap, the libs or the passes while leaving the extracted files in
     * place. Reading it from the package manager is the only way to know, and a
     * failure to read it falls back to 0, which simply re-probes.
     */
    private fun packageVersionCode(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    private fun execHealthMarker(): File = File(usrPrefix, EXEC_HEALTH_MARKER)

    /**
     * Whether the marker proves this exact tree, under this exact build, runs.
     *
     * Both facts are checked: the marker's own schema version, so a change to
     * what the probe means re-runs it, and the package versionCode, so an
     * upgrade re-proves rather than inheriting a claim made by different code.
     */
    /**
     * Whether the marker proves this exact tree, under this exact build, runs.
     *
     * THE FINGERPRINT MUST BE TAKEN BEFORE ANY REPAIR PASS, and that is not a
     * detail. The device test proved it: the libraries were removed, initialize()
     * then ran copyBundledLibs and createLibrarySymlinks as it always does, those
     * passes PUT THE LIBRARIES BACK, and by the time this check ran the count
     * matched the marker again. The probe was skipped, health stayed null, and a
     * tree that had been damaged on entry was never measured.
     *
     * So the caller reads [libraryFingerprint] at the top of initialize() and
     * passes it in. Comparing against the state on ENTRY is the only version of
     * this check that can notice damage, because everything after that point may
     * legitimately repair it.
     */
    private fun execHealthIsCurrent(libsAtEntry: Int): Boolean {
        val recorded = runCatching { execHealthMarker().readText() }.getOrNull() ?: return false
        return recorded.contains("version=$EXEC_HEALTH_VERSION") &&
            recorded.contains("app=${packageVersionCode()}") &&
            recorded.contains("libs=$libsAtEntry")
    }

    /**
     * How many shared libraries the prefix holds, as a cheap fingerprint of the
     * tree the marker was written against.
     *
     * WHY THE MARKER NEEDED A THIRD FACT. The device test proved the hole: a tree
     * whose libraries were removed still carried a marker written when they were
     * present, the versionCode had not changed, and so [execHealthIsCurrent]
     * answered true, [initialize] skipped the probe, and a prefix that could not
     * execute reported itself healthy. That is issue 317 exactly, one layer in.
     *
     * A count is enough for the shape that actually happens — libraries removed,
     * truncated or never extracted — and it costs one directory listing rather
     * than a hash over a hundred megabytes. A count cannot detect a library
     * replaced by a different file of the same name, and that limit is stated
     * rather than implied: the per-run probe is what catches real corruption, and
     * this is only the cheap gate in front of it.
     */
    private fun libraryFingerprint(): Int =
        libDir.listFiles()?.count { it.isFile } ?: 0

    private fun writeExecHealthMarker(health: ExecHealth) {
        runCatching {
            execHealthMarker().writeText(
                "version=$EXEC_HEALTH_VERSION;app=${packageVersionCode()};" +
                    "libs=${libraryFingerprint()};" +
                    "ran=${listOf(health.bash, health.git, health.curl, health.apt).count { it?.state == CommandProbe.RUNS }}" +
                    ";bash=${health.bash?.output?.lineSequence()?.firstOrNull()?.take(80)}\n",
            )
        }.onFailure { Log.w(TAG, "TermuxProvider: could not write $EXEC_HEALTH_MARKER: ${it.message}") }
    }

    /**
     * Run the four binaries the issue measured and report what the kernel did.
     *
     * A probe result is reused rather than reimplemented: [probeCommand] already
     * distinguishes ABSENT from WILL_NOT_LOAD from RUNS and attaches the real
     * output, so this class keeps ONE definition of "this binary works" instead
     * of growing a second one that can disagree.
     */
    private fun probePrefixExecution(): ExecHealth {
        val bash = probeCommand("bash")
        val git = probeCommand("git")
        val curl = probeCommand("curl")
        val apt = probeCommand("apt")
        val allRan = listOf(bash, git, curl, apt).all { it.state == CommandProbe.RUNS }
        return ExecHealth(allRan, bash, git, curl, apt)
    }

    /**
     * Rebuild the prefix from the bundled payload, once, because it is installed
     * and cannot execute.
     *
     * The distinction from the wiped-tree case matters. A wiped tree has no
     * files, so re-extraction is obviously correct. A tree that HAS files and
     * cannot run them is the harder case: something about the extract on this
     * device is wrong, and the bundled payload is the only known-good copy. So
     * the whole tree is discarded and rebuilt rather than patched, because a
     * partial repair of an unknown drift is how a prefix ends up in a state
     * nobody can explain twice.
     *
     * `home/` survives: it holds the operator's own scripts and notes, which no
     * part of this rebuild owns.
     */
    private suspend fun recoverOnce() {
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(
                TAG,
                "TermuxProvider: recovery already attempted $recoveryAttempts time(s) this process; " +
                    "leaving the tree for the next cold start rather than looping",
            )
            return
        }
        recoveryAttempts++

        Log.w(TAG, "TermuxProvider: rebuilding the prefix from the bundled payload (issue 317)")
        val rebuilt = fullCleanAndExtract()
        if (!rebuilt) {
            Log.w(TAG, "TermuxProvider: rebuild could not extract the bundled payload")
            return
        }

        // Every post-extract pass is marker-gated, so the markers that belong to
        // the tree just deleted must go with it. fullCleanAndExtract removes the
        // whole tree except home/, which already takes them; this states the
        // requirement rather than relying on it, because a surviving marker would
        // make the next init skip the pass that produces the missing library.
        listOf(RPATH_SWEEP_MARKER, GIT_RUNTIME_MARKER, RE_TOOLCHAIN_MARKER, EXEC_HEALTH_MARKER)
            .forEach { runCatching { File(usrPrefix, it).delete() } }

        canonicaliseTreeLayout()
        applyBootstrapSymlinks()
        homeDir.mkdirs()
        usrDir.mkdirs()
        File(usrDir, "tmp").mkdirs()
        File(usrDir, "var").mkdirs()
        File(etcDir, "apt").mkdirs()

        // The same post-install chain initialize() runs, in the same order and for
        // the same reasons. It is repeated rather than re-entered because
        // initialize() short-circuits on its own latch, and clearing that latch
        // mid-call would recurse into the probe that called this.
        symlinksCreated = false
        copyBundledLibs()
        createLibrarySymlinks()
        copyLibsToReToolsDir()

        /*
         * THE RPATH SWEEP IS SKIPPED DURING A REBUILD, deliberately.
         *
         * It was here and it crashed the rebuild on the device test with an
         * OutOfMemoryError inside ProcessBuilder.environment(), reached from
         * runPatchelf. The reason is arithmetic: the sweep spawns one patchelf
         * process for every ELF in usr/bin, usr/libexec and usr/sbin — several
         * hundred — and each spawn builds a fresh environment map plus its
         * ProcessEnvironment bookkeeping inside an app heap that is already
         * holding a 29 MB compressed bootstrap extract. The sweep is a cold-start
         * convenience, not a correctness step, and a rebuild that dies halfway
         * through it leaves the tree worse than before.
         *
         * WHAT IS LOST BY SKIPPING IT. The sweep repoints DT_RUNPATH from the
         * upstream com.termux path to this prefix's lib. The binaries still
         * resolve without it because every launch goes through linker64 with
         * LD_LIBRARY_PATH set, which is the same mechanism that makes them run in
         * the first place. The sweep only matters for a binary launched some
         * OTHER way, which nothing in the app does.
         *
         * The marker is deleted here so the sweep still runs once on the next
         * ordinary init, where the heap is not holding an extraction, which keeps
         * the original behaviour without putting it on the recovery path.
         */
        runCatching { File(usrPrefix, RPATH_SWEEP_MARKER).delete() }
        val bashBinary = File(binDir, "bash")
        symlinksCreated = bashBinary.exists() && bashBinary.length() > 0 && bashBinary.canRead()
        installGitRuntime()
        installReToolchain()
        relocatePrefixPaths()
        ensureCompatibilityRoot()
        initAptConfig()
        fixAptMethods()
        removeOldAptWrappers()
        applyPrefixPermissions()

        // Measure the rebuild by the only thing that counts.
        execHealth = probePrefixExecution()
        if (execHealth?.allRan == true) {
            writeExecHealthMarker(execHealth!!)
            Log.d(TAG, "TermuxProvider: rebuild fixed the prefix — every probed binary runs")
        } else {
            Log.w(
                TAG,
                "TermuxProvider: rebuild complete but the prefix still cannot execute — " +
                    "${execHealth?.describe()}",
            )
        }
    }

    /** Canonicalise root-level package dirs into the Termux-standard usr/ prefix layout. */
    private suspend fun canonicaliseTreeLayout() = withContext(Dispatchers.IO) {
        try { TermuxCanonicaliser.canonicalise(termuxRoot) } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: canonicalise failed: ${e.message}")
        }
    }

    /**
     * Clean recovery: wipe the termux root except home/, then re-extract the
     * bundled bootstrap. home/ is the human's own directory (their scripts,
     * notes); everything else rebuilds from assets which hold the full payload.
     */
    private fun fullCleanAndExtract(): Boolean {
        try {
            // Preserve home/
            val home = File(termuxRoot, HOME_DIR)
            val homeContents = home.listFiles()?.let { items ->
                items.filter { it.name != "termux" }.mapNotNull { file ->
                    try { file.absolutePath to file.isDirectory } catch (e: Exception) { null }
                }
            } ?: emptyList()
            // Wipe everything except the termux/home dir itself
            termuxRoot.listFiles()?.forEach { child ->
                if (child.name != HOME_DIR) {
                    when {
                        child.isDirectory && child.name != HOME_DIR -> {
                            if (child.absolutePath != home.absolutePath) {
                                child.deleteRecursively()
                            }
                        }
                        else -> child.delete()
                    }
                }
            }
            Log.d(TAG, "TermuxProvider: clean wipe done (home preserved); re-extracting")
            val extracted = extractBootstrap()
            if (extracted) {
                // Reset latches so this process re-runs every post-install chain step
                symlinksCreated = false
                Log.d(TAG, "TermuxProvider: clean re-extract completed")
            }
            return extracted
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: clean re-extract failed", e)
            return false
        }
    }

    // Lazy — detect linker64/32 once
    private val linker by lazy {
        val is64Bit = System.getProperty("os.arch")?.contains("64") ?: true
        if (is64Bit) "/system/bin/linker64" else "/system/bin/linker"
    }

    /**
     * Whether the prefix is installed, answered from DISK rather than from
     * this instance's own memory.
     *
     * THE DEFECT THIS FIXES, reported by GURU on a fresh v3.9 install. `available`
     * was an instance field defaulting to false and only set by [initialize] on
     * the SAME object. Application boot constructs one provider and installs the
     * prefix through it; TermuxToolExecutor constructs a SECOND provider, whose
     * latch was still false. termuxStatus therefore answered
     * {"success":false} and GURU confidently reported "Termux isn't installed"
     * while a verified prefix, bash and git 2.56.0 sat on disk.
     *
     * "Is it installed" is a fact about the filesystem, not about which object
     * asked. The latch stays for cheap short-circuiting inside [initialize], but
     * any caller asking the public question gets the real answer.
     *
     * WHAT THIS DELIBERATELY DOES NOT ANSWER (issue 317). "Installed" is not
     * "working". Every caller in the app gates on this function before running
     * anything, so a true answer here is what stops [initialize] from ever being
     * called — and [initialize] is the only place the execution probe and the
     * rebuild live. A prefix with 416 binaries that segfault on every invocation
     * therefore reported itself installed and healthy forever.
     *
     * The answer stays true for the question it asks, because a false negative
     * here would make every tool refuse to even try. What fixes the trap is that
     * [initialize] now proves the tree by execution whenever it is entered, and
     * [executionHealth] reports the measured result separately.
     */
    fun isAvailable(): Boolean {
        if (available) return true
        // A real prefix has a populated bin directory. Anything else is not
        // installed, and this cannot produce a false positive from a partial
        // extract because initialize() only clears the wiped-tree case when bin
        // is empty.
        val installed = binDir.isDirectory && binDir.listFiles()?.isNotEmpty() == true
        if (installed) available = true
        return installed
    }

    /**
     * Whether the prefix is installed AND was last measured to execute.
     *
     * The distinction callers need: [isAvailable] is "the files are here",
     * [isUsable] is "the files run". A tool that is about to give a person a
     * refusal should ask this one, because a prefix that cannot exec is a
     * rebuild waiting to happen rather than a missing install.
     */
    fun isUsable(): Boolean = isAvailable() && executionHealth().allRan

    fun isInitialized(): Boolean = initialized || isAvailable()

    fun getRootPath(): String = termuxRoot.absolutePath
    fun getHomePath(): String = homeDir.absolutePath
    fun getTmpPath(): String = tmpDir.absolutePath
    fun getBinPath(): String = binDir.absolutePath
    fun getLibPath(): String = libDir.absolutePath
    fun getLinkerPath(): String = linker

    /** Absolute path of the bundled JVM home, whether or not it exists yet. */
    fun getJvmHomePath(): String = File(usrPrefix, JVM_HOME_RELATIVE).absolutePath

    /**
     * Absolute path of one of the toolchain's binaries in this prefix.
     *
     * Exists so callers never build a path from a guess: the JVM's launchers
     * live under lib/jvm, while aapt, aapt2, zipalign and apktool live in bin/,
     * and a caller that assumes one location reports the other missing.
     */
    fun getToolPath(name: String): String = File(binDir, name).absolutePath

    /** True once the toolchain has been installed and every tool has run. */
    fun isReToolchainInstalled(): Boolean {
        val marker = File(usrPrefix, RE_TOOLCHAIN_MARKER)
        val recorded = runCatching { marker.readText() }.getOrNull() ?: return false
        return recorded.contains("version=$RE_TOOLCHAIN_VERSION")
    }

    /**
     * The one honest answer to "is this tool usable", for the reverse-engineering
     * surface.
     *
     * WHY THIS IS A RUN AND NOT A FILE CHECK (issue 200). The shipped status was
     * built from `File.exists()`, so it reported jadx present because a 5751-byte
     * Gradle start script named `jadx-arm64` was on disk, while the JVM that
     * script's first act is to look for did not exist anywhere in the APK. A
     * status field that answers from existence is not reporting on the tool, it
     * is reporting on the filename.
     *
     * So the answer comes from the same three-state probe the rest of the prefix
     * uses, with the argument each tool actually accepts rather than a blanket
     * --version that apktool and zipalign do not take.
     */
    fun probeTool(name: String, args: List<String> = listOf("--version")): CommandProbeResult {
        val resolved = resolveBinary(name)
            ?: return CommandProbeResult(
                CommandProbe.ABSENT,
                "$name is not present in ${searchPath.joinToString(":") { it.absolutePath }}",
                -1,
                null,
            )
        val result = execWithEnv(listOf(linker, resolved) + args)
        val combined = (result.stdout + "\n" + result.stderr).trim()
        val loadFailureMarkers = listOf(
            "CANNOT LINK EXECUTABLE",
            "not found: needed by main executable",
            "Permission denied",
            "Exec format error",
            "No such file or directory",
            "command not found",
        )
        val refusedToLoad = result.exitCode < 0 ||
            loadFailureMarkers.any { combined.contains(it, ignoreCase = true) }
        return when {
            refusedToLoad -> CommandProbeResult(CommandProbe.WILL_NOT_LOAD, combined, result.exitCode, resolved)
            combined.isNotEmpty() -> CommandProbeResult(CommandProbe.RUNS, combined, result.exitCode, resolved)
            else -> CommandProbeResult(
                CommandProbe.WILL_NOT_LOAD,
                "$name produced no output and exited ${result.exitCode}",
                result.exitCode,
                resolved,
            )
        }
    }

    // RE tools directory — same level as termux root
    private val reToolsDir: File
        get() = File(context.filesDir, "re-tools")

    // PATH search order: termux usr/bin, re-tools, system
    private val searchPath: List<File>
        get() = listOf(binDir, reToolsDir, File("/system/bin"), File("/system/xbin"))

    // Shell metacharacters that signal "this needs bash -c"
    private val shellMetaChars = Regex("""[;|&<>]""")

    // Shared env map reused across all ProcessBuilder calls
    private fun buildEnv(): MutableMap<String, String> {
        val env = mutableMapOf(
            "PREFIX" to usrPrefix.absolutePath,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:${reToolsDir.absolutePath}:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${libDir.absolutePath}:${reToolsDir.absolutePath}/lib",
            // libtermux-exec reads these env vars to override its compiled-in defaults.
            "TERMUX__ROOTFS" to termuxRoot.absolutePath,
            "TERMUX__PREFIX" to usrPrefix.absolutePath,
            "TERMUX_PREFIX" to usrPrefix.absolutePath,
            "TERMUX_HOME" to homeDir.absolutePath,
            // THE PARENT OF files, NOT files ITSELF (#329). See appDataDir for the matrix.
            "TERMUX_APP__DATA_DIR" to appDataDir.absolutePath,
            "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
            "TERMUX_APP_PACKAGE" to context.packageName,
            "TERMUX_ANDROID_HOME" to homeDir.absolutePath
        )

        /*
         * THE PROXY GOES IN THE ENVIRONMENT, NOT IN EACH COMMAND (issue 335).
         *
         * Every other tool in the app reaches the network through the Tor circuit, and the
         * Termux prefix did not: a `curl` inside it connected directly and reported the
         * device's real address, while the same binary family through busyboxExec reported a
         * Tor exit. One architecture promise, two behaviours, and the prefix was the one
         * outside it.
         *
         * THE SHAPE IS COPIED FROM THE PATH THAT ALREADY WORKS rather than invented here.
         * ShellToolExecutor sets ALL_PROXY and both HTTP variables to socks5h on the SOCKS
         * port, because socks5h is what makes the RESOLVER go through the proxy: plain socks5
         * resolves the name locally, which leaks the query even when the connection is
         * tunnelled.
         *
         * THE PORTS ARE THE STANDARD ONES THE TOR SERVICE PUBLISHES on this device, and the
         * ticket's own suggested fix names the same values.
         *
         * THIS IS NOT THE CLEARNET OVERRIDE. If a person turns clearnet on, that is a decision
         * taken in the egress layer for one command; it is not a reason for the whole prefix to
         * be permanently outside the circuit, which is what having no variables at all meant.
         */
        env["ALL_PROXY"] = "socks5h://127.0.0.1:$TOR_SOCKS_PORT"
        env["HTTP_PROXY"] = "socks5h://127.0.0.1:$TOR_SOCKS_PORT"
        env["HTTPS_PROXY"] = "socks5h://127.0.0.1:$TOR_SOCKS_PORT"
        // Lower case as well, because plenty of tools read only these spellings.
        env["all_proxy"] = "socks5h://127.0.0.1:$TOR_SOCKS_PORT"
        env["http_proxy"] = "socks5h://127.0.0.1:$TOR_SOCKS_PORT"
        env["https_proxy"] = "socks5h://127.0.0.1:$TOR_SOCKS_PORT"
        /*
         * LD_PRELOAD carries TWO libraries, in order, and the order matters.
         *
         *  1. libtermux-exec-ld-preload.so — routes app-data execs through
         *     linker64, which is what makes `sh` and every helper script run at
         *     all. Present since the beginning and load-bearing.
         *
         *  2. libpathshim.so — rewrites a leading upstream com.termux path to
         *     this prefix at the libc boundary. This is what makes a binary with
         *     a COMPILED-IN path (figlet's font directory is the smallest
         *     example) find its own files without root, without adb and without
         *     ptrace. A compiled constant cannot be edited in a file, so it has
         *     to be handled at the point where a path becomes a syscall.
         *
         * Both are listed only when the file exists. Pointing LD_PRELOAD at an
         * absent path makes Android's dynamic linker refuse every linked launch
         * with CANNOT LINK EXECUTABLE, so one missing library would take the
         * whole prefix down.
         */
        val preloads = mutableListOf<String>()
        val termuxExec = File(libDir, "libtermux-exec-ld-preload.so")
        if (termuxExec.exists()) preloads.add(termuxExec.absolutePath)
        val pathShim = File(context.applicationInfo.nativeLibraryDir, "libpathshim.so")
        if (pathShim.exists()) {
            preloads.add(pathShim.absolutePath)
            // The shim reads these at first use. Setting both means the shim
            // contains no hardcoded package id, so the same library is correct
            // in debug and in release.
            env["GURU_SHIM_FROM"] = UPSTREAM_FILES_ROOT
            env["GURU_SHIM_TO"] = termuxRoot.absolutePath
        }
        if (preloads.isNotEmpty()) {
            env["LD_PRELOAD"] = preloads.joinToString(":")
        }
        // SSL/TLS: apt and curl need to find CA certificates
        val certPem = File(etcDir, "tls/cert.pem")
        if (certPem.exists()) {
            env["SSL_CERT_FILE"] = certPem.absolutePath
            env["CA_CERT_FILE"] = certPem.absolutePath
            env["REQUESTS_CA_BUNDLE"] = certPem.absolutePath
            env["GIT_SSL_CAINFO"] = certPem.absolutePath
        }
        /*
         * APT_CONFIG in the AMBIENT environment, not only per call.
         *
         * Issue 31 named this precisely: the config was correct but only found
         * when the tool set APT_CONFIG itself, so `apt update` typed in a shell
         * fell back to the compiled-in com.termux root and failed with
         * "Unable to determine a suitable packaging system type". Setting it in
         * the shared environment means every process we start, interactive or
         * not, sees the same answer. This is the difference between a fix that
         * works from inside the tool and a fix that works.
         */
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf")
        if (aptConfig.exists()) {
            env["APT_CONFIG"] = aptConfig.absolutePath
        }
        return env
    }

    /**
     * Execute a command inside the Termux environment.
     *
     * Binary lookup searches PATH in order: termux/usr/bin, re-tools, system/bin, system/xbin.
     * Shell metacharacters (; | && || > <) trigger automatic bash -c wrapping.
     * Uses /system/bin/linker64 for binaries on noexec filesystems (/data),
     * direct execution for binaries on /system.
     */
    /**
     * Run one binary from this prefix with an explicit argument list.
     *
     * WHY THIS EXISTS, alongside execInTermux. execInTermux takes a command
     * STRING, parses it on whitespace and re-quotes by hand, which is fine for a
     * command a person typed and wrong for a path a caller built: an argument
     * containing a space or a quote silently becomes two arguments or none. The
     * reverse-engineering tools take file paths, and a path with a space in it is
     * ordinary on Android.
     *
     * Resolution and the linker64 launch are the same as execInTermux, so this is
     * the same execution model with the quoting hazard removed rather than a
     * second mechanism.
     */
    fun execBinary(name: String, args: List<String>): ShellResult {
        val resolved = resolveBinary(name)
            ?: return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "command not found: $name (searched ${searchPath.joinToString(": ") { it.absolutePath }})",
                success = false,
                tier = ShellTier.APP_UID,
            )
        val processArgs = if (resolved.startsWith("/system/")) {
            listOf(resolved) + args
        } else {
            listOf(linker, resolved) + args
        }
        return execWithEnv(processArgs)
    }

    fun execInTermux(command: String): ShellResult {
        // Auto-detect shell syntax — semicolons, pipes, redirects, conditionals
        if (shellMetaChars.containsMatchIn(command)) {
            return execBash(command)
        }

        val parts = parseCommand(command)
        if (parts.isEmpty()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "empty command", success = false)
        }

        val binaryName = parts[0]
        val args = if (parts.size > 1) parts.drop(1) else emptyList()
        val resolved = resolveBinary(binaryName)

        if (resolved == null) {
            return ShellResult(
                exitCode = -1, stdout = "",
                stderr = "command not found: $binaryName (searched termux/usr/bin, re-tools, /system/bin)",
                success = false
            )
        }

        val isOnSystem = resolved.startsWith("/system/")
        val processArgs = if (isOnSystem) {
            mutableListOf(resolved).also { it.addAll(args) }
        } else {
            // linker64 loads ELF from noexec /data, linker searches binary's dir for .so
            mutableListOf(linker, resolved).also { it.addAll(args) }
        }

        return execWithEnv(processArgs)
    }

    /**
     * Run a command through bash. For piped commands, shell scripts, or anything
     * with shell metacharacters.
     * linker64 /data/.../termux/bin/bash -c "..."
     */
    fun execBash(script: String): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found in termux/usr/bin/", success = false)
        }
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", script))
    }

    /**
     * Install packages via apt.
     *
     * apt is compiled against `/data/data/com.termux/files/usr`, so every path
     * it knows has to be redirected at this prefix. The redirect lives in TWO
     * places on purpose:
     *
     *  - `etc/apt/apt.conf.d/01-root.conf`, written by [initAptConfig], which is
     *    what any other caller running `apt` gets.
     *  - `APT_CONFIG` in the environment for THIS call, pointing at that file,
     *    so apt reads our configuration before any compiled-in default. Without
     *    it apt starts from the com.termux root, cannot find its parts
     *    directory, and prints "Unable to determine a suitable packaging system
     *    type" before a single -o flag is consulted.
     *
     * The two -o flags are kept for the paths apt resolves most eagerly, but the
     * config file is the mechanism now: a pile of -o arguments repeated per call
     * is how this drifted out of step in the first place.
     */
    fun execAptInstall(packages: String): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found", success = false)
        }
        val prefix = usrPrefix.absolutePath
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf").absolutePath
        val aptBinary = File(binDir, "apt").absolutePath
        val aptEnv = "export APT_CONFIG='$aptConfig'; " +
            "export PATH='${binDir.absolutePath}:/system/bin'; "
        // dpkg's own compiled-in admin dir is the com.termux path, so every
        // dpkg invocation inside apt gets an explicit one.
        val dpkgArgs = "-o Dir::Bin::dpkg='$prefix/bin/dpkg' " +
            "-o Dir::Bin::methods='$prefix/lib/apt/methods' " +
            "-o DPkg::Options::='--admindir=$prefix/var/lib/dpkg' " +
            "-o DPkg::Options::='--instdir=${termuxRoot.absolutePath}'"

        val fullScript = aptEnv + "\"$aptBinary\" $dpkgArgs update 2>&1; " +
            aptEnv + "\"$aptBinary\" $dpkgArgs install -y $packages 2>&1"
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", fullScript))
    }

    /**
     * The three distinct things `termuxDiagnose` needs to distinguish about apt.
     *
     * Issue 4 asked for this and issue 30 named the exact cost of not having it:
     * a boolean that meant "my probe failed" was reported as "the binary is
     * missing", so a healthy apt-get was declared absent and the real cause was
     * lost. These three values make that impossible by construction.
     */
    enum class AptProbe {
        /** No apt-get binary at all in the prefix. */
        ABSENT,
        /** The binary exists but will not run, with the real output attached. */
        WILL_NOT_LOAD,
        /** The binary runs and answers --version. */
        RUNS,
    }

    data class AptProbeResult(
        val state: AptProbe,
        /** Raw stdout+stderr, always populated, so a caller never has to invent a reason. */
        val output: String,
        val exitCode: Int,
    )

    /**
     * Probe apt, honestly and with the same environment apt actually needs.
     *
     * THE DEFECT THIS FIXES, from issue 30. The old probe ran
     * `apt-get --version` with no APT_CONFIG. apt cannot parse a configuration
     * that points at a com.termux directory it cannot read, so it exits 100 and
     * prints "Unable to determine a suitable packaging system type" — and the
     * probe read exit 100 as "the binary is missing". The binary was fine; the
     * probe was measuring its own missing environment.
     *
     * Two things are true and both are now handled: apt needs APT_CONFIG to be
     * able to answer at all, and a probe must report the real output rather than
     * collapse a failure into a fixed string.
     */
    fun probeApt(): AptProbeResult {
        val aptGet = File(binDir, "apt-get")
        if (!aptGet.exists() || !aptGet.canRead()) {
            return AptProbeResult(AptProbe.ABSENT, "apt-get is not present in ${binDir.absolutePath}", -1)
        }
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf").absolutePath
        val env = buildEnv()
        // apt resolves its config from this before anything else. Without it the
        // probe fails for a reason that has nothing to do with the binary.
        env["APT_CONFIG"] = aptConfig
        val result = execWithEnv(listOf(linker, aptGet.absolutePath, "--version"), env)
        val combined = (result.stdout + "\n" + result.stderr).trim()
        return when {
            result.exitCode == 0 && result.stdout.contains("apt") ->
                AptProbeResult(AptProbe.RUNS, combined, result.exitCode)
            else ->
                AptProbeResult(AptProbe.WILL_NOT_LOAD, combined.ifBlank { "apt-get produced no output" }, result.exitCode)
        }
    }

    /** Convenience for callers that only need the yes/no. */
    fun aptRuns(): Boolean = probeApt().state == AptProbe.RUNS

    /**
     * Run `apt update` and return the raw result.
     *
     * Separate from [execAptInstall] so a diagnosis path can report exactly what
     * apt said without implying an install happened, and so a caller can tell
     * "cannot reach the repository" from "cannot parse its own config" by
     * reading real output rather than a boolean.
     */
    fun execAptUpdate(): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found", success = false)
        }
        val prefix = usrPrefix.absolutePath
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf").absolutePath
        val script = "export APT_CONFIG='$aptConfig'; " +
            "export PATH='${binDir.absolutePath}:/system/bin'; " +
            "apt-get -o Dir::Bin::dpkg='$prefix/bin/dpkg' " +
            "-o Dir::Bin::methods='$prefix/lib/apt/methods' update 2>&1"
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", script))
    }

    /**
     * Whether a binary of this name EXISTS in the search path.
     *
     * Existence only. Issue 175 named the cost precisely: termuxStatus reported
     * `bash: true` while bash could not run at all, because this question was
     * being answered where "does it work" was being asked. Use [probeCommand]
     * for that; this stays for callers that genuinely mean "is the file there"
     * and for the install tests that assert a package delivered a name.
     */
    fun hasCommand(name: String): Boolean {
        return resolveBinary(name) != null
    }

    // --- Command execution probe ---

    /**
     * The three distinct things a caller can mean by "is this command here",
     * mirroring [AptProbe] so one vocabulary covers the whole prefix.
     */
    enum class CommandProbe {
        /** No binary of that name in the search path at all. */
        ABSENT,
        /** The file exists but will not run, with its real output attached. */
        WILL_NOT_LOAD,
        /** The binary started and produced output, so it is installed and loadable. */
        RUNS,
    }

    data class CommandProbeResult(
        val state: CommandProbe,
        /** Raw stdout+stderr, always populated, so a caller never has to invent a reason. */
        val output: String,
        val exitCode: Int,
        /** The path that was probed, or null when nothing was found. */
        val path: String? = null,
    )

    /**
     * Probe a command by RUNNING it.
     *
     * THE DEFECT THIS FIXES, from issue 175. `termuxStatus` built its
     * `availableTools` map from [hasCommand], which is `File.exists() &&
     * canRead()`. That is existence, not execution, so it reported `bash: true`
     * for a prefix in which every binary failed to load. A status field that
     * reports a binary as available without running it is not a status field.
     *
     * Modelled on [probeApt], deliberately: it distinguishes ABSENT from
     * WILL_NOT_LOAD from RUNS, and it always attaches the real output so the
     * reason is observed rather than guessed. --version is the argument because
     * nearly every Unix tool answers it.
     *
     * A NON-ZERO EXIT IS NOT BY ITSELF "WILL NOT LOAD". Some tools reject
     * --version and exit non-zero while running perfectly — `ssh` is the common
     * one — and calling those broken would be issue 175's error pointed the
     * other way. So a run counts as RUNS when it produced output and nothing in
     * that output is one of the load failures the issues actually measured:
     * "CANNOT LINK EXECUTABLE" and "needed by main executable" from the linker,
     * "Permission denied" and "Exec format error" from the kernel refusing the
     * exec. A run with no output at all is WILL_NOT_LOAD, because nothing proves
     * the binary started.
     */
    fun probeCommand(name: String): CommandProbeResult {
        val resolved = resolveBinary(name)
            ?: return CommandProbeResult(
                CommandProbe.ABSENT,
                "$name is not present in ${searchPath.joinToString(":") { it.absolutePath }}",
                -1,
                null,
            )
        val processArgs = if (resolved.startsWith("/system/")) {
            listOf(resolved, "--version")
        } else {
            listOf(linker, resolved, "--version")
        }
        val result = execWithEnv(processArgs)
        val combined = (result.stdout + "\n" + result.stderr).trim()

        // The exact shapes the issues measured when a binary could not be
        // loaded: the linker naming a missing NEEDED library, and the kernel
        // refusing the exec of a file the loader could not read.
        val loadFailureMarkers = listOf(
            "CANNOT LINK EXECUTABLE",
            "not found: needed by main executable",
            "Permission denied",
            "Exec format error",
            "No such file or directory",
        )
        val refusedToLoad = result.exitCode < 0 ||
            loadFailureMarkers.any { combined.contains(it, ignoreCase = true) }

        return when {
            refusedToLoad -> CommandProbeResult(CommandProbe.WILL_NOT_LOAD, combined, result.exitCode, resolved)
            combined.isNotEmpty() -> CommandProbeResult(CommandProbe.RUNS, combined, result.exitCode, resolved)
            else -> CommandProbeResult(
                CommandProbe.WILL_NOT_LOAD,
                "$name produced no output and exited ${result.exitCode}",
                result.exitCode,
                resolved,
            )
        }
    }

    /**
     * Apply the exact file modes the prefix needs, across the WHOLE tree.
     *
     * THE DEFECT THIS FIXES, from issue 175. The old pass walked bin/ and lib/
     * only and used [File.setExecutable] / [File.setReadable] with the
     * ownerOnly flag, which can only ADD the owner bit to the mode the zip
     * extraction left behind. What that produces on device is 0711 —
     * `-rwx--x--x` — which gives execute without read. The dynamic linker must
     * READ an ELF's header and program headers before mapping it, so every
     * binary in the prefix failed with "Permission denied" before it started,
     * and the issue measured exactly that on all 400 of them.
     *
     * [android.system.Os.chmod] sets the mode rather than OR-ing bits into it,
     * which is the only way to guarantee 0755 from a 0711 start. Directories
     * need the execute bit to be traversable at all, libraries need no execute
     * bit, and everything else gets a readable mode. The whole prefix is covered,
     * not just bin/ and lib/, because libexec/, share/ and the helper scripts
     * under them are executed too.
     */
    private fun applyPrefixPermissions() {
        var binaries = 0
        var libraries = 0
        var directories = 0
        var others = 0
        var failed = 0

        // The directories must be traversable before anything inside them can
        // be reached, so they are fixed first and walked separately.
        usrPrefix.walkTopDown().forEach { entry ->
            if (!entry.isDirectory) return@forEach
            if (setMode(entry, MODE_DIRECTORY)) directories++ else failed++
        }

        usrPrefix.walkTopDown().forEach { entry ->
            if (entry.isDirectory) return@forEach
            if (!entry.isFile) return@forEach
            // Skip symlinks: chmod on a link follows the target, and the link
            // itself carries no mode of its own worth setting.
            if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return@forEach
            val name = entry.name
            val mode = when {
                name.endsWith(".so") || name.contains(".so.") -> MODE_LIBRARY
                entry.parentFile?.name == "bin" || entry.parentFile?.name == "sbin" -> MODE_BINARY
                looksExecutable(name) -> MODE_BINARY
                else -> MODE_READABLE_FILE
            }
            val ok = setMode(entry, mode)
            when {
                !ok -> failed++
                // Compared by what was DECIDED, not by the mode value: 0644 for a
                // library and 0644 for a data file are the same number, so a
                // `when (mode)` would count every library as "other".
                mode == MODE_BINARY -> binaries++
                name.endsWith(".so") || name.contains(".so.") -> libraries++
                else -> others++
            }
        }

        Log.d(
            TAG,
            "TermuxProvider: prefix modes set — $directories dirs, $binaries binaries (0755), " +
                "$libraries libraries (0644), $others files (0644), $failed refused",
        )
    }

    /**
     * A file with no extension in a package dir, or a `.sh`, is a program the
     * payload expects to execute. Everything else is data and gets 0644.
     */
    private fun looksExecutable(name: String): Boolean {
        if (name.endsWith(".sh")) return true
        if (name.contains('.')) return false
        return name.isNotEmpty()
    }

    /** chmod through android.system.Os, matching the Os.symlink style used here. */
    private fun setMode(file: File, mode: Int): Boolean = try {
        android.system.Os.chmod(file.absolutePath, mode)
        true
    } catch (e: Exception) {
        Log.w(TAG, "TermuxProvider: chmod ${file.absolutePath} failed: ${e.message}")
        false
    }

    // --- Private execution helpers ---

    /**
     * Search for a binary across all known PATH directories.
     * Returns absolute path or null.
     */
    private fun resolveBinary(name: String): String? {
        for (dir in searchPath) {
            val f = File(dir, name)
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        return null
    }

    /**
     * Shared ProcessBuilder execution with the standard Termux environment.
     *
     * `extraEnv` overrides are applied last so a caller can adjust one variable
     * for one call without rebuilding the environment. The probe path uses this
     * for APT_CONFIG before the file exists, and nothing else should need it.
     *
     * Issue 44: a program that refuses on stdout and exits 0 must not be
     * reported as a success. Termux binaries run at this app's UID, so a
     * permission-class denial here is the permanent APP_UID wall the tier
     * describes; the exit code and both streams are preserved as observed.
     */
    private fun execWithEnv(
        cmd: List<String>,
        extraEnv: Map<String, String> = emptyMap(),
    ): ShellResult {
        val first = execOnce(cmd, buildEnv(), extraEnv)

        /*
         * SIGSEGV MEANS THE PRELOAD CHAIN KILLED IT, so try again without it.
         *
         * THE MEASUREMENT THIS IS BUILT ON, from the handset on issue 317. Every
         * binary exited 139 through the app's exec path, and the operator got the
         * same binaries to print their versions with a wrapper of his own:
         *
         *   LD_LIBRARY_PATH=<prefix>/lib  PREFIX=<prefix>
         *   /system/bin/linker64 <prefix>/bin/bash -c 'echo alive'
         *
         * That is the SAME launch the app already performs — linker64, the same
         * LD_LIBRARY_PATH, the same PREFIX — with one difference: it sets no
         * LD_PRELOAD. The app sets two libraries there, libtermux-exec and
         * libpathshim, and both are injected into every process. A library that
         * faults inside the linker before main() is reached produces exactly
         * 128+SIGSEGV with no output, which is precisely the 139 and empty stderr
         * of the report.
         *
         * So the fallback is the operator's own working invocation, run
         * automatically. A first result of 139 is retried once with LD_PRELOAD
         * removed; if that produces output, that is the answer and the caller
         * never learns the preload was the problem — which is right, because from
         * the caller's side the binary either works or it does not.
         *
         * WHY NOT JUST DROP THE PRELOADS. libpathshim is load-bearing for a
         * compiled-in com.termux path: figlet's font directory is the smallest
         * case, and apt's config tree is the large one. libtermux-exec routes
         * app-data execs through linker64, which is what lets a script with a
         * shebang run at all. Removing them permanently would trade one broken
         * behaviour for another. Trying WITH them first, and falling back only on
         * a hard fault, keeps both: the shim works wherever it is safe, and a
         * device where it cannot load still has a working prefix.
         *
         * The fallback is recorded on the result rather than hidden, so a caller
         * that wants to know the prefix is running degraded can ask.
         */
        if (looksLikeThePreloadFailed(first)) {
            val withoutPreloads = execOnce(cmd, buildEnv(), extraEnv, stripPreloads = true)
            if (!looksLikeThePreloadFailed(withoutPreloads)) {
                return withoutPreloads
            }
            // Both shapes failed the same way, so the preload was not the cause
            // and the original result is the honest one to report.
            return first
        }
        return first
    }

    /**
     * The two shapes a preload fault takes, both measured on the handset.
     *
     *  - EXIT 139 (SIGSEGV). apt, git and curl all reported this. The process
     *    dies inside the linker, before main(), which is why stderr is empty.
     *
     *  - EXIT 0 WITH NO OUTPUT AT ALL. bash reported exactly this: "bash
     *    produced no output and exited 0". A binary that exits cleanly and says
     *    nothing did not run its own code, and treating that as success is how a
     *    broken tool gets called working.
     *
     * Both mean "the launch was refused", and both are retried without
     * LD_PRELOAD. A tool that legitimately prints nothing and exits 0 — a `true`
     * or a silent write — pays one extra process here, which is a fair price for
     * a prefix that works.
     */
    private fun looksLikeThePreloadFailed(result: ShellResult): Boolean =
        result.exitCode == EXIT_SIGSEGV ||
            (result.exitCode == 0 && result.stdout.isBlank() && result.stderr.isBlank())

    /**
     * One launch, with the preload set optionally stripped.
     *
     * Split out of [execWithEnv] so the fallback is the same code path rather than
     * a second copy of the ProcessBuilder plumbing that can drift from it.
     */
    private fun execOnce(
        cmd: List<String>,
        baseEnv: Map<String, String>,
        extraEnv: Map<String, String>,
        stripPreloads: Boolean = false,
    ): ShellResult {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().putAll(baseEnv)
            pb.environment().putAll(extraEnv)
            if (stripPreloads) {
                // Only LD_PRELOAD goes. The shim's own variables are harmless
                // without the library, and removing them would be a second change
                // inside what is meant to be a single-variable test.
                pb.environment().remove("LD_PRELOAD")
            }
            pb.directory(homeDir)
            pb.redirectErrorStream(false)

            val proc = pb.start()
            /*
             * Drain BOTH streams CONCURRENTLY, before waitFor().
             *
             * Reading stdout to EOF first blocks forever if the child fills the
             * stderr pipe buffer, and a tool printing a screenful to stderr
             * reaches that easily. Each stream gets its own thread so both keep
             * draining while the child runs, and the exit code is only read once
             * neither can still be writing.
             */
            val stdoutText = StringBuilder()
            val stderrText = StringBuilder()
            val drainStdout = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { stdoutText.append(it.readText()) }
                }
            }
            val drainStderr = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(proc.errorStream)).use { stderrText.append(it.readText()) }
                }
            }
            drainStdout.start()
            drainStderr.start()
            val exitCode = proc.waitFor()
            drainStdout.join()
            drainStderr.join()

            // The concurrent drain above produces stdoutText/stderrText, so those are
            // the streams this result carries. The denial check then runs on them: a
            // Termux binary runs at this app's UID, so a permission-class refusal
            // printed on stdout with exit 0 is a failure, not a success.
            ShellDenialSignatures.apply(
                ShellResult(
                    exitCode = exitCode,
                    stdout = stdoutText.toString().trim(),
                    stderr = stderrText.toString().trim(),
                    success = exitCode == 0,
                    tier = ShellTier.APP_UID,
                ),
                ShellTier.APP_UID,
            )
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to execute: ${e.message}",
                success = false,
                tier = ShellTier.APP_UID,
            )
        }
    }

    // --- Private helpers ---

    /**
     * Parse a command string respecting single and double quotes.
     * "bash -c 'echo hello'" becomes ["bash", "-c", "echo hello"].
     */
    private fun parseCommand(raw: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\\' && i + 1 < raw.length -> {
                    current.append(raw[i + 1])
                    i++
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) parts.add(current.toString())

        return parts
    }

    /**
     * Create versioned library symlinks so the ELF loader can find them.
     * libreadline.so.8.3 -> libreadline.so.8
     * libncursesw.so.6.5 -> libncursesw.so.6
     * libhistory.so.8.3 -> libhistory.so.8
     * etc.
     */
    private fun createLibrarySymlinks() {
        if (!libDir.exists()) return

        var linkCount = 0
        libDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name

            // Three-segment versions (libbz2.so.1.0.8) deserve TWO more forms:
            // the two-segment name Termux ELF binaries link against
            // (libbz2.so.1.0), AND the plain major (libbz2.so.1).
            val fullVersionPattern = Regex("""^(.+\.so)\.(\d+)\.(\d+)\.(\d+.*)$""")
            fullVersionPattern.find(name)?.let { match3 ->
                val baseName3 = match3.groupValues[1]
                val majorOnly = "$baseName3.${match3.groupValues[2]}"
                val majorMinor = "$baseName3.${match3.groupValues[2]}.${match3.groupValues[3]}"
                for (variant in listOf(majorOnly, majorMinor)) {
                    val linkFile = File(libDir, variant)
                    if (!linkFile.exists()) {
                        try {
                            android.system.Os.symlink(name, linkFile.absolutePath)
                            linkCount++
                        } catch (_: Exception) {}
                    }
                }
                if (!File(libDir, baseName3).exists()) {
                    try {
                        android.system.Os.symlink(name, File(libDir, baseName3).absolutePath)
                        linkCount++
                    } catch (_: Exception) {}
                }
                return@forEach
            }

            // Match: libfoo.so.MAJOR.MINOR or libfoo.so.MAJOR.MINOR.PATCH
            val versionedPattern = Regex("""^(.+\.so)\.(\d+)\.(\d+.*)$""")
            val match = versionedPattern.find(name) ?: return@forEach
            val baseName = match.groupValues[1]       // libreadline.so
            val major = match.groupValues[2]          // 8
            val majorLink = "$baseName.$major"        // libreadline.so.8

            val majorLinkFile = File(libDir, majorLink)
            if (!majorLinkFile.exists()) {
                try {
                    android.system.Os.symlink(name, majorLinkFile.absolutePath)
                    linkCount++
                } catch (_: Exception) {}
            }

            // Also create libfoo.so link if it doesn't exist
            val plainLinkFile = File(libDir, baseName)
            if (!plainLinkFile.exists()) {
                try {
                    android.system.Os.symlink(name, plainLinkFile.absolutePath)
                    linkCount++
                } catch (_: Exception) {}
            }
        }

        if (linkCount > 0) {
            Log.d(TAG, "TermuxProvider: created $linkCount library symlinks in ${libDir.absolutePath}")
        }
    }

    /**
     * Copy bundled Termux library .so files and patchelf from APK assets into termux/.
     * After extraction, patches all native RE binaries' RPATH so the linker
     * searches our library directories without needing env vars or linker flags.
     */
    private fun copyBundledLibs() {
        val assetDir = "termux-libs"
        val libs: List<String> = try {
            context.assets.list(assetDir)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: failed to list bundled libs: ${e.message}")
            emptyList()
        }

        if (libs.isEmpty()) {
            Log.d(TAG, "TermuxProvider: no bundled libs in assets/$assetDir")
            return
        }

        var count = 0
        for (filename in libs) {
            // patchelf goes in bin/ so it's on PATH for patchElfBinaries()
            val dest = if (filename == "patchelf") {
                File(binDir, filename)
            } else {
                File(libDir, filename)
            }

            if (dest.exists() && dest.length() > 0) continue

            try {
                context.assets.open("$assetDir/$filename").use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                dest.setReadable(true, false)
                if (filename == "patchelf") {
                    dest.setExecutable(true, false)
                }
                count++
            } catch (e: Exception) {
                Log.w(TAG, "TermuxProvider: failed to copy bundled $filename: ${e.message}")
                if (dest.exists()) dest.delete()
            }
        }

        if (count > 0) {
            Log.d(TAG, "TermuxProvider: copied $count bundled files from assets/termux-libs/")
        }
    }

    /**
     * Repoint every ELF in this prefix at this prefix's library directory.
     *
     * WHAT WAS WRONG (issue 193). The shipped Termux binaries carry a
     * DT_RUNPATH of `/data/data/com.termux/files/usr/lib`, the upstream Termux
     * path, which does not exist inside this app's sandbox. When the linker runs
     * one it searches that dead path and fails with
     *
     *   CANNOT LINK EXECUTABLE "usr/bin/grep": library
     *   "libandroid-support.so" not found: needed by main executable
     *
     * even though the library is sitting in this prefix's lib/. Only six
     * binaries were ever repointed — the three RE tools, bash, git and
     * git-remote-http — so roughly four hundred others still pointed at a path
     * that cannot exist.
     *
     * THE ANSWER HERE IS THE CHEAP ONE, DELIBERATELY. No new shim and no new
     * dependency: the same patchelf-through-linker64 invocation the RE tools and
     * the git runtime already use, applied to the whole of usr/bin and
     * usr/libexec. buildEnv's LD_PRELOAD path shim covers compiled-in CONSTANT
     * paths at the libc boundary, and LD_LIBRARY_PATH plus the linker's own
     * search already covers the unpatched case at this prefix when a process is
     * launched from it; this pass is what makes a binary launched any other way,
     * including from a raw shell, resolve its NEEDED libraries.
     *
     * Honest about what it skipped: every non-ELF, every ELF it could not patch
     * and why, and the total. A pass that silently skipped half the tree would
     * be the same kind of claim this whole issue is about.
     *
     * Marker-gated, because it is several hundred processes and nothing about
     * the answer changes until the prefix does.
     */
    private fun patchElfBinaries() {
        val marker = File(usrPrefix, RPATH_SWEEP_MARKER)
        val recorded = runCatching { marker.readText() }.getOrNull()
        if (recorded != null && recorded.contains("version=$RPATH_SWEEP_VERSION")) {
            Log.d(TAG, "TermuxProvider: RPATH sweep v$RPATH_SWEEP_VERSION already done; skipping")
            return
        }

        val patchelfBin = File(binDir, "patchelf")
        if (!patchelfBin.exists() || !patchelfBin.canExecute()) {
            Log.w(TAG, "TermuxProvider: patchelf not available, skipping ELF RPATH patching")
            return
        }

        // RE tools live outside the prefix, so they are covered here as well as
        // the prefix tree; the git runtime's helper is reached through libexec.
        val rpathForPrefixBinaries = libDir.absolutePath
        val rpathForReTools = "${libDir.absolutePath}:${reToolsDir.absolutePath}/lib"

        var patched = 0
        var notElf = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        // The RE tools first: they are the ones most callers reach for.
        for (bin in listOf(File(reToolsDir, "aapt"), File(reToolsDir, "aapt2"), File(reToolsDir, "zipalign"))) {
            if (!bin.exists() || !bin.canRead()) continue
            when (val outcome = runPatchelf(patchelfBin, rpathForReTools, bin)) {
                PatchelfOutcome.PATCHED -> patched++
                PatchelfOutcome.NOT_ELF -> notElf++
                is PatchelfOutcome.REFUSED -> {
                    skipped++
                    failures.add("${bin.absolutePath}: ${outcome.detail}")
                }
            }
        }

        // Every ELF the prefix actually executes: usr/bin and usr/libexec. A
        // data file cannot be patched and is counted, not complained about.
        for (tree in listOf(binDir, File(usrPrefix, "libexec"), File(usrPrefix, "sbin"))) {
            if (!tree.isDirectory) continue
            tree.walkTopDown().forEach { entry ->
                if (!entry.isFile) return@forEach
                if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return@forEach
                // patchelf is a tool we run, not a target; patching it while it is
                // about to be exec'd is a needless risk.
                if (entry == patchelfBin) return@forEach
                if (!entry.canRead()) {
                    skipped++
                    failures.add("${entry.absolutePath}: not readable")
                    return@forEach
                }
                when (val outcome = runPatchelf(patchelfBin, rpathForPrefixBinaries, entry)) {
                    PatchelfOutcome.PATCHED -> patched++
                    PatchelfOutcome.NOT_ELF -> notElf++
                    is PatchelfOutcome.REFUSED -> {
                        skipped++
                        failures.add("${entry.absolutePath}: ${outcome.detail}")
                    }
                }
            }
        }

        // First few failures only: a broken patchelf fails on hundreds of files
        // and one hundred identical log lines hides the pattern instead of
        // showing it.
        failures.take(5).forEach { Log.w(TAG, "TermuxProvider: RPATH sweep skipped $it") }
        if (failures.size > 5) {
            Log.w(TAG, "TermuxProvider: RPATH sweep skipped ${failures.size - 5} more of the same shape")
        }

        // The marker is written only when the sweep genuinely ran to completion
        // over the tree, and records what it achieved so a later pass can tell a
        // complete sweep from an interrupted one.
        if (patched > 0 || (notElf > 0 && skipped == 0)) {
            marker.writeText(
                "version=$RPATH_SWEEP_VERSION;patched=$patched;not_elf=$notElf;skipped=$skipped;rpath=$rpathForPrefixBinaries\n"
            )
        }
        Log.d(
            TAG,
            "TermuxProvider: RPATH sweep v$RPATH_SWEEP_VERSION -> $rpathForPrefixBinaries — " +
                "$patched patched, $notElf not ELF, $skipped skipped",
        )
    }

    /**
     * What happened to one candidate file during the RPATH sweep.
     *
     * A sealed class rather than an enum because the refusal case has to carry
     * the text patchelf actually printed; collapsing that into a fixed string is
     * the same "my probe failed, so I will guess the reason" defect these issues
     * are about.
     */
    private sealed class PatchelfOutcome {
        object PATCHED : PatchelfOutcome()
        object NOT_ELF : PatchelfOutcome()
        data class REFUSED(val detail: String) : PatchelfOutcome()
    }

    /**
     * Run patchelf --set-rpath on one file through the system linker, exactly as
     * the original RE-tool and git passes did.
     *
     * The ELF magic is checked first so a data file under usr/bin costs a
     * four-byte read instead of a process spawn. patchelf itself refuses a
     * non-ELF, but paying a fork for each of several hundred non-binaries is
     * the difference between a pass that completes and one that does not.
     */
    private fun runPatchelf(patchelfBin: File, rpath: String, target: File): PatchelfOutcome {
        val isElf = runCatching {
            target.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
        if (!isElf) return PatchelfOutcome.NOT_ELF

        return try {
            val pb = ProcessBuilder(
                linker, patchelfBin.absolutePath,
                "--set-rpath", rpath,
                target.absolutePath,
            )
            pb.environment().apply {
                put("LD_LIBRARY_PATH", libDir.absolutePath)
            }
            // One pipe, so a chatty patchelf cannot fill stderr while stdout is
            // being drained — the classic waitFor() deadlock. Its failure text is
            // what matters and the stream it chose does not.
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                PatchelfOutcome.PATCHED
            } else {
                PatchelfOutcome.REFUSED(
                    "patchelf exited $exitCode: ${output.trim().take(200)}",
                )
            }
        } catch (e: Exception) {
            PatchelfOutcome.REFUSED("patchelf error: ${e.message}")
        }
    }

    /**
     * Install the bundled Termux git runtime into this prefix.
     *
     * Why the runtime lands here: the git tool family needs a git built with an
     * HTTP transport. Termux's git 2.56.0 links libcurl and ships the real
     * git-remote-http helper, and every library both binaries name as NEEDED
     * (libcurl, libssl, libcrypto, libpcre2-8, libz, libiconv, libnghttp2/3,
     * libngtcp2, libssh2) already exists in this prefix's lib/ from the
     * bootstrap. So the zip carries only bin/git, that one helper, and the
     * clone templates.
     *
     * Idempotent and honest about failure:
     *  - extraction skips any file already present with content, so an
     *    interrupted pass resumes rather than restarting;
     *  - the marker is written only after bin/git has actually executed and
     *    printed a version, so a half-installed tree retries on the next init;
     *  - a missing asset, missing patchelf or a git that will not run leaves
     *    the marker absent and logs what is missing; GitProvider then reports
     *    the absence to the caller instead of pretending a binary exists.
     */
    private fun installGitRuntime() {
        val marker = File(usrPrefix, GIT_RUNTIME_MARKER)
        val gitBinary = File(binDir, "git")

        if (marker.exists() && gitBinary.isFile && gitBinary.length() > 0) return

        val assetPresent = try {
            context.assets.list("git-termux")?.contains("git-termux.zip") == true
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: git-termux asset listing failed: ${e.message}")
            false
        }
        if (!assetPresent) {
            Log.w(TAG, "TermuxProvider: $ASSET_GIT_RUNTIME not bundled; git tools will report it missing")
            return
        }

        // Extract into usr/ so bin/, libexec/ and share/ land at their Termux
        // paths, next to the lib/ this runtime resolves against.
        val tempZip = File(usrPrefix, "git-termux-temp.zip")
        try {
            Log.d(TAG, "TermuxProvider: installing git runtime from $ASSET_GIT_RUNTIME")
            context.assets.open(ASSET_GIT_RUNTIME).use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            }

            var extracted = 0
            ZipFile(tempZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val cleanName = entry.name.removePrefix("./")
                    if (cleanName.isEmpty()) continue

                    val entryFile = File(usrPrefix, cleanName)

                    // Defend the prefix boundary: a crafted entry name must
                    // never escape usr/.
                    val canonicalTarget = entryFile.canonicalFile
                    val canonicalPrefix = usrPrefix.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalPrefix.path + File.separator) &&
                        canonicalTarget.path != canonicalPrefix.path
                    ) {
                        Log.w(TAG, "TermuxProvider: refusing git runtime entry outside prefix: $cleanName")
                        continue
                    }

                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                        continue
                    }

                    // git-remote-https is a symlink in the package and is stored
                    // as one in the zip. Recreate it as a symlink pointing at its
                    // sibling helper; copying the link bytes as a file would
                    // produce a 15-byte "binary" git cannot exec.
                    if (entryFile.name == "git-remote-https") {
                        if (!entryFile.exists()) {
                            entryFile.parentFile?.mkdirs()
                            try {
                                android.system.Os.symlink("git-remote-http", entryFile.absolutePath)
                            } catch (e: Exception) {
                                Log.w(TAG, "TermuxProvider: failed to create git-remote-https symlink: ${e.message}")
                            }
                        }
                        continue
                    }

                    if (entryFile.isFile && entryFile.length() > 0) continue

                    entryFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { entryInput ->
                        FileOutputStream(entryFile).use { entryOutput ->
                            entryInput.copyTo(entryOutput)
                        }
                    }
                    extracted++
                }
            }

            // Execute bits: bin/git itself and every file under libexec/git-core.
            gitBinary.setExecutable(true, false)
            gitBinary.setReadable(true, false)
            File(usrPrefix, "libexec/git-core").walkTopDown().forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                }
            }

            // The package's RUNPATH is /data/data/com.termux/files/usr/lib, which
            // does not exist here. Repoint both ELFs at this prefix's lib/, using
            // the bundled patchelf through the system linker exactly like
            // patchElfBinaries() does.
            val patchelfBin = File(binDir, "patchelf")
            val remoteHttp = File(usrPrefix, "libexec/git-core/git-remote-http")
            if (!patchelfBin.exists() || !patchelfBin.canExecute()) {
                Log.w(TAG, "TermuxProvider: patchelf unavailable; git runtime RPATH left unpatched")
            } else {
                for (binary in listOf(gitBinary, remoteHttp)) {
                    if (!binary.exists() || !binary.canRead()) continue
                    try {
                        val pb = ProcessBuilder(
                            linker, patchelfBin.absolutePath,
                            "--set-rpath", libDir.absolutePath,
                            binary.absolutePath
                        )
                        pb.environment().apply {
                            put("LD_LIBRARY_PATH", libDir.absolutePath)
                        }
                        val proc = pb.start()
                        val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                        val exitCode = proc.waitFor()
                        if (exitCode == 0) {
                            Log.d(TAG, "TermuxProvider: patched git RPATH ${binary.name} -> ${libDir.absolutePath}")
                        } else {
                            Log.w(TAG, "TermuxProvider: patchelf failed on ${binary.name}: $stderr")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "TermuxProvider: patchelf error on ${binary.name}: ${e.message}")
                    }
                }
            }

            // Verify by running it. This is the whole gate: a version string on
            // stdout is the only thing that may write the marker.
            val versionOutput = runGitVersionProbe(remoteHttp)
            if (versionOutput != null) {
                marker.writeText(
                    "tree=$ASSET_GIT_RUNTIME;extracted=$extracted;version=$versionOutput\n"
                )
                Log.d(TAG, "TermuxProvider: git runtime installed and verified ($versionOutput, $extracted files)")
            } else {
                Log.w(TAG, "TermuxProvider: git runtime present but bin/git did not run; marker withheld")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: git runtime install failed: ${e.message}")
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Install the bundled Java and reverse-engineering toolchain (issue 200).
     *
     * WHAT IS INSTALLED, and why each part is where it is:
     *
     *  - `bin/java`, `bin/javac`, `bin/jar`, `bin/jarsigner`, `bin/keytool` are
     *    SYMLINKS into `lib/jvm/java-21-openjdk/bin/`. The Termux package makes
     *    them with update-alternatives from its postinst, and a postinst never
     *    runs here, so the archive carries the links explicitly in its manifest.
     *
     *  - `bin/aapt`, `bin/aapt2`, `bin/zipalign` are the real static-ish Termux
     *    binaries. Their DT_RUNPATH is the upstream com.termux lib path, which
     *    cannot exist inside this sandbox, so they are repointed at this
     *    prefix's lib/ through patchelf exactly like the git runtime.
     *
     *  - `share/java/apktool.jar` plus `bin/apktool`, whose wrapper carries the
     *    upstream libdir in plain text. relocatePrefixPaths rewrites that on the
     *    same pass, which is why this install runs before it.
     *
     * VERIFICATION IS EXECUTION, because that is the entire complaint in issue
     * 200: the old surface reported `jadx=true` from a wrapper file's existence
     * while the JVM behind it was absent. Nothing here is reported as present
     * until it has run, and the marker is withheld otherwise so the next init
     * tries again.
     *
     * Idempotent: extraction skips any file already present with content, so an
     * interrupted pass resumes rather than restarting.
     */
    private fun installReToolchain() {
        val marker = File(usrPrefix, RE_TOOLCHAIN_MARKER)
        val recorded = runCatching { marker.readText() }.getOrNull()
        if (recorded != null && recorded.contains("version=$RE_TOOLCHAIN_VERSION")) {
            Log.d(TAG, "TermuxProvider: toolchain v$RE_TOOLCHAIN_VERSION already installed; skipping")
            return
        }

        val assetPresent = try {
            context.assets.list("re-toolchain")?.contains("re-toolchain.zip") == true
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: re-toolchain asset listing failed: ${e.message}")
            false
        }
        if (!assetPresent) {
            Log.w(TAG, "TermuxProvider: $ASSET_RE_TOOLCHAIN not bundled; the Java toolchain is absent")
            return
        }

        val tempZip = File(usrPrefix, "re-toolchain-temp.zip")
        try {
            Log.d(TAG, "TermuxProvider: installing the Java toolchain from $ASSET_RE_TOOLCHAIN")
            context.assets.open(ASSET_RE_TOOLCHAIN).use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            }

            /*
             * The symlinks are created FIRST and the extraction then skips any
             * path they occupy. Doing it the other way round writes a 32-byte
             * regular file named `java` whose content is the target path, and
             * os.symlink then fails with EEXIST, so the JVM is permanently
             * unreachable while the file looks present. That is precisely the
             * class of defect issue 200 is about.
             */
            val declaredLinks = readToolchainSymlinks(tempZip)
            var linked = 0
            var repaired = 0
            for ((path, target) in declaredLinks) {
                val linkFile = File(usrPrefix, path)
                linkFile.parentFile?.mkdirs()

                /*
                 * A SYMLINK THAT ALREADY EXISTS IS VERIFIED, NOT TRUSTED (issue 292).
                 *
                 * This used to `continue` on any existing symlink, so the links written by the
                 * FIRST install were never re-examined. The manifest's target is written
                 * prefix-relative — `lib/jvm/java-21-openjdk/bin/java` — but the link lives in
                 * `bin/`, so the kernel resolves it to `bin/lib/jvm/...`, which does not exist.
                 * All thirteen JDK tools were dangling for that reason, and no reinstall could
                 * fix them because the second pass skipped them as present.
                 *
                 * The manifest's target is a PREFIX-RELATIVE path, so it is made absolute
                 * against usrPrefix here rather than left for the kernel to reinterpret from
                 * whichever directory the link happens to sit in. An absolute target behaves
                 * identically wherever the link lives and can be checked with readlink.
                 */
                val wanted = File(usrPrefix, target).absolutePath
                val existing = runCatching {
                    android.system.Os.readlink(linkFile.absolutePath)
                }.getOrNull()

                if (existing == wanted) continue
                if (existing != null) {
                    // Wrong target: remove and recreate rather than leaving a dangling link.
                    runCatching { linkFile.delete() }
                    repaired++
                } else if (linkFile.exists()) {
                    // A stale regular file at the link path is the broken artefact described
                    // above: a small text file that looks present and cannot execute.
                    linkFile.delete()
                }
                try {
                    android.system.Os.symlink(wanted, linkFile.absolutePath)
                    linked++
                } catch (e: Exception) {
                    Log.w(TAG, "TermuxProvider: toolchain symlink $path -> $wanted failed: ${e.message}")
                }
            }
            if (repaired > 0) {
                Log.w(TAG, "TermuxProvider: repaired $repaired toolchain symlinks that pointed at a dead relative path (issue 292)")
            }
            if (declaredLinks.isNotEmpty()) {
                Log.d(TAG, "TermuxProvider: toolchain symlinks created ($linked of ${declaredLinks.size})")
            }

            var extracted = 0
            var skippedBecauseLinked = 0
            ZipFile(tempZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val cleanName = entry.name.removePrefix("./")
                    if (cleanName.isEmpty()) continue
                    if (cleanName == RE_TOOLCHAIN_MANIFEST) continue

                    val entryFile = File(usrPrefix, cleanName)

                    // Defend the prefix boundary: a crafted entry name must never
                    // escape usr/. installGitRuntime does the same and for the
                    // same reason.
                    val canonicalTarget = entryFile.canonicalFile
                    val canonicalPrefix = usrPrefix.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalPrefix.path + File.separator) &&
                        canonicalTarget.path != canonicalPrefix.path
                    ) {
                        Log.w(TAG, "TermuxProvider: refusing toolchain entry outside prefix: $cleanName")
                        continue
                    }

                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                        continue
                    }

                    // A declared symlink is already correct on disk, so its zip
                    // entry — which is a regular file holding the target text —
                    // must not overwrite it.
                    if (declaredLinks.containsKey(cleanName)) {
                        skippedBecauseLinked++
                        continue
                    }

                    if (entryFile.isFile && entryFile.length() > 0) continue

                    entryFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { entryInput ->
                        FileOutputStream(entryFile).use { entryOutput ->
                            entryInput.copyTo(entryOutput)
                        }
                    }
                    extracted++
                }
            }
            Log.d(
                TAG,
                "TermuxProvider: toolchain extracted $extracted files " +
                    "($skippedBecauseLinked declared symlinks left alone)",
            )

            // The packaged binaries carry the upstream Termux RUNPATH, which is
            // a dead path inside this app. Repoint them at this prefix's lib/ so
            // they resolve without LD_LIBRARY_PATH, exactly as the git runtime is
            // repointed.
            repointToolchainRpaths()

            // apktool's own wrapper records the upstream libdir in text; without
            // this it looks in a directory that cannot exist. relocatePrefixPaths
            // would do it on the next init anyway, but doing it here means the
            // verification below measures the tool as a user would actually run
            // it rather than a half-relocated one.
            relocateToolchainWrapperText()

            // THE GATE: every tool must RUN. A version string on stdout is the
            // only thing that may write the marker.
            val report = verifyToolchain()
            val allRan = report.values.all { it.first }
            if (allRan) {
                marker.writeText(
                    "version=$RE_TOOLCHAIN_VERSION;" +
                        report.entries.joinToString(";") { "${it.key}=${it.value.second}" } + "\n"
                )
                Log.d(TAG, "TermuxProvider: toolchain installed and verified — $report")
            } else {
                val failures = report.filterValues { !it.first }.keys
                Log.w(
                    TAG,
                    "TermuxProvider: toolchain present but $failures did not run; marker withheld " +
                        "so the next init retries",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: toolchain install failed: ${e.message}")
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Read the symlink map from the archive's manifest.
     *
     * Parsed with kotlinx.serialization rather than org.json: org.json is part of
     * the Android framework but its stub throws in a JVM unit test, and this
     * function is covered by one. The schema is fixed and local to this archive,
     * so a typed model is the honest description of it rather than a bag of
     * optString calls.
     *
     * Returns an empty map when the manifest is absent or unreadable, and says so
     * in the log, because silently installing without the links would put a JVM
     * on disk that nothing can launch and then report it as present.
     */
    /**
     * The archive's manifest, as a typed model.
     *
     * Internal rather than private so a JVM test can hold it against the
     * archive's real entries: the failure this guards is a launcher that arrives
     * as a 32-byte text file, and only a test comparing the two catches that
     * before a handset does.
     */
    @kotlinx.serialization.Serializable
    internal data class ToolchainManifest(
        val schema: Int = 0,
        val jvmHome: String = "",
        val symlinks: List<ToolchainSymlink> = emptyList(),
        val tools: List<ToolchainTool> = emptyList(),
    )

    @kotlinx.serialization.Serializable
    internal data class ToolchainSymlink(val path: String = "", val target: String = "")

    @kotlinx.serialization.Serializable
    internal data class ToolchainTool(
        val name: String = "",
        val probe: List<String> = emptyList(),
        val kind: String = "",
    )

    /**
     * The parsed manifest, or null when it cannot be read.
     *
     * Returns null rather than a partial result so the caller withholds the
     * install marker instead of installing a JVM that nothing can launch. A
     * manifest with no symlinks is that case as well: the launchers would arrive
     * as text files and `java` would resolve to something unexecutable.
     */
    internal fun readToolchainManifest(zip: File): ToolchainManifest? {
        return try {
            val text = ZipFile(zip).use { z ->
                val entry = z.getEntry(RE_TOOLCHAIN_MANIFEST) ?: return null
                z.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            val manifest = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<ToolchainManifest>(text)
            if (manifest.symlinks.isEmpty()) {
                Log.w(TAG, "TermuxProvider: the toolchain manifest declares no symlinks")
                null
            } else {
                manifest
            }
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: could not read the toolchain manifest: ${e.message}")
            null
        }
    }

    private fun readToolchainSymlinks(zip: File): Map<String, String> {
        val manifest = readToolchainManifest(zip) ?: return emptyMap()
        val links = LinkedHashMap<String, String>()
        for (item in manifest.symlinks) {
            if (item.path.isNotEmpty() && item.target.isNotEmpty()) links[item.path] = item.target
        }
        return links
    }

    /**
     * Repoint the toolchain's ELFs at this prefix's lib/.
     *
     * aapt, aapt2, zipalign, the JVM launchers and every JVM .so carry
     * DT_RUNPATH=/data/data/com.termux/files/usr/lib, the upstream path, which
     * cannot exist here. Without this they fail with CANNOT LINK EXECUTABLE the
     * moment the linker looks for a NEEDED library.
     *
     * Only files that are ELF are touched, checked by magic, because patchelf on
     * a text file is a wasted process and on a symlink would follow the link.
     */
    private fun repointToolchainRpaths() {
        val patchelfBin = File(binDir, "patchelf")
        if (!patchelfBin.exists() || !patchelfBin.canExecute()) {
            Log.w(TAG, "TermuxProvider: patchelf unavailable; toolchain RPATH left unpatched")
            return
        }
        val rpath = libDir.absolutePath
        var patched = 0
        var failed = 0

        val roots = listOf(binDir, File(usrPrefix, "lib"))
        for (root in roots) {
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { entry ->
                if (!entry.isFile) return@forEach
                if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return@forEach
                if (entry == patchelfBin) return@forEach
                val isElf = runCatching {
                    entry.inputStream().use { input ->
                        val magic = ByteArray(4)
                        input.read(magic) == 4 && magic[0] == 0x7F.toByte() &&
                            magic[1] == 'E'.code.toByte() &&
                            magic[2] == 'L'.code.toByte() &&
                            magic[3] == 'F'.code.toByte()
                    }
                }.getOrDefault(false)
                if (!isElf) return@forEach

                // The archive's own .so files are already covered by the rpath
                // being this prefix's lib; rewriting several hundred of them
                // costs a process each and changes nothing. Only the executables
                // and the JVM's own objects need it, and an existing correct
                // rpath is indistinguishable from an absent one to patchelf, so
                // the sweep is bounded to bin/ plus the JVM tree.
                val inJvm = entry.absolutePath.contains("/lib/jvm/")
                if (root == File(usrPrefix, "lib") && !inJvm) return@forEach

                when (val outcome = runPatchelf(patchelfBin, rpath, entry)) {
                    PatchelfOutcome.PATCHED -> patched++
                    PatchelfOutcome.NOT_ELF -> {}
                    is PatchelfOutcome.REFUSED -> {
                        failed++
                        if (failed <= 5) {
                            Log.w(TAG, "TermuxProvider: toolchain RPATH skipped ${entry.name}: ${outcome.detail}")
                        }
                    }
                }
            }
        }
        Log.d(TAG, "TermuxProvider: toolchain RPATH sweep -> $rpath ($patched patched, $failed skipped)")
    }

    /**
     * Rewrite the plain-text upstream paths inside the toolchain's wrappers.
     *
     * apktool's `bin/apktool` is a shell script whose libdir is written as
     * /data/data/com.termux/files/usr/share/java. relocatePrefixPaths covers the
     * whole prefix on the same init, but this install runs first and then
     * verifies apktool by running it, so the wrapper has to be correct before
     * that probe rather than after it.
     */
    private fun relocateToolchainWrapperText() {
        val realRoot = termuxRoot.absolutePath
        val candidates = listOf(
            File(binDir, "apktool"),
            File(usrPrefix, "bin/libpng-config"),
            File(usrPrefix, "bin/libpng16-config"),
        )
        for (file in candidates) {
            if (!file.isFile) continue
            if (file.length() > 4L * 1024 * 1024) continue
            val head = runCatching {
                file.inputStream().use { input ->
                    val buf = ByteArray(4)
                    if (input.read(buf) == 4) String(buf, Charsets.ISO_8859_1) else ""
                }
            }.getOrNull() ?: continue
            if (head == "\u007fELF") continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (!text.contains(UPSTREAM_FILES_ROOT)) continue
            val updated = text.replace(UPSTREAM_FILES_ROOT, realRoot)
            if (runCatching { file.writeText(updated) }.isSuccess && updated.startsWith("#!")) {
                file.setExecutable(true, false)
            }
        }
    }

    /**
     * Run every tool in the manifest and report what actually happened.
     *
     * Returns the tool name to a pair of (ran, one-line detail). "Ran" means the
     * process started and printed something that is not one of the linker or
     * kernel refusal shapes the issues measured — the same rule probeCommand
     * uses, so this cannot call a working tool broken or a broken tool working.
     *
     * java is probed FIRST and the rest are skipped when it fails, because aapt
     * does not need the JVM and reporting five failures when the JVM alone is at
     * fault hides which one is the cause.
     */
    private fun verifyToolchain(): Map<String, Pair<Boolean, String>> {
        val results = LinkedHashMap<String, Pair<Boolean, String>>()

        val probes = linkedMapOf(
            "java" to listOf("-version"),
            "aapt" to listOf("version"),
            "aapt2" to listOf("version"),
            "zipalign" to emptyList(),
            "apktool" to listOf("--version"),
        )

        val refusalMarkers = listOf(
            "CANNOT LINK EXECUTABLE",
            "not found: needed by main executable",
            "Permission denied",
            "Exec format error",
            "No such file or directory",
            "command not found",
        )

        for ((name, args) in probes) {
            val file = File(binDir, name)
            val exists = file.exists() && file.canRead()
            if (!exists) {
                results[name] = false to "not present at ${file.absolutePath}"
                continue
            }
            val outcome = runCatching { execWithEnv(listOf(linker, file.absolutePath) + args) }
                .getOrElse { e ->
                    ShellResult(
                        exitCode = -1,
                        stdout = "",
                        stderr = "Failed to execute: ${e.message}",
                        success = false,
                        tier = ShellTier.APP_UID,
                    )
                }
            val combined = (outcome.stdout + "\n" + outcome.stderr).trim()
            val refused = outcome.exitCode < 0 ||
                refusalMarkers.any { combined.contains(it, ignoreCase = true) }
            val ran = !refused && combined.isNotEmpty()
            // One line, always the real output: a caller reading the marker or
            // the log never has to invent a reason.
            val firstLine = combined.lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(160)
                ?: "no output, exit ${outcome.exitCode}"
            results[name] = ran to firstLine
        }
        return results
    }

    /**
     * Run the freshly installed git with --version through the system linker,
     * with the same environment GitProvider uses, and return its version line
     * when it genuinely executed. Null means it did not run.
     */
    private fun runGitVersionProbe(remoteHttp: File): String? {
        val gitBinary = File(binDir, "git")
        if (!gitBinary.exists() || !gitBinary.canRead()) return null
        if (!remoteHttp.exists()) return null

        return try {
            val pb = ProcessBuilder(linker, gitBinary.absolutePath, "--version")
            pb.environment().putAll(buildEnv())
            pb.directory(homeDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exitCode = proc.waitFor()
            val versionLine = output.lineSequence().firstOrNull { it.contains("git version") }?.trim()
            if (exitCode == 0 && versionLine != null) versionLine else null
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: git version probe failed: ${e.message}")
            null
        }
    }

    /**
     * Copy all .so files from termux/lib/ into re-tools/lib/.
     * The Android linker searches the binary's own directory for .so files
     * before looking at RPATH/LD_LIBRARY_PATH. By placing copies alongside
     * the RE binaries we ensure they're found even if RPATH patching fails.
     */
    private fun copyLibsToReToolsDir() {
        val reToolsLibDir = File(reToolsDir, "lib")
        reToolsLibDir.mkdirs()


        var count = 0
        libDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".so") && !file.name.contains(".so.")) return@forEach
            val dest = File(reToolsLibDir, file.name)
            if (dest.exists()) return@forEach
            try {
                file.copyTo(dest)
                dest.setReadable(true, false)
                count++
            } catch (_: Exception) {}
        }

        if (count > 0) {
            Log.d(TAG, "TermuxProvider: copied $count libs to ${reToolsLibDir.absolutePath}")
        }

        /*
         * THE C++ RUNTIME IS THE ONE LIBRARY THAT FLOWS THE OTHER WAY (issue 291).
         *
         * aapt, aapt2 and zipalign are built against a newer libc++ than the Termux bootstrap
         * ships, so they fail with:
         *
         *   cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm"
         *
         * The re-toolchain archive carries a libc++_shared.so that DOES export it. Measured:
         * the re-toolchain copy exports `_ZNSt6__ndk113__hash_memoryEPKvm`, the bootstrap copy
         * does not, and the prefix's lib directory is FIRST on LD_LIBRARY_PATH, so the older
         * one wins and the newer one is never reached.
         *
         * The copy above only ever runs Termux -> re-tools, so the newer library could not get
         * in. This copies it the other way, into the prefix's lib, where the loader looks
         * first. It is a superset: a newer libc++ exports everything the older one did, which
         * is exactly what the version skew means.
         *
         * IT IS COPIED ONLY WHEN THE PREFIX COPY LACKS THE SYMBOL, so a future bootstrap that
         * already carries a new enough library is left alone rather than overwritten.
         */
        /*
         * THE CANDIDATE IS EXTRACTED FROM THE ARCHIVE WHEN IT IS NOT ON DISK.
         *
         * The re-toolchain installer writes its whole tree to reToolsDir, but it is gated on
         * its own marker and may not have run in this process at all. Depending on it would
         * make the C++ runtime's correctness a consequence of install order, which is exactly
         * the kind of hidden coupling this issue is about. Reading the archive entry directly
         * means the newer library is available whatever else has or has not happened.
         */
        /*
         * THE PRESENCE OF THE FILE IS NOT ENOUGH, WHICH IS WHAT MY FIRST ATTEMPT GOT WRONG.
         *
         * re-tools/lib already contains a libc++_shared.so — copied there from the Termux prefix
         * by the loop above, and it is the OLD one. So "if the file is missing, extract it" never
         * fires on a tree that has ever been initialised, and the old library sits in both
         * places. The question is whether the file in hand EXPORTS THE SYMBOL, not whether a
         * file of that name exists.
         */
        val newerCxx = File(reToolsLibDir, "libc++_shared.so")
        val cxxIsNewEnough = runCatching {
            newerCxx.isFile && newerCxx.readBytes().toString(Charsets.ISO_8859_1).contains("__hash_memory")
        }.getOrDefault(false)
        if (!cxxIsNewEnough) {
            extractReToolchainEntry("lib/libc++_shared.so", newerCxx)
        }
        /*
         * AND IF THE ARCHIVE ENTRY COULD NOT BE EXTRACTED, THE PREFIX COPY IS LEFT ALONE.
         * installNewerCxxRuntime checks the candidate for the symbol before touching anything,
         * so a missing candidate is a no-op rather than a half-applied change.
         */
        installNewerCxxRuntime(newerCxx, File(libDir, "libc++_shared.so"))
    }

    /** Pull one entry out of the bundled re-toolchain archive, if the archive is there. */
    private fun extractReToolchainEntry(entry: String, dest: File) {
        runCatching {
            context.assets.open(ASSET_RE_TOOLCHAIN).use { stream ->
                java.util.zip.ZipInputStream(stream).use { zip ->
                    var current = zip.nextEntry
                    while (current != null) {
                        if (current.name == entry) {
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> zip.copyTo(out) }
                            dest.setReadable(true, false)
                            Log.d(TAG, "TermuxProvider: extracted $entry to ${dest.absolutePath}")
                            return
                        }
                        current = zip.nextEntry
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "TermuxProvider: could not read $entry from the re-toolchain: ${it.message}")
        }
    }

    /**
     * Put a libc++ that exports `__hash_memory` where the loader will find it first.
     *
     * [candidate] is the re-toolchain copy; [target] is the prefix's own. Both are read as raw
     * bytes and the check is a byte search for the mangled symbol name, because this runs on
     * Android where `nm` may not be present and spawning a process to inspect a library is a
     * heavy way to answer a question about a string in it.
     */
    private fun installNewerCxxRuntime(candidate: File, target: File) {
        if (!candidate.isFile) return
        val symbol = "__hash_memory"
        val candidateHas = runCatching {
            candidate.readBytes().toString(Charsets.ISO_8859_1).contains(symbol)
        }.getOrDefault(false)
        if (!candidateHas) return

        val targetHas = runCatching {
            target.isFile && target.readBytes().toString(Charsets.ISO_8859_1).contains(symbol)
        }.getOrDefault(false)
        if (targetHas) return

        runCatching {
            target.parentFile?.mkdirs()
            candidate.copyTo(target, overwrite = true)
            target.setReadable(true, false)
            Log.d(TAG, "TermuxProvider: installed a libc++ that exports $symbol into ${target.absolutePath}")
        }.onFailure {
            Log.w(TAG, "TermuxProvider: could not install the newer libc++: ${it.message}")
        }
    }

    /**
     * Write apt configuration so apt works from our custom prefix.
     * Apt is compiled with /data/data/com.termux/files/usr hardcoded.
     * We redirect everything to our actual termuxRoot via an apt.conf.d drop-in.
     *
     * The bootstrap zip already includes a valid sources.list pointing to
     * packages-cf.termux.dev (cloudflare cached). We preserve that and only
     * add our root redirect config.
     */
    private fun initAptConfig() {
        try {
            // Preserve the bootstrap's sources.list — it has the correct URLs.
            // Only write one if it doesn't exist (e.g. bootstrap was already extracted
            // but sources.list got deleted somehow).
            val sourcesList = File(etcDir, "apt/sources.list")
            if (!sourcesList.exists()) {
                sourcesList.parentFile?.mkdirs()
                sourcesList.writeText(
                    "deb https://packages-cf.termux.dev/apt/termux-main/ stable main\n"
                )
            }

            val aptConfDir = File(etcDir, "apt/apt.conf.d")
            aptConfDir.mkdirs()

            /*
             * ALWAYS REWRITTEN, never skipped when present.
             *
             * Two faults lived here. Every line was written without a
             * terminating semicolon, and apt's config parser rejects a bare
             * statement: "Syntax error ... Extra junk at end of file". So the
             * file meant to point apt at this prefix could not be parsed at
             * all, and apt fell back to its compiled-in com.termux defaults.
             *
             * And it was only written when absent, so every tree installed by
             * an earlier build kept the unparseable copy forever. Rewriting
             * unconditionally is one small file and makes the content a
             * property of this build rather than the install date.
             */
            val rootConf = File(aptConfDir, "01-root.conf")
            val prefix = usrPrefix.absolutePath
            rootConf.writeText(
                listOf(
                    "Dir \"$prefix\";",
                    "Dir::State \"$prefix/var/lib/apt\";",
                    "Dir::State::lists \"$prefix/var/lib/apt/lists\";",
                    "Dir::Cache \"$prefix/var/cache/apt\";",
                    "Dir::Log \"$prefix/var/log/apt\";",
                    "Dir::Etc \"${etcDir.absolutePath}/apt\";",
                    "Dir::Etc::sourcelist \"${etcDir.absolutePath}/apt/sources.list\";",
                    "Dir::Etc::sourceparts \"${etcDir.absolutePath}/apt/sources.list.d\";",
                    "Dir::Etc::parts \"${aptConfDir.absolutePath}\";",
                    "Dir::Etc::trustedparts \"${etcDir.absolutePath}/apt/trusted.gpg.d\";",
                    "Dir::Bin::dpkg \"$prefix/bin/dpkg\";",
                    "Dir::Bin::methods \"$prefix/lib/apt/methods\";",
                    "Dir::Bin::gpgv \"$prefix/bin/gpgv\";",
                    // apt delegates repository signature checking to apt-key and
                    // resolves it from this key. Its compiled-in default is the
                    // com.termux path, which does not exist here, so without this
                    // line every repository reads as "not signed" no matter how
                    // correct the keyring is.
                    "Dir::Bin::apt-key \"$prefix/bin/apt-key\";",
                    /*
                     * dpkg's two roots, and both are load-bearing.
                     *
                     * dpkg's compiled-in admin dir is the com.termux path, so
                     * --admindir is mandatory or it fails with "cannot create
                     * the dpkg database directory ... Permission denied".
                     *
                     * --instdir is the TERMUX ROOT, not the prefix and not /.
                     * A Termux package records its payload as
                     * `./data/data/com.termux/files/usr/bin/figlet`. Relative to
                     * instdir=/ that tries to create /data/data/com.termux and
                     * the sandbox denies it; relative to instdir=<prefix> it
                     * lands four directories too deep. Relative to
                     * instdir=<termuxRoot> it resolves through the compatibility
                     * symlink at <termuxRoot>/data/data/com.termux/files/usr
                     * straight into the real prefix. There is no other value
                     * that both satisfies the sandbox and lands the file where
                     * the app can run it.
                     */
                    "DPkg::Options:: \"--admindir=$prefix/var/lib/dpkg\";",
                    "DPkg::Options:: \"--instdir=${termuxRoot.absolutePath}\";",
                    "APT::Sandbox::User \"root\";",
                    "Acquire::https::CaInfo \"${etcDir.absolutePath}/tls/cert.pem\";",
                    "DPkg::Path \"$prefix/bin\";",
                ).joinToString("\n", postfix = "\n")
            )

            // Ensure state directories exist
            File(usrPrefix, "var/lib/apt/lists").mkdirs()
            File(usrPrefix, "var/cache/apt/archives/partial").mkdirs()
            File(usrPrefix, "var/log/apt").mkdirs()

            /*
             * The keyring directory, populated from the keys the bootstrap
             * already ships in share/termux-keyring/. Nothing links them into
             * place: the package postinst that would do it never runs, because
             * a postinst is a script and the shell it needs is the very thing
             * missing. So trusted.gpg.d arrives empty and apt has no key to
             * verify a repository against. Copy what is already on disk instead
             * of trusting an install step that cannot execute.
             */
            val trustedDir = File(etcDir, "apt/trusted.gpg.d")
            trustedDir.mkdirs()
            var keyCount = 0
            File(usrPrefix, "share/termux-keyring").listFiles()?.forEach { key ->
                if (!key.isFile || !key.name.endsWith(".gpg")) return@forEach
                val dest = File(trustedDir, key.name)
                if (dest.exists() && dest.length() == key.length()) return@forEach
                if (runCatching { key.copyTo(dest, overwrite = true) }.isSuccess) {
                    dest.setReadable(true, false)
                    keyCount++
                }
            }
            if (keyCount > 0) {
                Log.d(TAG, "TermuxProvider: installed $keyCount repo signing keys into ${trustedDir.absolutePath}")
            }

            Log.d(TAG, "TermuxProvider: apt config written to ${aptConfDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: apt config init failed: ${e.message}")
        }
    }

    /**
     * Ensure the apt transport method drivers exist at prefix/lib/apt/methods/
     * and that the https variant is present (some bootstraps only ship http).
     * Under the unified layout source and target are the same tree, so the
     * old copy pass reduced to a no-op and only this check remains.
     */
    private fun fixAptMethods() {
        val methodsDir = File(usrDir, "lib/apt/methods")
        methodsDir.mkdirs()

        val httpsMethod = File(methodsDir, "https")
        if (!httpsMethod.exists()) {
            val httpMethod = File(methodsDir, "http")
            if (httpMethod.exists()) {
                try {
                    httpMethod.copyTo(httpsMethod)
                    httpsMethod.setExecutable(true, false)
                    httpsMethod.setReadable(true, false)
                    Log.d(TAG, "TermuxProvider: created https apt method from http method")
                } catch (e: Exception) {
                    Log.w(TAG, "TermuxProvider: failed to create https method: ${e.message}")
                }
            }
        }
    }

    /**
     * Remove old wrapper scripts created by previous versions of TermuxProvider.
     * These wrappers used shebangs pointing to app-private storage which Android
     * SELinux blocks with "bad interpreter: Permission denied". The proper fix
     * is using LD_PRELOAD with libtermux-exec and correct env vars, not wrappers.
     */
    private fun removeOldAptWrappers() {
        val pairs = listOf(
            File(binDir, "apt") to File(binDir, "apt.real"),
            File(binDir, "apt-get") to File(binDir, "apt-get.real"),
            File(binDir, "dpkg") to File(binDir, "dpkg.real")
        )

        for ((wrapper, real) in pairs) {
            // If wrapper is a script (not ELF) and real exists, restore original
            if (real.exists()) {
                if (wrapper.exists()) {
                    wrapper.delete()
                }
                real.renameTo(wrapper)
                wrapper.setExecutable(true, false)
                Log.d(TAG, "TermuxProvider: removed old wrapper, restored ${wrapper.name}")
            }
        }
    }

    // --- Extraction ---

    /*
     * REMOVED: linkUpstreamRootFromRoot().
     *
     * It symlinked /data/data/com.termux into this app's tree via a root shell so
     * that compiled-in paths would resolve. Two reasons it is gone.
     *
     * 1. Nobody roots their phone, so a mechanism that requires root is not a
     *    mechanism. It also cannot work on a device with a real com.termux
     *    install without taking over another app's data path.
     *
     * 2. proot was the obvious unrooted alternative and it is also dead here:
     *    Android's seccomp filter blocks ptrace for ordinary apps, so proot
     *    cannot execve even /system/bin/sh. Measured, not assumed.
     *
     * The replacement is libpathshim.so, loaded through LD_PRELOAD, which
     * rewrites a leading upstream prefix at the libc boundary. It needs no root,
     * no adb and no ptrace, and it covers every package at once. See pathshim.c.
     */

    /**
     * Build the compatibility root that lets dpkg install upstream-prefixed
     * packages into THIS prefix.
     *
     * WHY THIS IS NEEDED, and why it is structural rather than a workaround.
     * A Termux `.deb` records its payload paths absolute to its own prefix:
     *
     *   ./data/data/com.termux/files/usr/bin/figlet
     *
     * dpkg unpacks those relative to its instdir. Every choice of instdir is
     * therefore wrong on its own:
     *
     *   --instdir=/          -> tries to create /data/data/com.termux, denied
     *                           by the sandbox (that path belongs to another app)
     *   --instdir=<prefix>   -> creates <prefix>/data/data/com.termux/files/usr/
     *                           bin/figlet, four directories too deep, and apt
     *                           still reports success because dpkg exited 0
     *
     * The missing piece is a mapping between the two layouts. This creates the
     * nested directory chain `<termuxRoot>/data/data/com.termux/files` and one
     * symlink `.../files/usr -> <termuxRoot>/usr`. With that root in place,
     * dpkg's instdir is the termux root itself and a package path of
     * `data/data/com.termux/files/usr/bin/figlet` resolves through the symlink
     * to exactly `<termuxRoot>/usr/bin/figlet`, which is the real prefix.
     *
     * This is the same idea as the /lib64 symlink on a multiarch system: one
     * fixed, documented mapping so foreign-prefixed content resolves into this
     * tree. It is built once at init, is idempotent, and only ever creates
     * directories and a single symlink inside our own storage.
     */
    private fun ensureCompatibilityRoot() {
        try {
            val upstreamUsr = "$UPSTREAM_FILES_ROOT/usr"          // /data/data/com.termux/files/usr
            val upstreamFiles = UPSTREAM_FILES_ROOT                // /data/data/com.termux/files
            val rel = upstreamFiles.removePrefix("/")              // data/data/com.termux/files
            val filesDirInRoot = File(termuxRoot, rel)
            filesDirInRoot.mkdirs()

            /*
             * readlink, NOT exists(), and the difference is a real defect.
             *
             * File.exists() FOLLOWS a symlink, so it answers "is the target
             * reachable", not "is the mapping correct". Two ways that went wrong
             * and both were measured:
             *
             *  - A directory left at this path by an earlier install reported
             *    true, so the symlink was never created. dpkg then wrote a whole
             *    parallel tree at <termuxRoot>/data/data/com.termux/files/usr,
             *    reported the packages installed, and not one binary reached the
             *    prefix. `apt install cowsay` exited 0 and `cowsay` was not
             *    there.
             *  - A dangling symlink reported false and was left dangling, so the
             *    same fault recurred on every init.
             *
             * readlink answers what this code actually needs: is the entry a
             * symlink, and does it point where the mapping requires.
             */
            val usrLink = File(filesDirInRoot, "usr")
            val wanted = File(usrPrefix, "").absolutePath.trimEnd('/')
            val existingTarget = runCatching {
                android.system.Os.readlink(usrLink.absolutePath)
            }.getOrNull()

            if (existingTarget != wanted) {
                if (existingTarget == null && usrLink.exists()) {
                    // A real directory or file occupies the path. It cannot be a
                    // working mapping, because dpkg writes THROUGH this path into
                    // the prefix; left in place it silently swallows installs.
                    Log.w(
                        TAG,
                        "TermuxProvider: compatibility root holds a real directory at " +
                            "${usrLink.absolutePath}, replacing it with the symlink",
                    )
                    usrLink.deleteRecursively()
                } else if (existingTarget != null) {
                    usrLink.delete()
                }
                try {
                    android.system.Os.symlink(wanted, usrLink.absolutePath)
                } catch (e: Exception) {
                    Log.w(TAG, "TermuxProvider: compatibility root symlink failed: ${e.message}")
                }
            }
            // Verified by readlink so the log reports the MAPPING, not whether
            // its target happens to be reachable at this instant.
            val ok = runCatching {
                android.system.Os.readlink(usrLink.absolutePath) == wanted
            }.getOrDefault(false)
            Log.d(
                TAG,
                "TermuxProvider: compatibility root ${if (ok) "ready" else "INCOMPLETE"} — " +
                    "${usrLink.absolutePath} -> ${usrPrefix.absolutePath} (upstream $upstreamUsr)",
            )
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: compatibility root failed: ${e.message}")
        }
    }

    /**
     * Rewrite every TEXT file in the prefix that names the upstream com.termux
     * root, so it names THIS prefix instead.
     *
     * WHY THIS EXISTS. The bundled payload was built for Termux's own package
     * id, and it records that path in text in four places: script shebangs
     * (`#!/data/data/com.termux/files/usr/bin/sh`), dpkg's status and its 96
     * .list rows, apt's generated helper scripts, and the keyring postinst
     * paths. None of those paths exist inside this app's sandbox.
     *
     * The cost of leaving them: apt delegates repository signature checking to
     * `apt-key`, apt-key is a shell script, the kernel resolves its shebang to
     * `/data/data/com.termux/files/usr/bin/sh`, that path does not exist, the
     * exec fails, and apt reports "Couldn't execute apt-key" followed by "not
     * signed". Every search for a keyring or GPG fault is then a search in the
     * wrong place: the keyring is fine, the signature is fine, the interpreter
     * path is dead.
     *
     * The mapping that matters:
     *
     *   upstream  /data/data/com.termux/files/usr/bin/sh
     *   ours      <filesDir>/termux/usr/bin/sh
     *
     * The upstream `files` segment corresponds to OUR `termux` directory, so
     * the replacement for the upstream root is the termux root, NOT filesDir.
     *
     * Scope, deliberately narrow: TEXT files only. ELF binaries are the
     * linker's business and rewriting bytes inside one corrupts it, so anything
     * beginning with \x7fELF is skipped, along with anything above 4 MiB.
     *
     * Idempotent and version-gated: the marker records the pass version, so a
     * change to these rules re-runs the pass on an installed tree.
     */
    private fun relocatePrefixPaths() {
        try {
            val marker = File(usrPrefix, ".prefix-relocated")
            val current = runCatching { marker.readText() }.getOrNull()
            if (current != null && current.contains("version=$PREFIX_RELOCATION_VERSION")) return

            val realRoot = termuxRoot.absolutePath
            var rewritten = 0
            var scanned = 0
            var elfSkipped = 0

            usrPrefix.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name == ".prefix-relocated") return@forEach
                if (file.length() > 4L * 1024 * 1024) return@forEach

                val head = runCatching {
                    file.inputStream().use { input ->
                        val buf = ByteArray(4)
                        if (input.read(buf) == 4) String(buf, Charsets.ISO_8859_1) else ""
                    }
                }.getOrNull() ?: return@forEach

                if (head == "\u007fELF") {
                    elfSkipped++
                    return@forEach
                }

                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                scanned++
                if (!text.contains(UPSTREAM_FILES_ROOT)) return@forEach

                val updated = text.replace(UPSTREAM_FILES_ROOT, realRoot)
                val wrote = runCatching { file.writeText(updated) }
                if (wrote.isSuccess) {
                    rewritten++
                    if (updated.startsWith("#!")) file.setExecutable(true, false)
                } else {
                    Log.w(TAG, "TermuxProvider: relocate failed for ${file.name}")
                }
            }

            marker.writeText(
                "version=$PREFIX_RELOCATION_VERSION;root=$realRoot;" +
                    "scanned=$scanned;rewritten=$rewritten;elf_skipped=$elfSkipped"
            )
            Log.d(
                TAG,
                "TermuxProvider: prefix relocation v$PREFIX_RELOCATION_VERSION — " +
                    "$rewritten of $scanned text files rewritten to $realRoot " +
                    "($elfSkipped ELF files left to the linker)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: prefix relocation failed (non-fatal): ${e.message}")
        }
    }

    private fun extractBootstrap(): Boolean {
        val tempZip = File(termuxRoot, "bootstrap_temp.zip")
        try {
            val assetExists = context.assets.list("termux")
                ?.contains("termux-bootstrap.zip") ?: false

            if (!assetExists) {
                Log.w(TAG, "TermuxProvider: termux-bootstrap.zip not bundled in assets")
                return false
            }

            Log.d(TAG, "TermuxProvider: copying bootstrap from assets")
            context.assets.open(ASSET_BOOTSTRAP).use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "TermuxProvider: unzipping bootstrap to ${termuxRoot.absolutePath}")
            ZipFile(tempZip).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val cleanName = entry.name.removePrefix("./")
                    if (cleanName.isEmpty()) continue

                    val entryFile = File(termuxRoot, cleanName)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { entryInput ->
                            FileOutputStream(entryFile).use { entryOutput ->
                                entryInput.copyTo(entryOutput)
                            }
                        }
                        count++
                    }
                }
                Log.d(TAG, "TermuxProvider: extracted $count files")
            }

            // Symlink application now lives in applyBootstrapSymlinks(), which runs
            // AFTER canonicalisation (called from initialize()), because the com.termux
            // translate-to-prefix branch can only succeed with the final usr/ tree on
            // disk. Extraction stops here.
            Log.d(TAG, "TermuxProvider: extracted all files; symlinks defer to post-canonicalise pass")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: bootstrap extraction failed", e)
            return false
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Apply SYMLINKS.txt from the extracted bootstrap, once, with the final
     * usr/ tree on disk.
     *
     * THE DEFECT THIS FIXES, and it was the root cause of apt being dead.
     * SYMLINKS.txt uses the form `target←link`, with NO spaces around the
     * arrow. This parser split on " ← " (space, arrow, space), matched zero
     * lines, and reported "0 applied this pass" on every install. So none of
     * the bootstrap's 1160 symlinks ever landed.
     *
     * What that cost: the shipped payload is multi-call. `sh`, `mktemp`,
     * `touch`, `sort`, `cut`, `awk`, `id`, `stat`, `expr` and the rest of
     * coreutils are symlinks onto the `coreutils`, `dash` and `gawk` binaries.
     * Without them the prefix has no shell and no `mktemp`. apt shells out to
     * `apt-key` for signature verification, apt-key is a `#!/.../bin/sh`
     * script, and with no `sh` that exec fails — which surfaced as
     * "Couldn't execute apt-key" and "not signed", looking like a keyring
     * fault and being nothing of the sort.
     *
     * Format handling:
     *  a) The arrow is matched with optional surrounding whitespace, so both
     *     `target←link` and the legacy `target ← link` parse.
     *  b) Absolute targets against the upstream com.termux prefix are
     *     translated to OUR prefix — a raw dead absolute symlink would point
     *     outside the app and break the keyring and helper binaries.
     *  c) A link name starting with a root-level package dir lands inside
     *     usr/, because those dirs physically live there post-canonicalise.
     *  d) Relative targets are resolved against the link's own directory and
     *     pinned to an absolute path inside our prefix. The kernel follows the
     *     link from its real location, so an absolute target behaves the same
     *     and is far easier to verify from a log.
     */
    private fun applyBootstrapSymlinks() {
        val symlinksFile = File(termuxRoot, "SYMLINKS.txt")
        if (!symlinksFile.exists()) return
        val packageDirs = setOf(
            "bin", "lib", "etc", "share", "var", "libexec", "tmp", "opt", "root", "sbin"
        )
        var linkCount = 0
        var alreadyPresent = 0
        var failed = 0

        symlinksFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachLine

            // THE FIX: split on the arrow itself, whitespace optional. The old
            // split required " ← " and matched nothing in the shipped file.
            val arrow = trimmed.indexOf('←')
            if (arrow <= 0) return@forEachLine
            val rawTarget = trimmed.substring(0, arrow).trim()
            val rawLink = trimmed.substring(arrow + 1).trim().removePrefix("./")
            if (rawTarget.isEmpty() || rawLink.isEmpty()) return@forEachLine

            val linkSegments = rawLink.split("/")
            val linkPathInsidePrefix = if (linkSegments.firstOrNull() in packageDirs) {
                File(usrPrefix, rawLink).path
            } else {
                File(termuxRoot, rawLink).path
            }

            val upstreamPrefix = "$UPSTREAM_FILES_ROOT/usr/"
            val upstreamRoot = "$UPSTREAM_FILES_ROOT/"
            val resolvedTarget = when {
                rawTarget.startsWith(upstreamPrefix) ->
                    File(usrPrefix, rawTarget.removePrefix(upstreamPrefix)).absolutePath
                rawTarget.startsWith(upstreamRoot) ->
                    File(termuxRoot, rawTarget.removePrefix(upstreamRoot)).absolutePath
                // A target outside the upstream tree is kept as written, whether
                // or not it exists today. Dropping it would silently lose a link
                // the payload expects; a dangling link is visible, a missing one
                // is a mystery.
                rawTarget.startsWith("/") -> rawTarget
                else -> File(File(linkPathInsidePrefix).parentFile, rawTarget).path
            }

            val linkFile = File(linkPathInsidePrefix)
            if (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath())) {
                alreadyPresent++
            } else {
                try {
                    linkFile.parentFile?.mkdirs()
                    android.system.Os.symlink(resolvedTarget, linkFile.absolutePath)
                    linkCount++
                } catch (e: Exception) {
                    failed++
                    if (failed <= 5) {
                        Log.w(TAG, "TermuxProvider: symlink $rawLink -> $resolvedTarget failed: ${e.message}")
                    }
                }
            }
        }

        // Deletion is gated on this pass having produced links, never on a
        // partially-applied one. A pass that linked nothing keeps the manifest
        // so a later canonical pass can retry it.
        if (linkCount > 0 || alreadyPresent > 0) {
            symlinksFile.delete()
            Log.d(
                TAG,
                "TermuxProvider: bootstrap symlinks applied ($linkCount new, " +
                    "$alreadyPresent present, $failed failed); SYMLINKS.txt consumed"
            )
        } else {
            Log.w(TAG, "TermuxProvider: SYMLINKS.txt kept for a later pass ($failed failed this pass)")
        }
    }
}
