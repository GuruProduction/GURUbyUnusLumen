// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.adspace

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json

class AdPackCache(
    /**
     * Root the cache lives under. Production passes the app's private files
     * directory; a test passes a temporary directory, which is what lets the
     * boot-and-fetch sequence be proven on the JVM instead of by watching a
     * handset at launch.
     */
    private val rootDir: File,
) {

    constructor(context: Context) : this(File(context.filesDir, "adspace"))

    private val json = Json { ignoreUnknownKeys = true }

    private val cacheDir: File get() = rootDir.apply { mkdirs() }
    private val lottieDir: File get() = File(cacheDir, "lottie").apply { mkdirs() }
    private val videoDir: File get() = File(cacheDir, "video").apply { mkdirs() }
    private val imageDir: File get() = File(cacheDir, "image").apply { mkdirs() }
    private val configFile: File get() = File(cacheDir, "pack_config.json")

    fun savePack(pack: AdPack) {
        cacheDir.mkdirs()
        lottieDir.mkdirs()
        videoDir.mkdirs()
        imageDir.mkdirs()

        val configJson = json.encodeToString(AdPack.serializer(), pack)
        configFile.writeText(configJson)
    }

    fun saveEntryLottie(entryId: String, bytes: ByteArray) {
        lottieDir.mkdirs()
        val file = File(lottieDir, "${entryId}.json")
        file.writeBytes(bytes)
    }

    fun saveElementLottie(elementId: String, bytes: ByteArray) {
        lottieDir.mkdirs()
        val file = File(lottieDir, "element_${elementId}.json")
        file.writeBytes(bytes)
    }

    fun saveElementImage(elementId: String, bytes: ByteArray) {
        imageDir.mkdirs()
        val file = File(imageDir, "${elementId}.bin")
        file.writeBytes(bytes)
    }

    fun saveElementVideo(elementId: String, bytes: ByteArray) {
        videoDir.mkdirs()
        val file = File(videoDir, "${elementId}.mp4")
        file.writeBytes(bytes)
    }

    fun loadPack(): AdPack? {
        if (!configFile.exists()) return null
        return try {
            val configJson = configFile.readText()
            json.decodeFromString(AdPack.serializer(), configJson)
        } catch (_: Exception) {
            null
        }
    }

    fun getEntryLottieFile(entryId: String): File? {
        val file = File(lottieDir, "$entryId.json")
        return if (file.exists()) file else null
    }

    fun getElementLottieFile(elementId: String): File? {
        val file = File(lottieDir, "element_$elementId.json")
        return if (file.exists()) file else null
    }

    fun getElementImageFile(elementId: String): File? {
        val file = File(imageDir, "$elementId.bin")
        return if (file.exists()) file else null
    }

    fun getElementVideoFile(elementId: String): File? {
        val file = File(videoDir, "$elementId.mp4")
        return if (file.exists()) file else null
    }

    fun hasEntryLottie(entryId: String): Boolean = File(lottieDir, "$entryId.json").exists()
    fun hasElementLottie(elementId: String): Boolean = File(lottieDir, "element_$elementId.json").exists()
    fun hasElementImage(elementId: String): Boolean = File(imageDir, "$elementId.bin").exists()
    fun hasElementVideo(elementId: String): Boolean = File(videoDir, "$elementId.mp4").exists()

    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

    /**
     * Deletes media the current pack no longer references and returns how many
     * files went.
     *
     * This replaces the boot-time `clearCache()` that used to be the only thing
     * pruning this directory. That wipe was wrong (it deleted the fallback
     * before the fallback could be used, issue 38) but removing it without a
     * replacement would leak every superseded entry's lottie, image and video
     * forever. Pruning to the live pack does the same job at the right moment:
     * after a successful fetch, when the new pack is the authority on what is
     * still referenced.
     *
     * Files are matched by the names the render path looks up, so a file is kept
     * only when something in the pack can still ask for it.
     */
    fun pruneUnreferencedMedia(pack: AdPack): Int {
        val entryIds = pack.entries.map { it.id }.toSet()
        val elementIds = pack.entries.flatMap { it.elements }.map { it.id }.toSet()

        return pruneDir(lottieDir) { name ->
            val bare = name.removeSuffix(".json")
            if (bare.startsWith(ELEMENT_PREFIX)) {
                bare.removePrefix(ELEMENT_PREFIX) in elementIds
            } else {
                bare in entryIds
            }
        } + pruneDir(imageDir) { name -> name.removeSuffix(".bin") in elementIds } +
            pruneDir(videoDir) { name -> name.removeSuffix(".mp4") in elementIds }
    }

    private inline fun pruneDir(dir: File, keep: (String) -> Boolean): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (file.isFile && !keep(file.name) && file.delete()) removed++
        }
        return removed
    }

    fun hasCache(): Boolean = configFile.exists()

    private companion object {
        const val ELEMENT_PREFIX = "element_"
    }
}