// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.compose.compiler)
}

// Release signing credentials, read from the gitignored local.properties so the
// password never enters the repository. Every GURU release before this was built
// unsigned and signed afterwards by hand with apksigner, which meant the one
// step that cannot be automated or reproduced lived in a human's memory.
//
// Absent keys are not an error: a machine without the keystore (CI, another
// developer, a fresh clone) still builds debug normally and produces an
// unsigned release exactly as before, rather than failing to configure at all.
val signingProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val guruStoreFile = signingProperties.getProperty("guru.signing.store")
val guruStorePassword = signingProperties.getProperty("guru.signing.storePassword")
val guruKeyAlias = signingProperties.getProperty("guru.signing.alias")
val guruKeyPassword = signingProperties.getProperty("guru.signing.keyPassword")
val hasReleaseSigning = !guruStoreFile.isNullOrBlank() &&
    !guruStorePassword.isNullOrBlank() &&
    !guruKeyAlias.isNullOrBlank() &&
    File(guruStoreFile).exists()

// Phase 0 packaging fix: Vosk's Java layer talks to Android through JNA, and
// vosk-android 0.3.47 declares net.java.dev.jna:jna:5.13.0 (aar). A sibling
// dependency in the graph ships an older JNA (5.6.0 in the Gradle cache), so
// without a pin the merged runtime classpath can drift between the dex'd
// com.sun.jna classes and the shipped libjnidispatch.so; Native crashes first
// link with UnsatisfiedLinkError "Can't obtain peer field ID for
// com.sun.jna.Pointer". Forcing one version aligns jar + native everywhere.
configurations.all {
    resolutionStrategy {
        force("net.java.dev.jna:jna:5.13.0")
        // Instrumentation deps pull androidx.concurrent 1.2.0 while the resolved
        // graph pins 1.1.0, which fails the androidTest classpath with
        // "multiple version constraints with conflicting requirements". Both are
        // needed at 1.2.0 for the test APK to resolve at all; the app's own
        // runtime graph is unaffected because this only lifts a transitive
        // version to what the test libraries already require.
        force("androidx.concurrent:concurrent-futures:1.2.0")
        force("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }
}

android {
    namespace = "com.unuslumen.app.guru"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.unuslumen.app.gurubeta"
        minSdk = 26
        targetSdk = 35
        versionCode = 71
        versionName = "3.47"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

    }

    // AGP will auto-download this NDK version
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // No extra jniLibs source directory is needed for the Python launcher.
    //
    // AGP packages an add_executable target when its output name looks like a
    // shared library, and this one is named libpython_launcher.so, so it is
    // merged into lib/<abi>/ by the same mechanism as libquickjs.so. Adding a
    // hand-declared directory as well produced a duplicate-file failure in
    // mergeDebugNativeLibs, which is how that was established rather than
    // assumed.

    // The keystore that every shipped GURU release was signed with. Installing a
    // build signed with any other key over an existing GURU is refused by
    // Android, so this must stay the guru-fresh.jks key, never a regenerated one.
    signingConfigs {
        if (hasReleaseSigning) {
            create("guruRelease") {
                storeFile = File(guruStoreFile!!)
                storePassword = guruStorePassword
                keyAlias = guruKeyAlias
                keyPassword = guruKeyPassword

                // EVERY SIGNATURE SCHEME, EXPLICITLY.
                //
                // These were left at their defaults, which produced a v2-only APK.
                // v2 alone is valid on modern Android, but it leaves nothing to fall
                // back on: an installer that rejects the v2 block for any reason has
                // no v1 JAR signature or v3 block to try, and reports the package as
                // invalid. v3 is also what carries key-rotation proof, so a future
                // key change can be authorised rather than refused.
                //
                // v1 is deliberately on even though minSdk is 26: it costs a few
                // hundred KB of digests and it is the scheme every installer on every
                // version understands. v4 is produced as a separate .idsig beside the
                // APK for adb incremental installs and is harmless to have.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach signing when the credentials are present, so the
            // build stays honest: signed when it can be, unsigned when it
            // cannot, never silently signed with a different key.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("guruRelease")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            isDebuggable = true
            resValue("string", "app_name", "GURUbeta")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Python stdlib zip must NOT be re-compressed by AGP — PythonProvider copies it
    // to app storage and uses ZipFile to extract it. The inner zip data must arrive
    // unadulterated. The python3.14 binary is also bundled as an asset.
    aaptOptions {
        noCompress += listOf(
            "zip",      // python-stdlib-arm64.zip, git-termux.zip, re-toolchain.zip
            "so",       // re-tools shared libraries
            "jar",      // apktool.jar, jadx.jar
            "arm64",    // toybox and standalone Unix tool binaries (curl, git, sqlite3, etc.)
            "traineddata" // tesseract language models
        )
        // The toolchain archive is 72 MB and already compressed internally, and
        // TermuxProvider reads it with ZipFile after copying it to app storage.
        // Letting AGP deflate it a second time costs build time, buys nothing,
        // and would make the copy in app storage differ from the asset — which
        // is exactly the kind of silent mismatch that produced issue 200's
        // "asset present but nothing in it" confusion in the first place.
        noCompress += "re-toolchain.zip"
    }
    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }
    lint {
        disable.add("MissingTranslation")
        disable.add("NullSafeMutableLiveData")
    }
}

dependencies {

    implementation(project(":notes:presentation"))
    implementation(project(":tasks:presentation"))
    implementation(project(":Projects:presentation"))
    implementation(project(":calendar:presentation"))
    implementation(project(":journal:presentation"))
    implementation(project(":settings:presentation"))
    implementation(project(":portal:presentation"))
    implementation(project(":thoughts:presentation"))
    implementation(project(":onboarding:presentation"))
    implementation(project(":adspace"))
    // OTTIO IS A ROOM OF ITS OWN, so it is a module of its own. A room that lives
    // inside another room's module cannot be moved, tested or replaced on its own,
    // and every room GURU ever builds follows the same rule.
    implementation(project(":ottio"))
    implementation(project(":agents"))
    implementation(project(":heartbeat"))
    implementation(project(":innermind"))

    implementation(project(":notes:data"))
    implementation(project(":tasks:data"))
    implementation(project(":Projects:data"))
    implementation(project(":journal:data"))
    implementation(project(":calendar:data"))
    implementation(project(":portal:data"))
    implementation(project(":thoughts:data"))
    implementation(project(":onboarding:data"))
    implementation(project(":settings:data"))
    implementation(project(":core:database"))

    implementation(project(":tasks:domain"))
    implementation(project(":calendar:domain"))
    implementation(project(":journal:domain"))
    implementation(project(":portal:domain"))
    implementation(project(":thoughts:domain"))
    implementation(project(":onboarding:domain"))
    implementation(project(":Projects:domain"))
    implementation(project(":core:notification"))
    implementation(project(":core:ui"))
    implementation(project(":core:di"))
    implementation(project(":core:alarm"))
    implementation(project(":core:database"))
    implementation(project(":widget"))
    implementation(project(":core:preferences"))
    implementation(project(":core:util"))

    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.test.junit4)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.biometric)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)
    implementation(libs.koin.android.workmanager)
    ksp(libs.koin.ksp.compiler)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.logging)

    implementation(libs.squircle.shape)
}

// KSP order fix: app's koin-ksp pass reads the classes.jar published by the
// thoughts:presentation AAR (ThoughtsPresentationModule). That jar is built
// by the module's bundleLibCompileToJarDebug task; without an explicit
// dependency, Gradle can run app:kspDebugKotlin against a stale or missing
// jar and KSP throws FileNotFoundException. Enforce the order.
androidComponents {
    onVariants { variant ->
        tasks.matching { task ->
            task.name == "ksp${variant.name.replaceFirstChar { c -> c.uppercase() }}Kotlin"
        }.configureEach {
            dependsOn(project(":thoughts:presentation").tasks.matching { t ->
                t.name == "bundle${variant.name.replaceFirstChar { c -> c.uppercase() }}ToJarDebug" ||
                    t.name == "bundleLibCompileToJarDebug"
            })
        }
    }
}


