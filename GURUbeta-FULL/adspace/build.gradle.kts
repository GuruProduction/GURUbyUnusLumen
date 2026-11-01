// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.unuslumen.app.adspace"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            freeCompilerArgs = listOf("-opt-in=kotlin.uuid.ExperimentalUuidApi")
        }
    }
    buildFeatures {
        compose = true
    }
    // The boot-and-fetch sequence decides whether the ad space shows a cached
    // pack or renders nothing, and that decision is pure Kotlin. Returning
    // default values for the untouched android.* stubs (Log) lets it be proven
    // on the JVM in seconds instead of by launching the app and waiting for a
    // Tor circuit.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // JVM tests for the boot-and-fetch sequence and the cache contract.
    // ktor-client-mock lets the real HttpClient stack run against a scripted
    // transport, so the test exercises the actual request, parsing and cache
    // write path rather than a hand-written stand-in for it.
    testImplementation(libs.junit)
    testImplementation(libs.ktor.mock)
    testImplementation(libs.ktor.serialization)
    testImplementation(libs.ktor.content.negotiation)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)
    ksp(libs.koin.ksp.compiler)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.lottie.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.ktor.okhttp)
    implementation(libs.ktor.serialization)
    implementation(libs.ktor.content.negotiation)

    // Sovereign egress: TorEgress lives in portal:data's tor package. The ad
    // client rides the same fail-closed Tor policy as the rest of the app.
    implementation(project(":portal:data"))
}