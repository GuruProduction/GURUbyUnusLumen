package com.unuslumen.app.adspace

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json

class AdPackCache(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val cacheDir: File get() = File(context.filesDir, "adspace").apply { mkdirs() }
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

    fun hasCache(): Boolean = configFile.exists()
}