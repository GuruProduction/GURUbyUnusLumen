// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.unuslumen.app.database"
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
    testOptions {
        // Robolectric needs the module's resources, because the database under test resolves
        // through the app's own Context rather than a stub.
        unitTests.isIncludeAndroidResources = true
    }
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_1_8
            freeCompilerArgs.addAll(listOf("-opt-in=kotlin.uuid.ExperimentalUuidApi", "-opt-in=kotlin.time.ExperimentalTime"))
        }
    }
}

dependencies {
    implementation(project(":tasks:domain"))
    implementation(project(":notes:domain"))
    implementation(project(":Projects:domain"))
    implementation(project(":journal:domain"))
    implementation(project(":core:alarm"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)

    // Robolectric, because the snapshot engine's central claim is about SQLite's own
    // write-ahead log: that a copy taken while pages are still in it contains those pages.
    // That cannot be proved in a pure JVM unit test, which has no SQLite and no Context, and
    // it must not be left to a handset experiment that nobody repeats. Robolectric runs the
    // real framework implementation, so a database opened here behaves as it does on device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // runTest, because the engine's operations are suspend: a snapshot is a copy, a seal and
    // a full re-open, and the test needs to await that rather than block a thread on it.
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    api(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite.framework)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)
    ksp(libs.koin.ksp.compiler)

    implementation(libs.kotlinx.serialization.json)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}