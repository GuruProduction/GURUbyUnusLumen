import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.unuslumen.app.data.portal"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            freeCompilerArgs.addAll(listOf("-opt-in=kotlin.uuid.ExperimentalUuidApi", "-opt-in=kotlin.time.ExperimentalTime"))
        }
    }
}

val modelAssetDir = project.file("src/main/assets")
val modelFile = file("$modelAssetDir/embedding_gemma_no_normalize_q8.tflite")
val tokenizerFile = file("$modelAssetDir/tokenizer.model")

// Expected SHA-256 checksums for downloaded files
// Note: Replace these with actual checksums from the model release
val expectedModelSha256 = "d4304845ba7d0e2300509b25daf29c25be176fbd9787a666a34fe3d36a1f8587"
val expectedTokenizerSha256 = "1299c11d7cf632ef3b4e11937501358ada021bbdf7c47638d13c0ee982f2e79c"

fun calculateSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun downloadFile(url: String, destFile: File, expectedSha256: String? = null) {
    val uri = URI.create(url)
    uri.toURL().openStream().use { input ->
        destFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
    
    if (expectedSha256 != null) {
        val actualSha256 = calculateSha256(destFile)
        if (actualSha256 != expectedSha256) {
            destFile.delete()
            throw GradleException(
                "SHA-256 checksum mismatch for ${destFile.name}!\n" +
                "Expected: $expectedSha256\n" +
                "Actual:   $actualSha256\n" +
                "The downloaded file may be corrupted or tampered with."
            )
        }
        logger.lifecycle("SHA-256 verified for ${destFile.name}")
    }
}

val downloadModel by tasks.registering {
    description = "Downloads EmbeddingGemma TFLite model and tokenizer from HuggingFace for on-device embedding"
    group = "build setup"

    inputs.property("model_url", "https://huggingface.co/kamalkraj/embeddinggemma-300m-litert/resolve/main/embedding_gemma_no_normalize_q8.tflite")
    inputs.property("vocab_url", "https://huggingface.co/kamalkraj/embeddinggemma-300m-litert/resolve/main/tokenizer.model")

    outputs.files(modelFile, tokenizerFile)

    doLast {
        modelAssetDir.mkdirs()
        
        if (!modelFile.exists()) {
            logger.lifecycle("Downloading EmbeddingGemma TFLite model (322MB)...")
            try {
                downloadFile(
                    url = inputs.properties["model_url"] as String,
                    destFile = modelFile,
                    expectedSha256 = expectedModelSha256
                )
                logger.lifecycle("Model downloaded and verified to ${modelFile.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to download model: ${e.message}")
                throw e
            }
        } else {
            logger.lifecycle("Model already exists, verifying checksum...")
            val actualSha256 = calculateSha256(modelFile)
            if (actualSha256 != expectedModelSha256) {
                logger.warn("Model checksum mismatch, re-downloading...")
                modelFile.delete()
                downloadFile(
                    url = inputs.properties["model_url"] as String,
                    destFile = modelFile,
                    expectedSha256 = expectedModelSha256
                )
            } else {
                logger.lifecycle("Model checksum verified")
            }
        }

        if (!tokenizerFile.exists()) {
            logger.lifecycle("Downloading tokenizer.model...")
            try {
                downloadFile(
                    url = inputs.properties["vocab_url"] as String,
                    destFile = tokenizerFile,
                    expectedSha256 = expectedTokenizerSha256
                )
                logger.lifecycle("Tokenizer downloaded and verified to ${tokenizerFile.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to download tokenizer: ${e.message}")
                throw e
            }
        } else {
            logger.lifecycle("Tokenizer already exists, verifying checksum...")
            val actualSha256 = calculateSha256(tokenizerFile)
            if (actualSha256 != expectedTokenizerSha256) {
                logger.warn("Tokenizer checksum mismatch, re-downloading...")
                tokenizerFile.delete()
                downloadFile(
                    url = inputs.properties["vocab_url"] as String,
                    destFile = tokenizerFile,
                    expectedSha256 = expectedTokenizerSha256
                )
            } else {
                logger.lifecycle("Tokenizer checksum verified")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadModel)
}

val voskModelDir = project.file("src/main/assets")
val voskModelZip = file("$voskModelDir/model-vosk-en-us-small.zip")

// SHA-256 of vosk-model-small-en-us-0.15.zip from alphacephei.com (verified 2026-09-13)
val expectedVoskSha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"

val downloadVoskModel by tasks.registering {
    description = "Downloads the Vosk en-US small speech model for fully on-device transcription"
    group = "build setup"

    inputs.property("model_url", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")

    outputs.file(voskModelZip)

    doLast {
        voskModelDir.mkdirs()
        if (!voskModelZip.exists()) {
            logger.lifecycle("Downloading Vosk en-US small speech model (~41MB)...")
            try {
                downloadFile(
                    url = inputs.properties["model_url"] as String,
                    destFile = voskModelZip,
                    expectedSha256 = expectedVoskSha256
                )
                logger.lifecycle("Vosk model downloaded and verified to ${voskModelZip.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to download Vosk model: ${e.message}")
                throw e
            }
        } else {
            logger.lifecycle("Vosk model already present, verifying checksum...")
            val actual = calculateSha256(voskModelZip)
            if (actual != expectedVoskSha256) {
                logger.warn("Vosk model checksum mismatch, re-downloading...")
                voskModelZip.delete()
                downloadFile(
                    url = inputs.properties["model_url"] as String,
                    destFile = voskModelZip,
                    expectedSha256 = expectedVoskSha256
                )
            } else {
                logger.lifecycle("Vosk model checksum verified")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(downloadVoskModel)
}

dependencies {
    implementation(project(":portal:domain"))
    implementation(project(":core:preferences"))
    implementation(project(":core:database"))
    implementation(project(":core:util"))
    implementation(project(":core:alarm"))

    implementation(project(":notes:domain"))
    implementation(project(":tasks:domain"))
    implementation(project(":calendar:domain"))
    implementation(project(":journal:domain"))
    implementation(project(":Projects:domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.okhttp)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)
    ksp(libs.koin.ksp.compiler)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.koog.agents)

    implementation(libs.litert)
    implementation(libs.litert.support)
    implementation(libs.litert.gpu)
    implementation(libs.androidx.work.runtime.ktx)

    implementation("org.jsoup:jsoup:1.17.2")
    implementation(libs.zxing.core)
    implementation(libs.androidx.webkit)
    implementation(libs.tor.android)
    implementation(libs.jtorctl)
    implementation(libs.slf4j.simple)

    // On-device speech recognition (hearAudio / transcribeAudio) — Vosk, fully
    // offline, no API keys. Model zip is bundled into assets and downloaded at
    // build time by the downloadVoskModel task below.
    implementation("com.alphacephei:vosk-android:0.3.47")
}