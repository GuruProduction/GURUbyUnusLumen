// GURU's Rust brain build.
//
// WHY THIS FILE EXISTS. Until now `libcerebrum_android.so` was a hand-copied
// artefact committed to `app/src/main/jniLibs`. Nothing compiled it, so nothing
// could tell which Cerebrum source was in a release, and three merged fixes
// (entity edges, merge retirement, update history) shipped absent from the APK
// while every test stayed green. This task is the fix: Gradle runs Cargo, Cargo
// builds the current source, and its output is packaged instead of the copy.
//
// WHY A BUILD TASK AND NOT `cargo-ndk`. This repository pins its NDK in
// app/build.gradle.kts (`ndkVersion = "27.0.12077973"`) and its own linker flags
// in cerebrum/.cargo/config.toml, including the 16 KB page-size requirement that
// Android 15+ enforces. A wrapper that resolved the NDK itself would be a second
// source of truth for both. Naming the NDK toolchain directly keeps one.
//
// WHY THE INPUTS ARE DECLARED. Every Rust source, manifest and lockfile under
// cerebrum/ is an input, and the four per-ABI `.so` files are outputs. Touch a
// `.rs` file and the task re-runs; change nothing and it is up-to-date in
// milliseconds. The git revision is an input too, so a commit that changes no
// Rust file still rebuilds the identity embedded in the library.
//
// The output directory is handed to the app module as a source directory, so
// these files are what gets packaged.

import java.io.ByteArrayOutputStream

// The ABIs GURU ships. `verifyCerebrumNativeProvenance` in app/build.gradle.kts
// fails the build if this drifts from `ndk.abiFilters`.
extra["cerebrumAbis"] = linkedMapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

// The NDK the Rust linker must use: one fact, read by the build and by the
// provenance check rather than repeated in both.
extra["cerebrumNdkVersion"] = "27.0.12077973"

// Locate the NDK's clang wrappers. A missing toolchain is fatal rather than
// skipped: a build that quietly omitted the brain would ship a stale one, which
// is exactly the defect this task removes.
fun ndkToolchainBin(projectRoot: File, ndkVersion: String): File {
    val sdkDir = run {
        val localProperties = File(projectRoot, "local.properties")
        val declared = if (localProperties.exists()) {
            java.util.Properties().apply {
                localProperties.inputStream().use { load(it) }
            }.getProperty("sdk.dir")
        } else {
            null
        }
        declared?.takeIf { it.isNotBlank() }
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: throw GradleException(
                "No Android SDK located. Set sdk.dir in local.properties, or ANDROID_HOME, so the " +
                    "Cerebrum Rust build can find NDK $ndkVersion."
            )
    }
    val hostTag = when {
        System.getProperty("os.name").startsWith("Mac") -> "darwin-x86_64"
        System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    val toolchain = File(sdkDir, "ndk/$ndkVersion/toolchains/llvm/prebuilt/$hostTag/bin")
    if (!toolchain.isDirectory) {
        throw GradleException(
            "NDK $ndkVersion toolchain not found at $toolchain. The Cerebrum brain cannot be compiled " +
                "without it, and a build that silently skipped the native library would ship a stale brain."
        )
    }
    return toolchain
}

// The clang wrapper for one Rust target. NDK r27 names them by API level, and 29
// is the level cerebrum/.cargo/config.toml already links against.
fun clangFor(targetTriple: String, toolchainBin: File): File {
    val wrapperName = when (targetTriple) {
        "aarch64-linux-android" -> "aarch64-linux-android29-clang"
        "armv7-linux-androideabi" -> "armv7a-linux-androideabi29-clang"
        "x86_64-linux-android" -> "x86_64-linux-android29-clang"
        "i686-linux-android" -> "i686-linux-android29-clang"
        else -> throw GradleException("Unsupported Cerebrum target: $targetTriple")
    }
    val clang = File(toolchainBin, wrapperName)
    if (!clang.isFile) throw GradleException("NDK clang missing for $targetTriple at $clang")
    return clang
}

// Cargo's per-target environment variables upper-case the triple and turn every
// '-' into '_'.
fun cargoEnvSuffix(targetTriple: String): String = targetTriple.uppercase().replace('-', '_')

// Search a binary for a byte sequence. Not in the stdlib, and a dependency for
// four lines of loop would be worse than the loop.
fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > this.size) return false
    outer@ for (start in 0..(this.size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return true
    }
    return false
}

// Where the compiled brain lands. Declared as an extra so the app module and the
// provenance check agree on one path rather than three copies of a string.
val cerebrumOutputDirectory = project.layout.buildDirectory.dir("rustJniLibs")
extra["cerebrumOutputDirectory"] = cerebrumOutputDirectory

@Suppress("UNCHECKED_CAST")
val cerebrumTargets = extra["cerebrumAbis"] as Map<String, String>
val cerebrumNdk = extra["cerebrumNdkVersion"] as String

