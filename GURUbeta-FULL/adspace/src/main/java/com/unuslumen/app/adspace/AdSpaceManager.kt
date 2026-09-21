package com.unuslumen.app.adspace

import android.content.Context
import android.util.Log
import com.unuslumen.app.data.tor.TorEgress
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AdSpaceManager(
    private val context: Context,
    private val cache: AdPackCache,
) : KoinComponent {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sovereign egress: the ad-pack client rides TorEgress like every other
     * outbound call, so the user's IP never leaks to the API host over
     * clearnet — the ad poll stays on its exact 30-second cadence, but now
     * anonymous. Fail-closed when Tor is down: the existing AdPackCache keeps
     * the ad space showing content until the next successful fetch.
     */
    private val httpClient = HttpClient(TorEgress.newEngine()) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private val _currentEntry = MutableStateFlow<AdEntry?>(null)
    val currentEntry: StateFlow<AdEntry?> = _currentEntry.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var currentPack: AdPack? = null
    val pack: AdPack? get() = currentPack
    private var entryIndex = 0
    private var rotationJob: Job? = null
    private var pollJob: Job? = null
    private var lastBaseUrl: String? = null

    fun setActive(active: Boolean) {
        _isActive.value = active
        if (active) {
            startRotation()
        } else {
            stopRotation()
        }
    }

    private fun startRotation() {
        val pack = currentPack ?: return
        if (pack.entries.isEmpty()) return

        stopRotation()

        _currentEntry.value = getNextEntry(pack)

        rotationJob = scope.launch {
            while (_isActive.value) {
                delay(pack.entryDurationMs)
                if (!_isActive.value) break
                val entry = getNextEntry(pack) ?: return@launch
                _currentEntry.value = entry
            }
        }
    }

    private fun stopRotation() {
        rotationJob?.cancel()
        rotationJob = null
    }

    private fun getNextEntry(pack: AdPack): AdEntry? {
        val activeEntries = pack.entries.sortedBy { it.orderIndex }
        if (activeEntries.isEmpty()) return null

        when (pack.rotationMode) {
            RotationMode.SEQUENTIAL -> {
                val entry = activeEntries[entryIndex % activeEntries.size]
                entryIndex = (entryIndex + 1) % activeEntries.size
                return entry
            }
            RotationMode.SHUFFLE -> {
                return activeEntries.random()
            }
        }
    }

    fun fetchAdPack(baseUrl: String = "https://api.unuslumen.com") {
        scope.launch {
            try {
                Log.d("AdSpaceManager", "fetchAdPack: url=$baseUrl/api/adpack")
                val response = httpClient.get("$baseUrl/api/adpack")
                Log.d("AdSpaceManager", "fetchAdPack: HTTP ${response.status.value}")
                if (!response.status.isSuccess()) {
                    val body = response.bodyAsText()
                    Log.e("AdSpaceManager", "fetchAdPack: non-success response: $body")
                    loadFromCache()
                    return@launch
                }
                val bodyText = response.bodyAsText()
                Log.d("AdSpaceManager", "fetchAdPack: response body length=${bodyText.length}")
                val parsed = json.decodeFromString(AdPackResponse.serializer(), bodyText)
                Log.d("AdSpaceManager", "fetchAdPack: parsed pack id=${parsed.id}, entries=${parsed.entries.size}")

                val pack = parsed.toDomain()

                // Download all media files before activating the pack
                downloadMediaFiles(pack, baseUrl)

                currentPack = pack
                cache.savePack(pack)
                entryIndex = 0
                if (_isActive.value) startRotation()

                Log.d("AdSpaceManager", "fetchAdPack: success. pack=${pack.entries.size} entries, first entry animationConfig=${pack.entries.firstOrNull()?.animationConfig != null}")
            } catch (e: Exception) {
                Log.e("AdSpaceManager", "fetchAdPack: exception", e)
                loadFromCache()
            }
        }
    }

    private suspend fun downloadMediaFiles(pack: AdPack, baseUrl: String) {
        for (entry in pack.entries) {
            // Download entry-level lottie if file name exists and not cached
            if (entry.lottieData.isEmpty() && !cache.hasEntryLottie(entry.id)) {
                try {
                    val resp = httpClient.get("$baseUrl/api/adpack/entry-media/${entry.id}")
                    if (resp.status.isSuccess()) {
                        val bytes: ByteArray = resp.body()
                        cache.saveEntryLottie(entry.id, bytes)
                        Log.d("AdSpaceManager", "downloaded entry lottie for ${entry.id}, ${bytes.size} bytes")
                    }
                } catch (e: Exception) {
                    Log.w("AdSpaceManager", "failed to download entry lottie for ${entry.id}: ${e.message}")
                }
            }

            // Download element-level media
            for (element in entry.elements) {
                // Element lottie
                if (element.lottieFileName != null && !cache.hasElementLottie(element.id)) {
                    try {
                        val resp = httpClient.get("$baseUrl/api/adpack/media/${element.id}?type=lottie")
                        if (resp.status.isSuccess()) {
                            val bytes: ByteArray = resp.body()
                            cache.saveElementLottie(element.id, bytes)
                            Log.d("AdSpaceManager", "downloaded element lottie for ${element.id}, ${bytes.size} bytes")
                        }
                    } catch (e: Exception) {
                        Log.w("AdSpaceManager", "failed to download element lottie for ${element.id}: ${e.message}")
                    }
                }

                // Element image
                if (element.imageFileName != null && !cache.hasElementImage(element.id)) {
                    try {
                        val resp = httpClient.get("$baseUrl/api/adpack/media/${element.id}?type=image")
                        if (resp.status.isSuccess()) {
                            val bytes: ByteArray = resp.body()
                            cache.saveElementImage(element.id, bytes)
                            Log.d("AdSpaceManager", "downloaded element image for ${element.id}, ${bytes.size} bytes")
                        }
                    } catch (e: Exception) {
                        Log.w("AdSpaceManager", "failed to download element image for ${element.id}: ${e.message}")
                    }
                }

                // Element video
                if (element.videoFileName != null && !cache.hasElementVideo(element.id)) {
                    try {
                        val resp = httpClient.get("$baseUrl/api/adpack/media/${element.id}?type=video")
                        if (resp.status.isSuccess()) {
                            val bytes: ByteArray = resp.body()
                            cache.saveElementVideo(element.id, bytes)
                            Log.d("AdSpaceManager", "downloaded element video for ${element.id}, ${bytes.size} bytes")
                        }
                    } catch (e: Exception) {
                        Log.w("AdSpaceManager", "failed to download element video for ${element.id}: ${e.message}")
                    }
                }
            }
        }
    }

    private fun loadFromCache() {
        val cached = cache.loadPack()
        if (cached != null) {
            currentPack = cached
            if (_isActive.value) startRotation()
        }
    }

    fun initialize(baseUrl: String = "https://api.unuslumen.com") {
        Log.d("AdSpaceManager", "initialize: baseUrl=$baseUrl, hasCache=${cache.hasCache()}")
        lastBaseUrl = baseUrl
        cache.clearCache()
        fetchAdPack(baseUrl)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(30_000)
                Log.d("AdSpaceManager", "poll: re-fetching ad pack")
                fetchAdPack(lastBaseUrl ?: "https://api.unuslumen.com")
            }
        }
    }
}