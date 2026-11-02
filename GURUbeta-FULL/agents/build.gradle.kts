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
    namespace = "com.unuslumen.app.agents"
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
    lint {
        disable += "NullSafeMutableLiveData"
    }
    testOptions {
        unitTests {
            // Robolectric reads this module's own merged manifest and resources, so the
            // container test runs against the real Android framework rather than stubs.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // The container test builds a real Koin graph and asks it for the view model. ViewModel's
    // constructor needs Android's main looper, so Robolectric supplies the framework on the
    // JVM. Same versions portal:data uses, so the build has one Robolectric rather than two.
    testImplementation(libs.mockk)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)
    ksp(libs.koin.ksp.compiler)

    implementation(libs.kotlinx.serialization.json)

    // The house design language, so the room wears the same paper and ink as
    // every other page rather than carrying its own copy of the palette.
    implementation(project(":core:ui"))
    // The stores the room reads and writes: the agent table and the catalogue
    // client. Those are shared app state, not this room's private property.
    implementation(project(":portal:domain"))
    implementation(project(":portal:data"))
    // The one-preference lane the intro flag uses.
    implementation(project(":core:preferences"))
}
