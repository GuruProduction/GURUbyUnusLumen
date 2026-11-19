// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a model-written skill has to be before it is stored (issue 492).
 *
 * THE GAP. The registry exposed six skill names and none created or edited a skill:
 * four were the library, one was discovery, one was execution. The Self Improvement
 * Engine's remit names skills as part of development and tells the agent to build
 * them, and it could use a skill and install someone else's but never write one.
 *
 * WHY THE VALIDATION MATTERS MORE THAN THE PLUMBING. A skill is a prompt injection the
 * agent chose to accept: its body expands into the model's context when it runs. A
 * skill with no name cannot be found, one with no body does nothing, and one whose
 * allowed-tools name tools that do not exist grants nothing while reading as though it
 * granted something.
 */
class SkillAuthoringTest {

    private val knownTools = setOf("readFile", "writeFile", "executeShellCommand")

    private fun valid(
        name: String? = "Probe the router",
        description: String? = "Finds every device on the LAN and names it.",
        body: String? = "Step one: read the ARP table. Step two: probe each host on 80 and 443. " +
            "Step three: report the ones that answer, with their banners.",
        tools: List<String> = emptyList(),
    ) = SkillAuthoring.validate(name, description, body, tools, knownTools)

    @Test
    fun a_complete_skill_is_valid() {
        assertEquals(SkillAuthoring.Verdict.Valid, valid())
    }

    @Test
    fun a_skill_with_no_name_is_refused() {
        val verdict = valid(name = "") as SkillAuthoring.Verdict.Invalid
        assertTrue(verdict.reason.contains("name", ignoreCase = true))
        assertTrue(valid(name = null) is SkillAuthoring.Verdict.Invalid)
    }

    @Test
    fun a_name_that_cannot_be_found_is_refused() {
        // A name has to survive being listed and read back. Slashes and control
        // characters do not.
        listOf("bad/name", "-leading-dash", "line\nbreak", "").forEach { bad ->
            assertTrue(
                "\"$bad\" must be refused as a name",
                valid(name = bad) is SkillAuthoring.Verdict.Invalid,
            )
        }
    }

    @Test
    fun a_skill_with_no_description_is_refused() {
        // The description is what a listing shows; without it a caller cannot decide
        // whether to read the body.
        assertTrue(valid(description = "") is SkillAuthoring.Verdict.Invalid)
        assertTrue(valid(description = "   ") is SkillAuthoring.Verdict.Invalid)
    }

    @Test
    fun a_skill_with_no_body_is_refused() {
        assertTrue(valid(body = "") is SkillAuthoring.Verdict.Invalid)
        assertTrue(valid(body = "   ") is SkillAuthoring.Verdict.Invalid)
    }

    @Test
    fun a_body_too_short_to_be_a_methodology_is_refused() {
        val verdict = valid(body = "Do the thing.") as SkillAuthoring.Verdict.Invalid
        assertTrue(
            "the reason must say the body is too short: ${verdict.reason}",
            verdict.reason.contains("too short"),
        )
    }

    @Test
    fun an_unclosed_frontmatter_block_is_refused() {
        // The loader reads frontmatter between two --- lines. An unclosed one leaves
        // every field after it unparsed, which reads as a skill that loaded with no
        // allowed tools.
        val verdict = valid(body = "---\nname: x\ndescription: y\n\nStep one and step two, and more words to pass the length rule here.") as SkillAuthoring.Verdict.Invalid
        assertTrue(verdict.reason.contains("---"))
    }

    @Test
    fun a_closed_frontmatter_block_is_accepted() {
        val body = "---\nname: x\n---\n\nStep one: read the file. Step two: change it. " +
            "Step three: write it back and prove the change landed."
        assertEquals(SkillAuthoring.Verdict.Valid, valid(body = body))
    }

    @Test
    fun an_allowed_tool_that_does_not_exist_is_refused_by_name() {
        // THIS IS THE ONE THAT MATTERS. A silently dropped grant makes a skill believe
        // it can do something it cannot, and the failure appears at the moment the
        // methodology is needed.
        val verdict = valid(tools = listOf("readFile", "inventTheUniverse")) as SkillAuthoring.Verdict.Invalid
        assertTrue("must name the bad tool: ${verdict.reason}", verdict.reason.contains("inventTheUniverse"))
        assertTrue(verdict.reason.contains("not a registered tool"))
    }

    @Test
    fun real_tools_are_accepted() {
        assertEquals(SkillAuthoring.Verdict.Valid, valid(tools = listOf("readFile", "writeFile")))
    }

    @Test
    fun the_slug_is_derived_and_stable() {
        // A slug is a machine concept, so it is derived rather than asked for — the
        // house rule is that a machine concept is never shown to a person.
        assertEquals("probe-the-router", SkillAuthoring.slugFor("Probe the router"))
        assertEquals("probe-the-router", SkillAuthoring.slugFor("  Probe   the ROUTER  "))
        assertEquals("skill", SkillAuthoring.slugFor("   "))
    }

    @Test
    fun a_missing_when_to_use_line_is_derived_from_the_description() {
        // A skill with no trigger is one the agent must remember to use, which is the
        // same as one it will not use.
        val derived = SkillAuthoring.defaultWhenToUse("Probe", "finds every device on the LAN.")
        assertTrue(derived.startsWith("Use when"))
        assertTrue(derived.contains("finds every device on the LAN"))
        assertTrue("no doubled full stop: $derived", !derived.contains(".."))
    }
}
