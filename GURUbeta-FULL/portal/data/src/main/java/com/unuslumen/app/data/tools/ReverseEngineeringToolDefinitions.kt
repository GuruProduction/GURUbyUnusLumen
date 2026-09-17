package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ReverseEngineeringToolDefinitions : ToolSetRegistration {
    const val LIST_INSTALLED_PACKAGES = "listInstalledPackages"; const val PM_DUMP = "pmDump"
    const val PULL_APK = "pullApk"; const val LIST_APK_CONTENTS = "listApkContents"
    const val EXTRACT_APK = "extractApk"; const val READ_MANIFEST = "readManifest"
    const val EXTRACT_DEX = "extractDex"; const val EXTRACT_NATIVE_LIBS = "extractNativeLibs"
    const val EXTRACT_RESOURCES = "extractResources"; const val DECOMPILE_APK = "decompileApk"
    const val MOD_APK = "modApk"; const val SIGN_APK = "signApk"

    override val definitions = listOf(
        ToolDefinition(name = LIST_INSTALLED_PACKAGES, description = "List all installed packages on the device. Filter by system apps, user apps, or search by package name.", category = "reverse_engineering", parameters = listOf(ToolParameter("filter", ToolParameterType.String, false, "Filter: all, user, system, or search term"), ToolParameter("limit", ToolParameterType.Integer, false, "Max results, default 100")), permissions = emptyList()),
        ToolDefinition(name = PM_DUMP, description = "Run 'pm dump' on a package to get detailed info: permissions, activities, services, receivers, providers.", category = "reverse_engineering", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name")), permissions = emptyList()),
        ToolDefinition(name = PULL_APK, description = "Pull an APK from an installed app. First finds the APK path using pm, then copies it.", category = "reverse_engineering", parameters = listOf(ToolParameter("packageName", ToolParameterType.String, true, "Package name"), ToolParameter("outputDir", ToolParameterType.String, false, "Output directory")), permissions = emptyList()),
        ToolDefinition(name = LIST_APK_CONTENTS, description = "List all files inside an APK without extracting.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "Path to APK file")), permissions = emptyList()),
        ToolDefinition(name = EXTRACT_APK, description = "Extract the entire APK to a directory. Unpacks everything: manifest, DEX files, resources, native libraries.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK path"), ToolParameter("outputDir", ToolParameterType.String, true, "Output directory")), permissions = emptyList()),
        ToolDefinition(name = READ_MANIFEST, description = "Read and parse an AndroidManifest.xml from an APK. Uses bundled aapt2 for full binary XML decoding.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK path or extracted directory")), permissions = emptyList()),
        ToolDefinition(name = EXTRACT_DEX, description = "Extract classes.dex from an APK. This is the app's executable code.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK path"), ToolParameter("outputDir", ToolParameterType.String, true, "Output directory")), permissions = emptyList()),
        ToolDefinition(name = EXTRACT_NATIVE_LIBS, description = "Extract native libraries (.so files) from an APK.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK path"), ToolParameter("outputDir", ToolParameterType.String, true, "Output directory"), ToolParameter("abi", ToolParameterType.String, false, "CPU architecture: arm64-v8a, armeabi-v7a, x86_64, x86")), permissions = emptyList()),
        ToolDefinition(name = EXTRACT_RESOURCES, description = "Extract resource files from an APK: XML layouts, drawables, strings, assets.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK path"), ToolParameter("outputDir", ToolParameterType.String, true, "Output directory"), ToolParameter("resourceType", ToolParameterType.String, false, "What to extract: all, layouts, drawables, strings, assets, raw")), permissions = emptyList()),
        ToolDefinition(name = DECOMPILE_APK, description = "Decompile an APK back to source. Uses bundled jadx, apktool, and dex2jar.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "APK path"), ToolParameter("outputDir", ToolParameterType.String, true, "Output directory"), ToolParameter("method", ToolParameterType.String, false, "auto, jadx, apktool, dex2jar, extract_only")), permissions = emptyList()),
        ToolDefinition(name = MOD_APK, description = "Modify an APK by unpacking, applying patches, and repacking.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "Original APK path"), ToolParameter("modDir", ToolParameterType.String, true, "Directory with modifications"), ToolParameter("outputPath", ToolParameterType.String, true, "Output path for modded APK")), permissions = emptyList()),
        ToolDefinition(name = SIGN_APK, description = "Sign an APK so it can be installed. Uses a self-generated debug keystore.", category = "reverse_engineering", parameters = listOf(ToolParameter("apkPath", ToolParameterType.String, true, "Unsigned APK path"), ToolParameter("outputPath", ToolParameterType.String, false, "Output path for signed APK")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ReverseEngineeringToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}