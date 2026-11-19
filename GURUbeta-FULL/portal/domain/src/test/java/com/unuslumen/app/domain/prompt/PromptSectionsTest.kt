// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the assembled prompt back apart (issue 492).
 *
 * THE GAP. The registry had one name containing "prompt", which returned the whole
 * assembled string. A model could see that an instruction was wrong and could not find
 * the section that said it, so a proposal had to quote by hand and might quote the
 * wrong paragraph.
 *
 * THE SPLIT IS ON BLANK LINES, because that is the separator the assembler joins rows
 * with. What it must never do is split inside a code fence, because a prompt row
 * routinely carries an example and half an instruction reads as a complete one.
 */
class PromptSectionsTest {

    @Test
    fun the_assembled_prompt_splits_on_blank_lines() {
        val prompt = "first section here\n\nsecond section here\n\nthird section here"
        val sections = PromptSections.split(prompt)
        assertEquals(3, sections.size)
        assertEquals("first section here", PromptSections.read(prompt, sections[0].id))
        assertEquals("second section here", PromptSections.read(prompt, sections[1].id))
        assertEquals("third section here", PromptSections.read(prompt, sections[2].id))
    }

    @Test
    fun a_blank_line_inside_a_code_fence_does_not_split_a_section() {
        // THE RULE THAT MATTERS. A prompt row carries examples; splitting one down the
        // middle hands a caller half an instruction that reads as complete.
        val prompt = """
            Section one introduces the rule.

            ```kotlin
            fun example() {
                // a blank line follows inside the fence

                return
            }
            ```

            Section two follows the fence.
        """.trimIndent()

        val sections = PromptSections.split(prompt)
        assertEquals("two sections, not four: $sections", 2, sections.size)
        val first = PromptSections.read(prompt, sections[0].id)!!
        assertTrue("the fenced example stays whole: $first", first.contains("return"))
        assertTrue(first.contains("```kotlin"))
    }

    @Test
    fun each_section_can_be_read_back_exactly_by_its_id() {
        val prompt = "alpha\n\nbeta\n\ngamma"
        PromptSections.split(prompt).forEachIndexed { index, section ->
            val text = PromptSections.read(prompt, "section-$index")
            assertNotNull("section-$index must read back", text)
            assertEquals(section.length, text!!.length)
        }
    }

    @Test
    fun an_unknown_section_id_is_null_rather_than_an_empty_string() {
        // A caller has to be able to tell "no such section" from "a section that says
        // nothing", because one is a mistake and the other is a fact about the prompt.
        assertNull(PromptSections.read("a\n\nb", "section-99"))
        assertNull(PromptSections.read("a\n\nb", ""))
    }

    @Test
    fun the_heading_is_the_first_line_with_its_marks_removed() {
        assertEquals("Capabilities", PromptSections.headingOf("## Capabilities\n\nYou can do things."))
        assertEquals("Plain first line", PromptSections.headingOf("Plain first line\nand more"))
    }

    @Test
    fun a_very_long_first_line_is_trimmed_to_something_a_listing_can_show() {
        val long = "word ".repeat(200)
        val heading = PromptSections.headingOf(long)
        assertTrue(
            "a heading must fit a listing: ${heading.length}",
            heading.length <= PromptSections.MAX_HEADING_CHARS,
        )
    }

    @Test
    fun an_empty_prompt_has_no_sections_and_no_crash() {
        assertEquals(0, PromptSections.split("").size)
        assertEquals(0, PromptSections.split(null).size)
        assertNull(PromptSections.read(null, "section-0"))
    }

    @Test
    fun leading_blank_lines_do_not_create_an_empty_first_section() {
        val prompt = "\n\n\nfirst real section\n\nsecond"
        val sections = PromptSections.split(prompt)
        assertEquals(2, sections.size)
        assertEquals("first real section", PromptSections.read(prompt, sections[0].id))
    }
}
