// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.

// Root build script. It declares the analysis plugin for every module to use
// and holds the one shared rules file. It deliberately does NOT apply detekt
// to subprojects: applying it globally collides with the Android Gradle Plugin
// on modules that also apply `com.android.library`, because detekt's plugin
// resolves Kotlin's Android target before AGP has finished configuring it.
//
// Each module opts in with one line:
//
//     alias(libs.plugins.detekt)
//
// which keeps the application order correct: the module applies its own Kotlin
// or Android plugin first, then detekt.

plugins {
    alias(libs.plugins.detekt) apply false
}

// Shared configuration, applied only by modules that opt in. A module runs
// this from its own build script after applying the plugin.
extra["guruDetektConfig"] = rootProject.file("config/detekt/detekt.yml")
extra["guruDetektBaseline"] = rootProject.file("config/detekt/baseline.xml")
