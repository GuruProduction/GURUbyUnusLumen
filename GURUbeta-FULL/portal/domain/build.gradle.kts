plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    implementation(project(":core:preferences"))
    implementation(project(":notes:domain"))
    implementation(project(":tasks:domain"))
    implementation(project(":calendar:domain"))
    implementation(project(":journal:domain"))
    implementation(project(":Projects:domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin)
    ksp(libs.koin.ksp.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}