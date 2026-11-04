// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

import com.unuslumen.app.data.tools.registry.ToolParameterType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how a stored tool's parameter schema is read, and specifically the one
 * thing that had no expression at all (issue 296).
 *
 * THE DEFECT. A JSON Schema `required` array was honoured, but there was no way
 * to say "optional" outside one. A flat-form author got `required = true` on
 * every property because `paramFromSpec` received null and the default was the
 * safe one. The default itself is right: a tool that silently drops an argument
 * is worse than one that demands it. What was missing was any way to override it
 * without knowing a second schema dialect existed.
 *
 * The fix accepts a per-property hint in any shape. These tests hold both halves:
 * the hint works, and writing nothing still gives the safe default.
 */
class GuruToolSchemaMapperOptionalTest {

    private fun parse(raw: String): List<GuruToolSchemaMapper.ParsedParam> =
        GuruToolSchemaMapper.parseParameters(Json.parseToJsonElement(raw))

    private fun byName(params: List<GuruToolSchemaMapper.ParsedParam>) =
        params.associateBy { it.name }

    @Test
    fun flatFormDefaultsEveryPropertyToRequired() {
        // The safe default, unchanged. An author who writes nothing still gets
        // required arguments, which is what stops a tool silently losing one.
        val params = byName(parse("""{"app":{"type":"string"},"volume":{"type":"integer"}}"""))
        assertTrue("app should default to required", params.getValue("app").required)
        assertTrue("volume should default to required", params.getValue("volume").required)
    }

    @Test
    fun flatFormCanMarkAPropertyOptionalWithRequiredFalse() {
        // The thing that was impossible. This is the whole point of the issue.
        val params = byName(parse("""{"app":{"type":"string","required":false},"volume":{"type":"integer"}}"""))
        assertFalse("app was marked optional", params.getValue("app").required)
        assertTrue("volume said nothing, so it stays required", params.getValue("volume").required)
    }

    @Test
    fun flatFormAcceptsTheWordOptionalToo() {
        // People reach for "optional": true before they reach for JSON Schema's
        // spelling. Refusing it would be pedantry that produces a mandatory
        // argument for a reason the author cannot see.
        val params = byName(parse("""{"app":{"type":"string","optional":true}}"""))
        assertFalse(params.getValue("app").required)
    }

    @Test
    fun aStringFalseIsReadAsFalseNotAsTruthy() {
        // A hand-written schema produces "false" as a string. Reading that as the
        // value true is exactly the silent inversion this change exists to stop.
        val params = byName(parse("""{"app":{"type":"string","required":"false"}}"""))
        assertFalse(params.getValue("app").required)

        val alsoFalse = byName(parse("""{"app":{"type":"string","optional":"true"}}"""))
        assertFalse(alsoFalse.getValue("app").required)
    }

    @Test
    fun aRequiredArrayStillWinsOverPerPropertyHints() {
        // JSON Schema form is authoritative: when the array exists it decides, and
        // a contradictory per-property hint must not override it.
        val params = byName(
            parse(
                """{"type":"object","properties":{"a":{"type":"string","required":false},"b":{"type":"string"}},"required":["a"]}""",
            ),
        )
        assertTrue("the required array names a, so a is required", params.getValue("a").required)
        assertFalse("b is absent from the array, so b is optional", params.getValue("b").required)
    }

    @Test
    fun mapFormIsStillParsedAndRequired() {
        // The third accepted shape: a bare type name per argument.
        val params = byName(parse("""{"location":"string","days":"integer"}"""))
        assertEquals(2, params.size)
        assertEquals(ToolParameterType.String, params.getValue("location").type)
        assertEquals(ToolParameterType.Integer, params.getValue("days").type)
        assertTrue(params.getValue("location").required)
    }

    @Test
    fun anEmptySchemaYieldsNoParameters() {
        // A tool that genuinely takes nothing must not invent an argument.
        assertTrue(parse("{}").isEmpty())
    }

    @Test
    fun aMalformedSchemaDegradesRatherThanThrowing() {
        // One bad tool can never block every other GURU tool from being offered,
        // so the mapper returns nothing instead of raising.
        val params = GuruToolSchemaMapper.parseParameters(Json.parseToJsonElement("\"not an object\""))
        assertTrue(params.isEmpty())
    }

    @Test
    fun aPropertyTypeSurvivesTheOptionalHint() {
        // The hint must not disturb type parsing: a boolean stays a boolean.
        val params = byName(parse("""{"force":{"type":"boolean","required":false}}"""))
        assertEquals(ToolParameterType.Boolean, params.getValue("force").type)
        assertFalse(params.getValue("force").required)
    }

    @Test
    fun theWholeSchemaParsesIntoADescriptorWithRealOptionalParameters() {
        // End of the chain: what the model is actually offered. This is the shape
        // a koog cloud executor serialises, so an optional argument has to appear
        // in optionalParameters rather than requiredParameters.
        val desc = GuruToolSchemaMapper.definitionToDescriptor(
            com.unuslumen.app.data.tools.registry.ToolDefinition(
                name = "test_tool",
                description = "test",
                category = "guru_tool",
                parameters = GuruToolSchemaMapper.parseParameters(
                    Json.parseToJsonElement(
                        """{"app":{"type":"string"},"volume":{"type":"integer","required":false}}""",
                    ),
                ).map {
                    com.unuslumen.app.data.tools.registry.ToolParameter(
                        it.name, it.type, it.required, it.description, it.enumValues,
                    )
                },
            ),
        )
        assertEquals(listOf("app"), desc.requiredParameters.map { it.name })
        assertEquals(listOf("volume"), desc.optionalParameters.map { it.name })
    }

    /** A round trip through JSON keeps the hint, which is what the database stores. */
    @Test
    fun theHintSurvivesSerialisationRoundTrip() {
        val raw = """{"app":{"type":"string","required":false}}"""
        val element: JsonElement = Json.parseToJsonElement(raw)
        val encoded = Json.encodeToString(JsonElement.serializer(), element)
        val params = byName(GuruToolSchemaMapper.parseParameters(Json.parseToJsonElement(encoded)))
        assertFalse(params.getValue("app").required)
    }
}
