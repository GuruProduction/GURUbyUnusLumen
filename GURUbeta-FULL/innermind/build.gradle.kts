// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose.compiler)
}

android {
    namespace = "com.unuslumen.app.innermind"
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
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // The container test builds a real Koin graph and asks it for the view
            // model. ViewModel's constructor needs Android's main looper, so
            // Robolectric supplies the framework on the JVM.
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
    testImplementation(libs.mockk)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)

    // The house design language, so the room wears the same paper and ink as
    // every other page rather than carrying its own copy of the palette.
    implementation(project(":core:ui"))

    // The tables this room draws. Every one of them is shared app state, so the
    // room reads them rather than owning a copy.
    implementation(project(":core:database"))
    implementation(project(":portal:domain"))
    // TaskEntity carries a List<SubTask>, so reading the task table at all needs
    // the domain types it is defined in. There is no way to read one task without
    // this, because the compiler resolves the property's type even if the map
    // never looked at a subtask.
    implementation(project(":tasks:domain"))
}