val buildCerebrumNative = tasks.register("buildCerebrumNative") {
    group = "cerebrum"
    description = "Compile the Rust Cerebrum brain for every ABI GURU ships, from the current source."

    // The input set is deliberately broad. One added `.rs` file must trigger a
    // rebuild, because a stale brain is the defect this task exists to prevent.
    inputs.files(
        rootProject.fileTree("cerebrum") {
            include("**/*.rs", "**/*.toml", "**/*.lock")
            exclude("target/**")
        }
    )
    inputs.property("cerebrumNdkVersion", cerebrumNdk)
    inputs.property("cerebrumAbis", cerebrumTargets.keys.joinToString(","))
    inputs.property("cerebrumRevisionInput", providers.provider {
        // Declared as an input so a new commit re-runs the build even when no Rust
        // file changed, because the revision is compiled into the binary.
        runCatching {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            text.ifBlank { "unknown" }
        }.getOrDefault("unknown")
    })

    // Declared outputs, so Gradle can tell a real build from a cached no-op.
    outputs.files(cerebrumTargets.keys.map { abi ->
        cerebrumOutputDirectory.map { it.file("$abi/libcerebrum_android.so") }
    })
    outputs.cacheIf { true }

    doLast {
        val androidProjectRoot = rootProject.projectDir
        val cerebrumRoot = File(androidProjectRoot, "cerebrum")
        val outputRoot = cerebrumOutputDirectory.get().asFile
        val toolchainBin = ndkToolchainBin(androidProjectRoot, cerebrumNdk)
        val cargoCommand = System.getenv("CARGO")?.takeIf { it.isNotBlank() } ?: "cargo"

        // The revision baked into the library. `git rev-parse HEAD` covers the
        // normal case; a source export with no .git still builds and reports
        // "unknown", which is honest about what it cannot prove.
        val revision = runCatching {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(androidProjectRoot)
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            text.ifBlank { "unknown" }
        }.getOrDefault("unknown")

        outputRoot.mkdirs()

        cerebrumTargets.forEach { (abi, targetTriple) ->
            val clang = clangFor(targetTriple, toolchainBin)
            val targetEnvSuffix = targetTriple.replace('-', '_')
            val environment = mapOf(
                "CEREBRUM_BUILD_REV" to revision,
                "CARGO_TARGET_${cargoEnvSuffix(targetTriple)}_LINKER" to clang.absolutePath,
                "CC_$targetEnvSuffix" to clang.absolutePath,
                "AR_$targetEnvSuffix" to File(toolchainBin, "llvm-ar").absolutePath,
                "PATH" to "${toolchainBin.absolutePath}${File.pathSeparator}${System.getenv("PATH").orEmpty()}",
            )

            logger.lifecycle("cerebrum: building $abi ($targetTriple) from $revision")

            val processBuilder = ProcessBuilder(
                cargoCommand, "build",
                "--release",
                "--locked",
                "-p", "cerebrum-android",
                "--target", targetTriple,
            )
            processBuilder.directory(cerebrumRoot)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment().putAll(environment)

            val process = processBuilder.start()
            // Read as it arrives, so a linker failure is visible while it happens
            // rather than appearing as one silent block at the end.
            val captured = ByteArrayOutputStream()
            process.inputStream.bufferedReader().forEachLine { line ->
                captured.write((line + "\n").toByteArray())
                if (line.contains("error", ignoreCase = true) || line.startsWith("warning:")) {
                    logger.lifecycle("cerebrum[$abi] $line")
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException(
                    "Cargo failed for $abi with exit code $exitCode. Last output:\n" +
                        captured.toString().takeLast(4000)
                )
            }

            val builtSo = File(cerebrumRoot, "target/$targetTriple/release/libcerebrum_android.so")
            if (!builtSo.isFile) {
                throw GradleException(
                    "Cargo reported success for $abi but produced no libcerebrum_android.so at $builtSo. " +
                        "That is the shape of a stale artefact being reused, not a build."
                )
            }

            // The marker must be in the file about to be packaged, not merely in
            // some earlier build output. Absent, the binary is not from this
            // revision, and shipping it would repeat the defect this task removes.
            val builtBytes = builtSo.readBytes()
            if (revision != "unknown" &&
                !builtBytes.containsSubsequence("cerebrum-build-rev=$revision".toByteArray())
            ) {
                throw GradleException(
                    "The $abi library does not carry the build marker for revision $revision. Cargo " +
                        "reused a cached artefact instead of rebuilding, so the brain in the APK would " +
                        "not match the source. Re-run with --rerun-tasks for this task."
                )
            }

            val destinationDir = File(outputRoot, abi).apply { mkdirs() }
            builtSo.copyTo(File(destinationDir, "libcerebrum_android.so"), overwrite = true)
            logger.lifecycle(
                "cerebrum: $abi packaged (${builtBytes.size} bytes, rev ${revision.take(12)})"
            )
        }
    }
}

// A named hook so `./gradlew cerebrumNative` builds every ABI without the app.
tasks.register("cerebrumNative") {
    group = "cerebrum"
    description = "Build the Rust Cerebrum brain for every shipped ABI."
    dependsOn(buildCerebrumNative)
}
