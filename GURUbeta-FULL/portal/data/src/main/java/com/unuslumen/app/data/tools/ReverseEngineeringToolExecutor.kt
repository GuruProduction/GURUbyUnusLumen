package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ReverseEngineeringToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)
    private val debugKeyStore: String by lazy { "${context.filesDir.absolutePath}/guru_debug.keystore" }

    private data class ParsedAapt(val packageName: String, val versionName: String, val versionCode: Int, val permissions: List<String>, val activities: List<String>, val services: List<String>, val receivers: List<String>, val providers: List<String>)
    private data class PmPackageInfo(val versionName: String, val versionCode: Int, val permissions: List<String>, val activities: List<String>, val services: List<String>, val receivers: List<String>, val providers: List<String>)

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

    private suspend fun pmDump(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val packageName = args["packageName"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packageName'")
        val result = shellExecutor.execute("pm dump $packageName 2>&1 | head -500")
        val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

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
        val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

    private suspend fun extractApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        File(outputDir).mkdirs(); val result = shellExecutor.execute("unzip -o \"$apkPath\" -d \"$outputDir\" 2>&1")
        val fileCount = if (result.success) File(outputDir).walkTopDown().count { it.isFile } else 0
        val r = ExtractionResult(result.success, outputDir, fileCount, if (result.success) null else result.stderr); ToolExecutionResult.success(r, json.encodeToString(ExtractionResult.serializer(), r))
    }

    private suspend fun readManifest(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        if (!shellExecutor.reTools.isInitialized) shellExecutor.reTools.initialize()
        if (shellExecutor.reTools.isAaptAvailable()) { val cmd = shellExecutor.reTools.wrapAapt("dump badging \"$apkPath\""); val aaptResult = shellExecutor.execute("$cmd 2>&1"); if (aaptResult.success && aaptResult.stdout.isNotBlank()) { val parsed = parseAaptOutput(aaptResult.stdout); val r = ManifestResult(true, parsed.packageName, parsed.versionName, parsed.versionCode, parsed.permissions, parsed.activities, parsed.services, parsed.receivers, parsed.providers, aaptResult.stdout, "aapt (bundled)", null); return@withContext ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r)) } }
        if (shellExecutor.reTools.isAapt2Available()) { val cmd = shellExecutor.reTools.wrapAapt2("dump badging \"$apkPath\""); val aaptResult = shellExecutor.execute("$cmd 2>&1"); if (aaptResult.success && aaptResult.stdout.isNotBlank()) { val parsed = parseAaptOutput(aaptResult.stdout); val r = ManifestResult(true, parsed.packageName, parsed.versionName, parsed.versionCode, parsed.permissions, parsed.activities, parsed.services, parsed.receivers, parsed.providers, aaptResult.stdout, "aapt2 (bundled)", null); return@withContext ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r)) } }
        val aaptResult = shellExecutor.execute("aapt dump badging \"$apkPath\" 2>&1")
        if (aaptResult.success && aaptResult.stdout.isNotBlank()) { val parsed = parseAaptOutput(aaptResult.stdout); val r = ManifestResult(true, parsed.packageName, parsed.versionName, parsed.versionCode, parsed.permissions, parsed.activities, parsed.services, parsed.receivers, parsed.providers, aaptResult.stdout, "aapt (system)", null); return@withContext ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r)) }
        val tmpDir = "${context.cacheDir.absolutePath}/manifest_tmp_${System.currentTimeMillis()}"; File(tmpDir).mkdirs()
        shellExecutor.execute("unzip -o \"$apkPath\" AndroidManifest.xml -d \"$tmpDir\" 2>/dev/null")
        val manifestFile = File("$tmpDir/AndroidManifest.xml")
        if (manifestFile.exists()) { val stringsResult = shellExecutor.execute("strings \"${manifestFile.absolutePath}\" 2>/dev/null"); if (stringsResult.stdout.isNotBlank()) { val pkgName = extractPackageName(stringsResult.stdout) ?: "unknown"; val pmInfo = getPmPackageInfo(pkgName); shellExecutor.execute("rm -rf \"$tmpDir\" 2>/dev/null"); val r = ManifestResult(true, pkgName, pmInfo.versionName, pmInfo.versionCode, pmInfo.permissions, pmInfo.activities, pmInfo.services, pmInfo.receivers, pmInfo.providers, stringsResult.stdout.take(10000), "strings+pm", "Binary XML — for full decoding, aapt2 is bundled but not available."); return@withContext ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r)) } }
        shellExecutor.execute("rm -rf \"$tmpDir\" 2>/dev/null")
        val r = ManifestResult(false, "", "", 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "", "none", "Could not read manifest. Bundled aapt2 unavailable."); ToolExecutionResult.success(r, json.encodeToString(ManifestResult.serializer(), r))
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

    private suspend fun decompileApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val outputDir = args["outputDir"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'outputDir'")
        val method = args["method"] as? String ?: "auto"
        if (!shellExecutor.reTools.isInitialized) shellExecutor.reTools.initialize(); File(outputDir).mkdirs()
        val methods = StringBuilder()
        if (method in listOf("auto", "jadx") && shellExecutor.reTools.isJadxAvailable()) { val jadxDir = "$outputDir/jadx_output"; File(jadxDir).mkdirs(); val cmd = shellExecutor.reTools.wrapJadx("-d \"$jadxDir\" \"$apkPath\""); val decompile = shellExecutor.execute("$cmd 2>&1 | tail -20"); val javaCount = if (decompile.success) File(jadxDir).walkTopDown().count { it.extension == "java" } else 0; if (javaCount > 0) methods.append("jadx (bundled): SUCCESS ($javaCount Java files)\n") else methods.append("jadx (bundled): FAILED\n") } else if (method in listOf("auto", "jadx")) methods.append("jadx: NOT AVAILABLE\n")
        if (method in listOf("auto", "apktool") && shellExecutor.reTools.isApktoolAvailable()) { val apktoolDir = "$outputDir/apktool_output"; File(apktoolDir).mkdirs(); val cmd = shellExecutor.reTools.wrapApktool("d -f -o \"$apktoolDir\" \"$apkPath\""); val decompile = shellExecutor.execute("$cmd 2>&1 | tail -20"); val smaliCount = if (decompile.success) File(apktoolDir).walkTopDown().count { it.extension == "smali" } else 0; if (smaliCount > 0) methods.append("apktool (bundled): SUCCESS ($smaliCount smali files)\n") else methods.append("apktool (bundled): FAILED\n") } else if (method in listOf("auto", "apktool")) methods.append("apktool: NOT AVAILABLE\n")
        if (method in listOf("auto", "dex2jar") && shellExecutor.reTools.isDex2jarAvailable()) { val dex2jarDir = "$outputDir/dex2jar_output"; File(dex2jarDir).mkdirs(); val outputJar = "$dex2jarDir/output.jar"; val cmd = shellExecutor.reTools.wrapDex2jar(apkPath, outputJar); val decompile = shellExecutor.execute("$cmd 2>&1 | tail -10"); if (decompile.success && File(outputJar).exists() && File(outputJar).length() > 0) methods.append("dex2jar (bundled): SUCCESS\n") else methods.append("dex2jar (bundled): FAILED\n") } else if (method in listOf("auto", "dex2jar")) methods.append("dex2jar: NOT AVAILABLE\n")
        val extractDir = "$outputDir/extracted"; File(extractDir).mkdirs(); shellExecutor.execute("unzip -o \"$apkPath\" -d \"$extractDir\" 2>/dev/null")
        val stringsOutput = StringBuilder()
        File(extractDir).walkTopDown().forEach { file -> if (file.isFile && file.extension == "dex") { val s = shellExecutor.execute("strings \"${file.absolutePath}\" 2>/dev/null | head -300"); if (s.stdout.isNotBlank()) { stringsOutput.appendLine("=== ${file.name} strings ==="); stringsOutput.appendLine(s.stdout) } } }
        methods.append("extract+strings: ALWAYS AVAILABLE")
        val r = DecompileResult(true, outputDir, methods.toString(), File(outputDir).walkTopDown().count { it.extension == "java" }, File(outputDir).walkTopDown().count { it.extension == "smali" }, File(extractDir).walkTopDown().count { it.isFile }, if (stringsOutput.isNotEmpty()) stringsOutput.toString().take(8000) else null, null); ToolExecutionResult.success(r, json.encodeToString(DecompileResult.serializer(), r))
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

    private suspend fun signApk(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val apkPath = args["apkPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'apkPath'")
        val signedPath = (args["outputPath"] as? String) ?: apkPath.replace(".apk", "-signed.apk")
        if (!File(debugKeyStore).exists()) { shellExecutor.execute("keytool -genkey -v -keystore \"$debugKeyStore\" -alias guru_debug -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname \"CN=Guru Debug, OU=RE, O=Guru, L=Unknown, ST=Unknown, C=XX\" 2>&1") }
        val apkSigner = shellExecutor.execute("which apksigner 2>/dev/null")
        if (apkSigner.success && apkSigner.stdout.isNotBlank()) { val signResult = shellExecutor.execute("apksigner sign --ks \"$debugKeyStore\" --ks-pass pass:android --key-pass pass:android --out \"$signedPath\" \"$apkPath\" 2>&1"); if (signResult.success) { val r = SignApkResult(true, signedPath, "apksigner", null); return@withContext ToolExecutionResult.success(r, json.encodeToString(SignApkResult.serializer(), r)) } }
        val jarsigner = shellExecutor.execute("which jarsigner 2>/dev/null")
        if (jarsigner.success && jarsigner.stdout.isNotBlank()) { val signResult = shellExecutor.execute("jarsigner -verbose -sigalg SHA1withRSA -digestalg SHA1 -keystore \"$debugKeyStore\" -storepass android -keypass android \"$apkPath\" guru_debug 2>&1"); if (signResult.success) { shellExecutor.execute("cp \"$apkPath\" \"$signedPath\" 2>/dev/null"); val r = SignApkResult(true, signedPath, "jarsigner", null); return@withContext ToolExecutionResult.success(r, json.encodeToString(SignApkResult.serializer(), r)) } else { val r = SignApkResult(false, "", "jarsigner", "Signing failed: ${signResult.stderr}"); return@withContext ToolExecutionResult.success(r, json.encodeToString(SignApkResult.serializer(), r)) } }
        val r = SignApkResult(false, "", "none", "No signing tool available."); ToolExecutionResult.success(r, json.encodeToString(SignApkResult.serializer(), r))
    }

    private fun parseAaptOutput(output: String): ParsedAapt {
        var packageName = ""; var versionName = ""; var versionCode = 0; val permissions = mutableListOf<String>(); val activities = mutableListOf<String>(); val services = mutableListOf<String>(); val receivers = mutableListOf<String>(); val providers = mutableListOf<String>()
        for (line in output.lines()) { val trimmed = line.trim(); when { trimmed.startsWith("package:") -> { packageName = Regex("name='([^']+)'").find(trimmed)?.groupValues?.get(1) ?: ""; versionName = Regex("versionName='([^']*)'").find(trimmed)?.groupValues?.get(1) ?: ""; versionCode = Regex("versionCode='([^']+)'").find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }; trimmed.startsWith("uses-permission:") -> permissions.add(trimmed.removePrefix("uses-permission:").trim().removePrefix("name='").removeSuffix("'")); trimmed.startsWith("launchable-activity:") -> activities.add(trimmed.removePrefix("launchable-activity:").trim().removePrefix("name='").removeSuffix("'")) } }
        return ParsedAapt(packageName, versionName, versionCode, permissions, activities, services, receivers, providers)
    }

    private fun extractPackageName(strings: String): String? = Regex("""([a-z][a-z0-9_]*\.){2,}[a-zA-Z][a-zA-Z0-9_]*""").findAll(strings).map { it.value }.firstOrNull { it.count { c -> c == '.' } >= 2 && it.length > 8 }

    private suspend fun getPmPackageInfo(packageName: String): PmPackageInfo {
        if (packageName == "unknown" || packageName.isEmpty()) return PmPackageInfo("", 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val result = shellExecutor.execute("pm dump $packageName 2>/dev/null | head -300"); val output = result.stdout
        var versionName = ""; var versionCode = 0; val permissions = mutableListOf<String>(); val activities = mutableListOf<String>(); val services = mutableListOf<String>(); val receivers = mutableListOf<String>(); val providers = mutableListOf<String>()
        var inPermissions = false; var inActivities = false
        for (line in output.lines()) { val trimmed = line.trim(); when { trimmed.startsWith("versionName=") -> versionName = trimmed.removePrefix("versionName="); trimmed.startsWith("versionCode=") -> versionCode = trimmed.removePrefix("versionCode=").toIntOrNull() ?: 0; trimmed == "requested permissions:" -> inPermissions = true; trimmed == "install permissions:" -> inPermissions = false; trimmed.startsWith("Activity Resolver Table:") -> inActivities = true; trimmed.startsWith("Service Resolver Table:") -> inActivities = false; inPermissions && trimmed.startsWith("android.permission.") -> permissions.add(trimmed.split(" ")[0]); inActivities && "filter" in trimmed.lowercase() && !trimmed.startsWith("Activity") -> { val parts = trimmed.split(Regex("\\s+")); val activity = parts.lastOrNull()?.takeIf { "/" in it || "." in it }; if (activity != null) activities.add(activity) } } }
        return PmPackageInfo(versionName, versionCode, permissions, activities, services, receivers, providers)
    }
}