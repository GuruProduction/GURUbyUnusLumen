import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.unuslumen.app.guru"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.unuslumen.app.gurubeta"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "3.2.0"

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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
            "zip",      // python-stdlib-arm64.zip
            "so",       // re-tools shared libraries
            "jar",      // apktool.jar, jadx.jar
            "arm64",    // toybox and standalone Unix tool binaries (curl, git, sqlite3, etc.)
            "traineddata" // tesseract language models
        )
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
    implementation(project(":adspace"))

    implementation(project(":notes:data"))
    implementation(project(":tasks:data"))
    implementation(project(":Projects:data"))
    implementation(project(":journal:data"))
    implementation(project(":calendar:data"))
    implementation(project(":portal:data"))
    implementation(project(":settings:data"))

    implementation(project(":tasks:domain"))
    implementation(project(":calendar:domain"))
    implementation(project(":journal:domain"))
    implementation(project(":portal:domain"))
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


