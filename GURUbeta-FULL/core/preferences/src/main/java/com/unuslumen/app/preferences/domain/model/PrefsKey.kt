// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.preferences.domain.model

sealed class PrefsKey<T>(val name: String) {
    class IntKey(name: String): PrefsKey<Int>(name)
    class BooleanKey(name: String): PrefsKey<Boolean>(name)
    class StringSetKey(name: String): PrefsKey<Set<String>>(name)
    class StringKey(name: String): PrefsKey<String>(name)
}
fun intPreferencesKey(name: String) = PrefsKey.IntKey(name)
fun booleanPreferencesKey(name: String) = PrefsKey.BooleanKey(name)
fun stringSetPreferencesKey(name: String) = PrefsKey.StringSetKey(name)
fun stringPreferencesKey(name: String) = PrefsKey.StringKey(name)