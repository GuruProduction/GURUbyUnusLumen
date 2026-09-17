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
    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
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
}