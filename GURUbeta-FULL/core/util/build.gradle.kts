// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    // The toolchain manifest inside the bundled archive is JSON, and reading it
    // with a hand-written regex would break the moment the file is reformatted.
    // This module had no serialization before; the plugin is what makes the
    // generated serializer exist, so it belongs here rather than in a
    // hand-rolled parser.
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.unuslumen.app.util"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
            freeCompilerArgs.addAll(listOf("-opt-in=kotlin.uuid.ExperimentalUuidApi", "-opt-in=kotlin.time.ExperimentalTime"))
        }
    }
    buildFeatures {
        compose = true
        // The Shizuku shell service crosses a UID boundary, so its binder surface is
        // declared in AIDL rather than serialised by hand. With AIDL off the
        // IShizukuShell stub is never generated and the bridge cannot compile.
        aidl = true
    }
    lint {
        disable += "NullSafeMutableLiveData"
    }

    // Robolectric reads the merged manifest and resources, so the Android it
    // emulates is this module's own rather than a generic platform.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {

    implementation(platform(libs.koin.bom))
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Robolectric, for the toolchain manifest reader test. The reader takes a
    // Context to reach assets, so it needs an Android runtime to construct; the
    // logic under test is file IO, so running it on the JVM is honest and costs
    // seconds rather than an emulator session. Same versions as portal:data,
    // where this pattern is already established.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    api(libs.kotlinx.datetime)

    // Shizuku. `api` for the api module because ShizukuBridge exposes
    // Shizuku.OnRequestPermissionResultListener in its public surface, and a
    // consumer of this module cannot implement that listener without the type.
    //
    // The provider module supplies the ShizukuProvider ContentProvider, which is
    // how Shizuku's binder becomes reachable; it is wired in the app manifest, so
    // it is `api` here too and the app module inherits it rather than repeating it.
    api(libs.shizuku.api)
    api(libs.shizuku.provider)

    // Reading the toolchain manifest. `implementation` rather than `api`:
    // nothing outside this module needs the JSON types, only the parsed model.
    implementation(libs.kotlinx.serialization.json)
}