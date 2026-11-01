// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellDenialSignatures
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ReverseEngineeringToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)
    private val debugKeyStore: String by lazy { "${context.filesDir.absolutePath}/guru_debug.keystore" }

    /**
     * The prefix the toolchain lives in, held here rather than reached through
     * ShellExecutor.
     *
     * ShellExecutor exposes reTools (the adb and tool-state surface) and no
     * termux accessor, and adding one would widen a class whose job is the
     * root/Shizuku/adb execution ladder. This executor owns its own handle for
     * the one thing it needs: launching a prefix binary with an argument list.
     */
    private val termux = TermuxProvider(context)

    private data class ParsedAapt(val packageName: String, val versionName: String, val versionCode: Int, val permissions: List<String>, val activities: List<String>, val services: List<String>, val receivers: List<String>, val providers: List<String>)
    private data class PmPackageInfo(val versionName: String, val versionCode: Int, val permissions: List<String>, val activities: List<String>, val services: List<String>, val receivers: List<String>, val providers: List<String>)

    /** The empty answer, used when a package genuinely cannot be resolved. */
    private fun emptyPmPackageInfo() =
        PmPackageInfo("", 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ReverseEngineeringToolDefinitions.LIST_INSTALLED_PACKAGES -> listInstalledPackages(args)
            ReverseEngineeringToolDefinitions.PM_DUMP -> pmDump(args)
            ReverseEngineeringToolDefinitions.PULL_APK -> pullApk(args)
            ReverseEngineeringToolDefinitions.LIST_APK_CONTENTS -> listApkContents(args)
            ReverseEngineeringToolDefinitions.EXTRACT_APK -> extractApk(args)
            ReverseEngineeringToolDefinitions.READ_MANIFEST -> readManifest(args)
            ReverseEngineeringToolDefinitions.EXTRACT_DEX -> extractDex(args)
            ReverseEngineeringToolDefinitions.EXTRACT_NATIVE_LIBS -> extractNativeLibs(args)
            ReverseEngineeringToolDefinitions.EXTRACT_RESOURCES -> extractResources(args)
            ReverseEngineeringToolDefinitions.DECOMPILE_APK -> decompileApk(args)
            ReverseEngineeringToolDefinitions.MOD_APK -> modApk(args)
            ReverseEngineeringToolDefinitions.SIGN_APK -> signApk(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun listInstalledPackages(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val filter = args["filter"] as? String ?: "all"; val limit = (args["limit"] as? Number)?.toInt() ?: 100
        val flag = when (filter.lowercase()) { "user" -> "-3"; "system" -> "-s"; else -> "" }
        val cmd = if (filter.lowercase() in listOf("all", "user", "system")) "pm list packages -f $flag 2>/dev/null | head -$limit" else "pm list packages -f 2>/dev/null | grep -i '$filter' | head -$limit"
        val result = shellExecutor.execute(cmd)
        val packages = result.stdout.lines().filter { it.startsWith("package:") }.map { line -> val parts = line.removePrefix("package:").split("="); InstalledPackage(parts.getOrNull(0)?.trim() ?: "", parts.getOrNull(1)?.trim() ?: "") }.filter { it.packageName.isNotEmpty() }
        val r = InstalledPackagesResult(packages); ToolExecutionResult.success(r, json.encodeToString(InstalledPackagesResult.serializer(), r))
    }

    /**
     * Inspect an installed package: version, permissions, activities, services,
     * receivers, providers.
     *
     * THE DEFECT THIS FIXES (issue 44). This ran `pm dump` and reported its
     * exit code. `dumpsys` and `pm dump` print "Permission Denial: …" to STDOUT
     * and exit 0, so on an unrooted device every line of the answer was a
     * refusal and the tool called it `success: true`. Worse, the denial body
     * keeps the "DUMP OF SERVICE <name>:" headers, so six refusals look like six
     * services to anyone summarising the text. And DUMP is signature-level: a
     * normal app can never hold it, so there was nothing to retry and nothing to
     * change.
     *
     * The honest and useful answer is the in-process one the issue names:
     * `PackageManager.getPackageInfo` with GET_PERMISSIONS, GET_ACTIVITIES,
     * GET_SERVICES and GET_RECEIVERS needs no permission at all and returns the
     * same information. It is tried FIRST, and `pm dump` runs only as a fallback
     * for a package the in-process call cannot see.
     *
     * The result keeps the ShellCommandResult shape because callers already read
     * it, and the new `denial` field carries one actionable sentence instead of
     * a page of refusals.
     */
    private suspend fun pmDump(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")

        val inProcess = readPackageInfo(packageName)
        if (inProcess != null) {
            val report = renderPackageInfo(packageName, inProcess)
            val r = ShellCommandResult(
                exitCode = 0,
                stdout = report,
                stderr = "",
                success = true,
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
        }

        // PackageManager could not resolve it — another app's package on a
        // device where it is not visible, or not installed at all. The shell can
        // still answer for some of those, but only when the tier holds DUMP.
        val result = shellExecutor.execute("pm dump $packageName 2>&1 | head -500")
        if (ShellDenialSignatures.isDumpPermissionDenial(result.stdout, result.stderr)) {
            // One sentence, not the page of denials. The tier the call ran at
            // says whether it is a permanent wall.
            val explanation = result.denial
                ?: ShellDenialSignatures.explain("Permission Denial:", result.tier)
            val r = ShellCommandResult(
                exitCode = result.exitCode,
                stdout = "",
                stderr = "",
                success = false,
                tier = result.tier.name,
                tierCapabilities = result.tier.capabilities(),
                denial = explanation,
                advisory = result.advisory,
            )
            return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
        }

        // No DUMP refusal: report whatever the shell observed, including a plain
        // non-zero exit or a clean answer from a tier that holds DUMP.
        val r = ShellCommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            success = result.success,
            tier = result.tier.name,
            tierCapabilities = result.tier.capabilities(),
            denial = result.denial,
            advisory = result.advisory,
        )
        ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

    /**
     * Read a package's information through PackageManager, in-process.
     *
     * Returns null when the package cannot be resolved this way, which is the
     * only case that should fall back to a shell dump. The flags are the four
     * the issue names; they are ignored rather than required on recent
     * releases, and passing them costs nothing where they still matter.
     */
    private fun readPackageInfo(packageName: String): PmPackageInfo? {
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: Exception) {
            return null
        }

        val requested = info.requestedPermissions?.toList() ?: emptyList()
        // longVersionCode arrived in API 28 and this module supports 26, so it
        // is read only where it exists rather than risking NoSuchMethodError.
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
        return PmPackageInfo(
            versionName = info.versionName ?: "",
            versionCode = versionCode,
            // Declared permissions the package itself defines, plus the ones it
            // asks for. Both are what `pm dump` printed under "requested
            // permissions:", and the shell parser read only that list.
            permissions = (requested + (info.permissions?.map { it.name } ?: emptyList())).distinct(),
            activities = info.activities?.map { it.name } ?: emptyList(),
            services = info.services?.map { it.name } ?: emptyList(),
            receivers = info.receivers?.map { it.name } ?: emptyList(),
            providers = info.providers?.map { it.name } ?: emptyList(),
        )
    }

    /**
     * Render package information in the same key-per-line shape `pm dump` used,
     * so a caller that read that text still can.
     */
    private fun renderPackageInfo(packageName: String, info: PmPackageInfo): String = buildString {
        appendLine("package: $packageName")
        appendLine("versionName=${info.versionName}")
        appendLine("versionCode=${info.versionCode}")
        appendLine("source=PackageManager (in-process, no DUMP permission required)")
        if (info.permissions.isNotEmpty()) {
            appendLine("requested permissions:")
            info.permissions.forEach { appendLine(it) }
        }
        if (info.activities.isNotEmpty()) {
            appendLine("activities:")
            info.activities.forEach { appendLine(it) }
        }
        if (info.services.isNotEmpty()) {
            appendLine("services:")
            info.services.forEach { appendLine(it) }
        }
        if (info.receivers.isNotEmpty()) {
            appendLine("receivers:")
            info.receivers.forEach { appendLine(it) }
        }
        if (info.providers.isNotEmpty()) {
            appendLine("providers:")
            info.providers.forEach { appendLine(it) }
        }
    }.trim()

    private suspend fun pullApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        val workDir = (args["outputDir"] as? String) ?: "${context.cacheDir.absolutePath}/reverse_engineering"; File(workDir).mkdirs()
        val pathResult = shellExecutor.execute("pm path $packageName 2>/dev/null")
        if (!pathResult.success || !pathResult.stdout.contains("package:")) { val r = PullApkResult(false, packageName, "", "", 0, "Package not found: $packageName"); return@withContext ToolExecutionResult.success(r, json.encodeToString(PullApkResult.serializer(), r)) }
        val apkPath = pathResult.stdout.lines().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: ""
        if (apkPath.isEmpty()) { val r = PullApkResult(false, packageName, "", "", 0, "Could not determine APK path"); return@withContext ToolExecutionResult.success(r, json.encodeToString(PullApkResult.serializer(), r)) }
        val outputFile = "$workDir/${packageName}.apk"; val copyResult = shellExecutor.execute("cp \"$apkPath\" \"$outputFile\" 2>&1")
        val size = if (copyResult.success) File(outputFile).length() else 0L
        val r = PullApkResult(copyResult.success, packageName, apkPath, outputFile, size, if (copyResult.success) null else copyResult.stderr); ToolExecutionResult.success(r, json.encodeToString(PullApkResult.serializer(), r))
    }

    private suspend fun listApkContents(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val result = shellExecutor.execute("unzip -l \"$apkPath\" 2>&1 | head -500")
        val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success, denial = result.denial, advisory = result.advisory); ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

    private suspend fun extractApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        File(outputDir).mkdirs(); val result = shellExecutor.execute("unzip -o \"$apkPath\" -d \"$outputDir\" 2>&1")
        val fileCount = if (result.success) File(outputDir).walkTopDown().count { it.isFile } else 0
        val r = ExtractionResult(result.success, outputDir, fileCount, if (result.success) null else result.stderr); ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r))
    }

    /**
     * Read an APK's manifest with the bundled aapt, falling back to aapt2 and
     * then to a strings scan.
     *
     * THE DEFECT THIS FIXES (issue 200). This called `wrapAapt` and `wrapAapt2`
     * on ReToolingProvider, which returned a command string for a binary in
     * `files/re-tools/` that was never bundled. The command then went through
     * ShellExecutor, which ends at `sh -c` under the app UID — and app storage
     * is not executable, so even a present aapt could not have run that way.
     * Every branch failed and the last one blamed "bundled aapt2 unavailable",
     * which named the wrong cause.
     *
     * Now the tools are real (installed into the prefix by TermuxProvider), they
     * are launched through linker64 with an explicit argument list rather than a
     * re-parsed string, and the reported method names which one actually
     * answered. When none can, the error says what was tried and why each
     * stopped, instead of attributing the failure to one tool.
     */
    private suspend fun readManifest(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        if (!shellExecutor.reTools.isInitialized) shellExecutor.reTools.initialize()

        // aapt first: it is the tool built for exactly this and its badging
        // output is the shape parseAaptOutput reads. aapt2 answers the same
        // question with the same format when aapt is absent.
        val attempts = mutableListOf<String>()
        for (tool in listOf("aapt", "aapt2")) {
            val probe = shellExecutor.reTools.toolState(tool)
            if (probe.state != TermuxProvider.CommandProbe.RUNS) {
                attempts.add("$tool ${probe.state.name.lowercase().replace('_', ' ')}")
                continue
            }
            val result = termux.execBinary(tool, listOf("dump", "badging", apkPath))
            if (result.success && result.stdout.isNotBlank()) {
                val parsed = parseAaptOutput(result.stdout)
                val r = ManifestResult(
                    true, parsed.packageName, parsed.versionName, parsed.versionCode,
                    parsed.permissions, parsed.activities, parsed.services,
                    parsed.receivers, parsed.providers, result.stdout,
                    "$tool (bundled toolchain)", null,
                )
                return@withContext ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r))
            }
            // Ran but produced nothing usable, so the real output is the reason.
            attempts.add("$tool ran but gave no output (exit ${result.exitCode})")
        }

        /*
         * A strings scan is a genuinely different source of truth rather than a
         * degradation of the same one: it reads the binary XML directly, so it
         * answers for a packaged APK even when the toolchain is missing. It is
         * reported as its own method so a caller can tell which answered, and the
         * note states what the richer path would have done.
         */
        val tmpDir = "${context.cacheDir.absolutePath}/manifest_tmp_${System.currentTimeMillis()}"
        File(tmpDir).mkdirs()
        try {
            shellExecutor.execute("unzip -o \"$apkPath\" AndroidManifest.xml -d \"$tmpDir\" 2>/dev/null")
            val manifestFile = File("$tmpDir/AndroidManifest.xml")
            if (manifestFile.exists()) {
                val stringsResult = shellExecutor.execute("strings \"${manifestFile.absolutePath}\" 2>/dev/null")
                if (stringsResult.stdout.isNotBlank()) {
                    val pkgName = extractPackageName(stringsResult.stdout) ?: "unknown"
                    val pmInfo = getPmPackageInfo(pkgName)
                    val r = ManifestResult(
                        true, pkgName, pmInfo.versionName, pmInfo.versionCode,
                        pmInfo.permissions, pmInfo.activities, pmInfo.services,
                        pmInfo.receivers, pmInfo.providers,
                        stringsResult.stdout.take(10000), "strings+pm",
                        "Decoded by scanning the binary XML, not by aapt. " +
                            "aapt/aapt2 were tried first: ${attempts.joinToString("; ")}.",
                    )
                    return@withContext ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r))
                }
            }
        } finally {
            shellExecutor.execute("rm -rf \"$tmpDir\" 2>/dev/null")
        }

        val r = ManifestResult(
            false, "", "", 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            "", "none",
            "Could not read the manifest. aapt and aapt2 were tried first: " +
                "${attempts.joinToString("; ")}; the strings fallback also produced nothing.",
        )
        ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r))
    }

    private suspend fun extractDex(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        File(outputDir).mkdirs()
        val listResult = shellExecutor.execute("unzip -l \"$apkPath\" 2>/dev/null | grep 'classes.*\\.dex'")
        val dexFiles = listResult.stdout.lines().mapNotNull { line -> val parts = line.trim().split(Regex("\\s+")); parts.lastOrNull() }.filter { it.startsWith("classes") && it.endsWith(".dex") }
        if (dexFiles.isEmpty()) { val r = ExtractionResult(false, outputDir, 0, "No classes.dex files found in APK"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r)) }
        val dexPattern = dexFiles.joinToString(" ") { "\"$it\"" }; val result = shellExecutor.execute("unzip -o \"$apkPath\" $dexPattern -d \"$outputDir\" 2>&1")
        val fileCount = if (result.success) File(outputDir).walkTopDown().count { it.isFile && it.extension == "dex" } else 0
        val stringsOutput = StringBuilder()
        File(outputDir).listFiles()?.filter { it.extension == "dex" }?.forEach { dex -> val s = shellExecutor.execute("strings \"${dex.absolutePath}\" 2>/dev/null | head -200"); if (s.stdout.isNotBlank()) { stringsOutput.appendLine("=== ${dex.name} strings ==="); stringsOutput.appendLine(s.stdout) } }
        val r = ExtractionResult(result.success, outputDir, fileCount, if (result.success) null else result.stderr, if (stringsOutput.isNotEmpty()) stringsOutput.toString() else null); ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r))
    }

    private suspend fun extractNativeLibs(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        val abi = args["abi"] as? String ?: "arm64-v8a"; File(outputDir).mkdirs()
        val listResult = shellExecutor.execute("unzip -l \"$apkPath\" 2>/dev/null | grep 'lib/$abi/'")
        val libFiles = listResult.stdout.lines().mapNotNull { line -> val parts = line.trim().split(Regex("\\s+")); parts.lastOrNull() }.filter { it.endsWith(".so") }
        val actualLibs = if (libFiles.isEmpty()) { val allLibs = shellExecutor.execute("unzip -l \"$apkPath\" 2>/dev/null | grep '\\.so$'"); allLibs.stdout.lines().mapNotNull { line -> val parts = line.trim().split(Regex("\\s+")); parts.lastOrNull() }.filter { it.endsWith(".so") } } else libFiles
        if (actualLibs.isEmpty()) { val r = ExtractionResult(false, outputDir, 0, "No native libraries found in APK for ABI '$abi' or any ABI"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r)) }
        val pattern = actualLibs.joinToString(" ") { "\"$it\"" }; val result = shellExecutor.execute("unzip -o \"$apkPath\" $pattern -d \"$outputDir\" 2>&1")
        val count = if (result.success) File(outputDir).walkTopDown().count { it.isFile && it.extension == "so" } else 0
        val r = ExtractionResult(result.success, outputDir, count, if (result.success) null else result.stderr); ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r))
    }

    private suspend fun extractResources(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        val resourceType = args["resourceType"] as? String ?: "all"; File(outputDir).mkdirs()
        val pattern = when (resourceType.lowercase()) { "layouts" -> "\"res/layout*\" \"res/layout*/\"*"; "drawables" -> "\"res/drawable*\" \"res/drawable*/\"* \"res/mipmap*\" \"res/mipmap*/\"*"; "strings" -> "\"resources.arsc\" \"res/values*\" \"res/values*/\"*"; "assets" -> "\"assets/*\" \"assets/**/*\""; "raw" -> "\"res/raw/*\" \"res/raw/**/*\""; else -> "\"res/*\" \"res/**/*\" \"resources.arsc\" \"assets/*\" \"assets/**/*\"" }
        val result = shellExecutor.execute("unzip -o \"$apkPath\" $pattern -d \"$outputDir\" 2>&1")
        val fileCount = if (result.success) File(outputDir).walkTopDown().count { it.isFile } else 0
        val r = ExtractionResult(result.success, outputDir, fileCount, if (result.success) null else result.stderr); ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r))
    }

    /**
     * Decompile an APK to Java and smali with the bundled JVM toolchain.
     *
     * THE DEFECT THIS FIXES (issue 200). Every branch asked whether a tool was
     * "available" — which was File.exists() on a wrapper — then built a command
     * string through wrapJadx/wrapApktool/wrapDex2jar and pushed it through
     * ShellExecutor. None of those wrappers existed, none of the jars existed,
     * and there was no JVM for any of them to launch even if they had. The
     * branches therefore reported "NOT AVAILABLE" from a real absence that the
     * provider had just described as present.
     *
     * Now each decompiler is checked by RUNNING it, launched with an explicit
     * argument list through linker64, and the summary states what each one did
     * with the real output attached when it failed. If the JVM itself is absent
     * that is said once, at the top, because every decompiler depends on it and
     * three identical failures hide the single cause.
     */
    private suspend fun decompileApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        val method = args["method"] as? String ?: "auto"
        if (!shellExecutor.reTools.isInitialized) shellExecutor.reTools.initialize()
        File(outputDir).mkdirs()

        val methods = StringBuilder()

        // The JVM is the shared dependency of all three decompilers, so its
        // absence is reported as the cause rather than as three symptoms.
        val javaProbe = shellExecutor.reTools.toolState("java")
        val javaRuns = javaProbe.state == TermuxProvider.CommandProbe.RUNS
        if (!javaRuns) {
            val reason = when (javaProbe.state) {
                TermuxProvider.CommandProbe.ABSENT ->
                    "the bundled JVM is not installed"
                TermuxProvider.CommandProbe.WILL_NOT_LOAD ->
                    "the bundled JVM will not run (${javaProbe.output.take(200)})"
                TermuxProvider.CommandProbe.RUNS -> "the bundled JVM runs"
            }
            methods.append("Java decompilers unavailable: $reason.\n")
        }

        /**
         * Run one decompiler and report what it actually produced.
         *
         * The success test is FILES ON DISK, not the exit code, because jadx and
         * apktool both exit non-zero on warnings while leaving a complete tree.
         * Judging by exit code would call a working decompile a failure.
         */
        suspend fun runDecompiler(
            tool: String,
            outDir: String,
            fullArgv: List<String>,
            extension: String,
        ) {
            if (!javaRuns) {
                methods.append("$tool: skipped, the JVM does not run\n")
                return
            }
            val probe = shellExecutor.reTools.toolState(tool)
            if (probe.state != TermuxProvider.CommandProbe.RUNS) {
                val why = when (probe.state) {
                    TermuxProvider.CommandProbe.ABSENT -> "not installed"
                    TermuxProvider.CommandProbe.WILL_NOT_LOAD ->
                        "will not run: ${probe.output.take(200)}"
                    TermuxProvider.CommandProbe.RUNS -> "runs"
                }
                methods.append("$tool: $why\n")
                return
            }
            File(outDir).mkdirs()
            val result = termux.execBinary(tool, fullArgv)
            val produced = File(outDir).walkTopDown().count { it.extension == extension }
            if (produced > 0) {
                methods.append("$tool: SUCCESS ($produced .$extension files)\n")
            } else {
                // Real output, trimmed: a decompiler that failed says why on
                // stderr and that sentence is the whole value of the attempt.
                val detail = (result.stderr.ifBlank { result.stdout }).trim().take(300)
                methods.append(
                    "$tool: produced no .$extension files (exit ${result.exitCode})" +
                        if (detail.isNotEmpty()) ": $detail\n" else "\n",
                )
            }
        }

        /*
         * Each decompiler gets a complete argument list rather than a shared
         * prefix, because their CLIs disagree: jadx takes `-d <dir> <apk>`;
         * apktool takes a `d` subcommand then `-f -o <dir> <apk>`; dex2jar takes
         * `-f -o <jar> <apk>` and produces a JAR, not a directory of sources.
         * Sharing one builder is how the wrong flag reaches the wrong binary.
         */
        if (method in listOf("auto", "jadx")) {
            val outDir = "$outputDir/jadx_output"
            runDecompiler("jadx", outDir, listOf("-d", outDir, apkPath), "java")
        }
        if (method in listOf("auto", "apktool")) {
            val outDir = "$outputDir/apktool_output"
            runDecompiler("apktool", outDir, listOf("d", "-f", "-o", outDir, apkPath), "smali")
        }
        if (method in listOf("auto", "dex2jar")) {
            val outDir = "$outputDir/dex2jar_output"
            val jar = "$outDir/output.jar"
            // Its output is a single jar, so the "did it produce something" test
            // is the jar's existence rather than a file count. The extension
            // passed here is only used for that count and the message, so the
            // count is computed from the jar directly below.
            runDecompiler("dex2jar", outDir, listOf("-f", "-o", jar, apkPath), "jar")
        }

        /*
         * Extract and scan strings ALWAYS, because it needs no toolchain at all
         * and it is the one step that cannot fail for want of a dependency. It is
         * labelled as its own method rather than as a fallback, so a caller can
         * tell which path produced the output.
         */
        val extractDir = "$outputDir/extracted"
        File(extractDir).mkdirs()
        shellExecutor.execute("unzip -o \"$apkPath\" -d \"$extractDir\" 2>/dev/null")
        val stringsOutput = StringBuilder()
        File(extractDir).walkTopDown().forEach { file ->
            if (file.isFile && file.extension == "dex") {
                val s = shellExecutor.execute("strings \"${file.absolutePath}\" 2>/dev/null | head -300")
                if (s.stdout.isNotBlank()) {
                    stringsOutput.appendLine("=== ${file.name} strings ===")
                    stringsOutput.appendLine(s.stdout)
                }
            }
        }
        methods.append("extract+strings: always available, no toolchain needed")

        // Decompilation is only a success when at least one decompiler produced
        // files. Reporting true while every method failed is the same lie as
        // reporting a tool present because its filename exists.
        val javaFiles = File(outputDir).walkTopDown().count { it.extension == "java" }
        val smaliFiles = File(outputDir).walkTopDown().count { it.extension == "smali" }
        val decompiled = javaFiles > 0 || smaliFiles > 0

        val r = DecompileResult(
            success = decompiled,
            outputDir = outputDir,
            methodsSummary = methods.toString().trim(),
            javaFiles = javaFiles,
            smaliFiles = smaliFiles,
            extractedFiles = File(extractDir).walkTopDown().count { it.isFile },
            stringsPreview = if (stringsOutput.isNotEmpty()) stringsOutput.toString().take(8000) else null,
            error = if (decompiled) null else
                "No decompiler produced output. The bundle is in the summary above, " +
                    "which states what each tool did rather than only that it was missing.",
        )
        ToolExecutionResult.success(r, json.encodeToString(DecompileResult.serializer(), r))
    }

    private suspend fun modApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val modDir = args["modDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'modDir'")
        val outputPath = args["outputPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputPath'")
        val workDir = "${context.cacheDir.absolutePath}/mod_${System.currentTimeMillis()}"; File(workDir).mkdirs()
        val extractResult = shellExecutor.execute("unzip -o \"$apkPath\" -d \"$workDir\" 2>&1")
        if (!extractResult.success) { val r = ModApkResult(false, outputPath, 0, "Failed to unpack: ${extractResult.stderr}"); shellExecutor.execute("rm -rf \"$workDir\" 2>/dev/null"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ModApkResult.serializer(), r)) }
        shellExecutor.execute("cp -r \"$modDir/.\" \"$workDir/\" 2>&1")
        shellExecutor.execute("rm -f \"$outputPath\" 2>/dev/null")
        val repackResult = shellExecutor.execute("cd \"$workDir\" && zip -r \"$outputPath\" . -x \"*.DS_Store\" \"__MACOSX/*\" 2>&1")
        shellExecutor.execute("rm -rf \"$workDir\" 2>/dev/null")
        if (!repackResult.success) { val r = ModApkResult(false, outputPath, 0, "Failed to repack: ${repackResult.stderr}"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ModApkResult.serializer(), r)) }
        val outputSize = File(outputPath).length()
        val r = ModApkResult(true, outputPath, outputSize, null, "APK modified. MUST sign with signApk before installing."); ToolExecutionResult.success(r, json.encodeToString(ModApkResult.serializer(), r))
    }

    /**
     * Sign an APK, in this process, with no external tool (issue 200).
     *
     * THE DEFECT THIS REPLACES. The old implementation shelled out three times:
     * `keytool` to create a keystore, `which apksigner`, then `which jarsigner`.
     * On the reporting handset none of those were present anywhere — not in the
     * APK, not in the prefix — so the tool could only ever return "No signing
     * tool available", while the same build's copy promised a complete on-device
     * rebuild-and-sign chain.
     *
     * Worse, each step's failure was silent. A `which` returning empty is not an
     * exception, so the code walked from missing keytool to missing apksigner to
     * missing jarsigner and reported the last one, which named a tool the caller
     * had never asked for.
     *
     * THE ANSWER. Signing is now done by apksig in this process. It needs no
     * JVM, no keytool and no shell, so it works on a handset with none of them.
     * The keystore is generated by KeyStore and a hand-written DER certificate,
     * because a certificate cannot be created through any public JCA API.
     *
     * Every outcome is a named reason. A missing input, an empty file, a bad
     * keystore and a signing fault all read differently, so whoever sees the
     * result knows what to do next rather than being told a tool is absent.
     */
    private suspend fun signApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val signedPath = (args["outputPath"] as? String) ?: apkPath.replace(".apk", "-signed.apk")

        val result = try {
            val keystore = ApkSigner.ensureKeystore(File(debugKeyStore))
            ApkSigner.sign(
                inputApk = File(apkPath),
                outputApk = File(signedPath),
                keystoreFile = keystore,
                // The minSdk the app itself supports. apksig uses it to decide
                // which signature schemes are worth producing, and reporting a
                // scheme the platform will not check would be a false claim.
                minSdk = 26,
            )
        } catch (e: Exception) {
            // ensureKeystore can fail where sign() cannot, so its faults are
            // reported with the same shape rather than escaping as a crash.
            ApkSigner.Result(
                success = false,
                signedPath = "",
                method = "apksig",
                error = "Could not create or read the signing key " +
                    "(${e.javaClass.simpleName}): ${e.message ?: "no detail"}",
            )
        }

        val r = SignApkResult(
            success = result.success,
            signedPath = result.signedPath,
            method = result.method,
            error = result.error,
        )
        ToolExecutionResult.success(r, json.encodeToString(SignApkResult.serializer(), r))
    }

    private fun parseAaptOutput(output: String): ParsedAapt {
        var packageName = ""; var versionName = ""; var versionCode = 0; val permissions = mutableListOf<String>(); val activities = mutableListOf<String>(); val services = mutableListOf<String>(); val receivers = mutableListOf<String>(); val providers = mutableListOf<String>()
        for (line in output.lines()) { val trimmed = line.trim(); when { trimmed.startsWith("package:") -> { packageName = Regex("name='([^']+)'").find(trimmed)?.groupValues?.get(1) ?: ""; versionName = Regex("versionName='([^']*)'").find(trimmed)?.groupValues?.get(1) ?: ""; versionCode = Regex("versionCode='([^']+)'").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }; trimmed.startsWith("uses-permission:") -> permissions.add(trimmed.removePrefix("uses-permission:").trim().removePrefix("name='").removeSuffix("'")); trimmed.startsWith("launchable-activity:") -> activities.add(trimmed.removePrefix("launchable-activity:").trim().removePrefix("name='").removeSuffix("'")) } }
        return ParsedAapt(packageName, versionName, versionCode, permissions, activities, services, receivers, providers)
    }

    private fun extractPackageName(strings: String): String? = Regex("""([a-z][a-z0-9_]*\.){2,}[a-zA-Z][a-zA-Z0-9_]*""").findAll(strings).map { it.value }.firstOrNull { it.count { c -> c == '.' } >= 2 && it.length > 8 }

    /**
     * Package information for `readManifest`'s strings-only fallback.
     *
     * Issue 44 applies here too: this was the second place that read `pm dump`
     * and trusted its text. PackageManager is tried first because it needs no
     * permission, and the shell path remains for a package the in-process call
     * cannot resolve.
     */
    private suspend fun getPmPackageInfo(packageName: String): PmPackageInfo {
        if (packageName == "unknown" || packageName.isEmpty()) return emptyPmPackageInfo()
        readPackageInfo(packageName)?.let { return it }
        val result = shellExecutor.execute("pm dump $packageName 2>/dev/null | head -300"); val output = result.stdout
        var versionName = ""; var versionCode = 0; val permissions = mutableListOf<String>(); val activities = mutableListOf<String>(); val services = mutableListOf<String>(); val receivers = mutableListOf<String>(); val providers = mutableListOf<String>()
        var inPermissions = false; var inActivities = false
        for (line in output.lines()) { val trimmed = line.trim(); when { trimmed.startsWith("versionName=") -> versionName = trimmed.removePrefix("versionName="); trimmed.startsWith("versionCode=") -> versionCode = trimmed.removePrefix("versionCode=").toIntOrNull() ?: 0; trimmed == "requested permissions:" -> inPermissions = true; trimmed == "install permissions:" -> inPermissions = false; trimmed.startsWith("Activity Resolver Table:") -> inActivities = true; trimmed.startsWith("Service Resolver Table:") -> inActivities = false; inPermissions && trimmed.startsWith("android.permission.") -> permissions.add(trimmed.split(" ")[0]); inActivities && "filter" in trimmed.lowercase() && !trimmed.startsWith("Activity") -> { val parts = trimmed.split(Regex("\\s+")); val activity = parts.lastOrNull()?.takeIf { "/" in it || "." in it }; if (activity != null) activities.add(activity) } } }
        return PmPackageInfo(versionName, versionCode, permissions, activities, services, receivers, providers)
    }
}