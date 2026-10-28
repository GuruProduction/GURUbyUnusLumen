// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.preferences.domain.model

/**
 * A typed preference key. This is a value: two keys with the same name and the
 * same runtime type ARE the same key. Equality is explicit because a key is
 * created fresh at every call site (stringPreferencesKey("user_name") in two
 * places must compare equal), and because any cache, map, or mock that keys on
 * a PrefsKey silently misses without it.
 */
sealed class PrefsKey<T>(val name: String) {
    class IntKey(name: String): PrefsKey<Int>(name)
    class BooleanKey(name: String): PrefsKey<Boolean>(name)
    class StringSetKey(name: String): PrefsKey<Set<String>>(name)
    class StringKey(name: String): PrefsKey<String>(name)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class != this::class) return false
        return name == (other as PrefsKey<*>).name
    }

    override fun hashCode(): Int = 31 * this::class.hashCode() + name.hashCode()

    override fun toString(): String = "${this::class.simpleName}($name)"
}
fun intPreferencesKey(name: String) = PrefsKey.IntKey(name)
fun booleanPreferencesKey(name: String) = PrefsKey.BooleanKey(name)
fun stringSetPreferencesKey(name: String) = PrefsKey.StringSetKey(name)
fun stringPreferencesKey(name: String) = PrefsKey.StringKey(name)