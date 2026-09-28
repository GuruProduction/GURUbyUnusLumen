plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(project(":portal:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    ksp(libs.koin.ksp.compiler)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-opt-in=kotlin.uuid.ExperimentalUuidApi", "-opt-in=kotlin.time.ExperimentalTime"))
    }
}