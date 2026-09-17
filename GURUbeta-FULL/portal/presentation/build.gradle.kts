import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.unuslumen.app.presentation"
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
            freeCompilerArgs = listOf("-XXLanguage:+ContextParameters", "-opt-in=kotlin.uuid.ExperimentalUuidApi", "-opt-in=kotlin.time.ExperimentalTime")
        }
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDirs("../../assets")
        }
    }
    lint {
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {

    implementation(project(":portal:domain"))
    implementation(project(":adspace"))
    implementation(project(":notes:domain"))
    implementation(project(":tasks:domain"))
    implementation(project(":calendar:domain"))
    implementation(project(":journal:domain"))
    implementation(project(":Projects:domain"))
    implementation(project(":core:util"))

    implementation(project(":core:ui"))
    implementation(project(":core:preferences"))

    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    implementation(libs.koin.android)
    ksp(libs.koin.ksp.compiler)

    implementation(libs.kotlinx.serialization.json)

    implementation(libs.squircle.shape)

    implementation(libs.koog.agents)
}