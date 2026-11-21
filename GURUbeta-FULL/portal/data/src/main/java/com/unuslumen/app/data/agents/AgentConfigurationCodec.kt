// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import com.unuslumen.app.domain.model.AgentAutonomy
import com.unuslumen.app.domain.model.AgentBudgets
import com.unuslumen.app.domain.model.AgentCapabilityManifest
import com.unuslumen.app.domain.model.AgentContextScope
import com.unuslumen.app.domain.model.AgentDefinitionSnapshot
import com.unuslumen.app.domain.model.AgentMemoryScope
import com.unuslumen.app.domain.model.AgentModelPreference
import com.unuslumen.app.domain.model.AgentNotifications
import com.unuslumen.app.domain.model.AgentVisibility
import com.unuslumen.app.domain.model.AgentVisual
import com.unuslumen.app.domain.model.GuruAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns an agent's configuration documents into JSON for storage and back again.
 *
 * WHY A CODEC RATHER THAN ROOM TYPE CONVERTERS. A converter is registered per type and applies
 * everywhere that type appears, which is right for a value object used in many tables. These
 * documents belong to exactly one table's columns and their schema version is part of their
 * meaning, so encoding them here keeps the version handling beside the decode rather than spread
 * across the database module.
 *
 * EVERY DECODE FALLS BACK TO THE DOMAIN DEFAULT AND NEVER THROWS. A row written by a newer build,
 * a truncated write, or the versioned empty envelope `{"schemaVersion":1}` that every existing row
 * carries all have to produce a usable agent. Throwing would turn an upgrade into a crash on a
 * device whose owner cannot do anything about it; falling back produces the behaviour that agent
 * had before the field existed, which is the honest reading of a document that does not say
 * anything.
 *
 * A FALLBACK IS SILENT HERE AND THAT IS THE ONE JUDGEMENT IN THIS FILE. The alternative is a log
 * line per field per agent per load, which nobody reads. What matters is that a *requested* setting
 * is never silently ignored at the point of use, and that is the validator's job, not the codec's:
 * a document that decodes to defaults did not request anything.
 */
object AgentConfigurationCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A missing field in an older document must not fail the whole decode.
        coerceInputValues = true
    }

    /** The document every new column defaults to, so an old row is readable rather than absent. */
    const val EMPTY_DOCUMENT = "{\"schemaVersion\":1}"

    fun encodeVisual(value: AgentVisual): String = encode(AgentVisual.serializer(), value)
    fun decodeVisual(stored: String): AgentVisual = decode(stored, AgentVisual.serializer(), AgentVisual.DEFAULT)

    fun encodeVisibility(value: AgentVisibility): String = encode(AgentVisibility.serializer(), value)
    fun decodeVisibility(stored: String): AgentVisibility =
        decode(stored, AgentVisibility.serializer(), AgentVisibility.TRANSPARENT)

    fun encodeAutonomy(value: AgentAutonomy): String = encode(AgentAutonomy.serializer(), value)
    fun decodeAutonomy(stored: String): AgentAutonomy =
        decode(stored, AgentAutonomy.serializer(), AgentAutonomy.DEFAULT)

    fun encodeBudgets(value: AgentBudgets): String = encode(AgentBudgets.serializer(), value)
    fun decodeBudgets(stored: String): AgentBudgets =
        decode(stored, AgentBudgets.serializer(), AgentBudgets.DEFAULT)

    fun encodeModelPreference(value: AgentModelPreference): String =
        encode(AgentModelPreference.serializer(), value)
    fun decodeModelPreference(stored: String): AgentModelPreference =
        decode(stored, AgentModelPreference.serializer(), AgentModelPreference.INHERIT)

    fun encodeNotifications(value: AgentNotifications): String =
        encode(AgentNotifications.serializer(), value)
    fun decodeNotifications(stored: String): AgentNotifications =
        decode(stored, AgentNotifications.serializer(), AgentNotifications.DEFAULT)

    fun encodeMemoryScope(value: AgentMemoryScope): String =
        encode(AgentMemoryScope.serializer(), value)
    fun decodeMemoryScope(stored: String): AgentMemoryScope =
        decode(stored, AgentMemoryScope.serializer(), AgentMemoryScope.NONE)

    fun encodeContextScope(value: AgentContextScope): String =
        encode(AgentContextScope.serializer(), value)
    fun decodeContextScope(stored: String): AgentContextScope =
        decode(stored, AgentContextScope.serializer(), AgentContextScope.ASSIGNMENT_ONLY)

    fun encodeManifest(value: AgentCapabilityManifest): String =
        encode(AgentCapabilityManifest.serializer(), value)
    fun decodeManifest(stored: String): AgentCapabilityManifest =
        decode(stored, AgentCapabilityManifest.serializer(), AgentCapabilityManifest.EMPTY)

    /** The whole definition, for a history row. Lives here so the entity stays a database concern. */
    fun encodeDefinition(value: GuruAgent): String = encode(AgentDefinitionSnapshot.serializer(), value.definitionSnapshot())

    // ------------------------------------------------------------------

    /** Encode, stamping the schema version. A document without one cannot be read honestly later. */
    fun <T> encode(
        serializer: kotlinx.serialization.SerializationStrategy<T>,
        value: T,
    ): String {
        val encoded = json.encodeToString(serializer, value)
        val obj = runCatching {
            json.parseToJsonElement(encoded) as? JsonObject
        }.getOrNull() ?: return encoded
        if (obj.containsKey("schemaVersion")) return encoded
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(obj + ("schemaVersion" to JsonPrimitive(CURRENT_SCHEMA_VERSION))),
        )
    }

    /**
     * Decode, falling back rather than throwing.
     *
     * The empty envelope is recognised explicitly so the common case — every existing row — costs
     * no exception. A genuinely damaged document also falls back, because an agent that loads with
     * defaults is recoverable and an agent that cannot load is a crash in a list.
     */
    private fun <T> decode(
        stored: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
        fallback: T,
    ): T {
        if (stored.isBlank() || stored == EMPTY_DOCUMENT) return fallback
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(fallback)
    }

    /** The schema version these documents are written at. Bump only with a decode path for older. */
    const val CURRENT_SCHEMA_VERSION = 1
}
