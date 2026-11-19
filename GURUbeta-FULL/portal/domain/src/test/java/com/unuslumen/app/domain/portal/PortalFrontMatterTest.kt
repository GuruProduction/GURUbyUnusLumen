// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The portal frontmatter contract (issue 479).
 *
 * THE DOCUMENTED EXAMPLE IS THE TEST. The system prompt gives every agent exactly
 * one example of how to write a portal, and it uses YAML block scalars. A parser
 * that does not understand them reads the `|` as the value, so the CSS injected
 * into the style tag is a single pipe, the JS injected into the script tag is a
 * single pipe, and the whole render arrives looking like flat HTML because that
 * is what it is.
 *
 * The failure is invisible from the message text: the block is well formed, the
 * agent followed the instructions, and only the rendered document shows the
 * damage. So every case below asserts on the PARSED VALUE, not on the absence of
 * an error.
 */
class PortalFrontMatterTest {

    // ------------------------------------------------------------------
    // The documented example, verbatim.
    // ------------------------------------------------------------------

    @Test
    fun the_example_from_the_system_prompt_parses_with_real_css_and_js() {
        val body = """
            ---
            css: |
              .my-card { background: var(--surface-variant); border-radius: 12px; padding: 16px; }
            js: |
              console.log("interactive content");
            height: 400
            ---
            <div class="my-card">Hello world</div>
        """.trimIndent()

        val parsed = PortalFrontMatter.parse(body)

        assertEquals(
            ".my-card { background: var(--surface-variant); border-radius: 12px; padding: 16px; }",
            parsed.css,
        )
        assertEquals("console.log(\"interactive content\");", parsed.js)
        assertEquals(400, parsed.height)
        assertEquals("<div class=\"my-card\">Hello world</div>", parsed.html)
    }

    // ------------------------------------------------------------------
    // Multi-line block content, which is the reason the format exists.
    // ------------------------------------------------------------------

    @Test
    fun a_multi_line_css_block_keeps_its_newlines_and_loses_its_indentation() {
        val body = """
            ---
            css: |
              .a { color: red; }
              .b { color: blue; }
            ---
            <p>hi</p>
        """.trimIndent()

        val parsed = PortalFrontMatter.parse(body)
        assertEquals(".a { color: red; }\n.b { color: blue; }", parsed.css)
    }

    @Test
    fun a_blank_line_inside_a_block_is_content_not_the_end_of_the_block() {
        val body = "---\ncss: |\n  .a { color: red; }\n\n  .b { color: blue; }\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertTrue(
            "a blank line inside the block belongs to the content: got ${parsed.css}",
            parsed.css.contains(".a { color: red; }") && parsed.css.contains(".b { color: blue; }"),
        )
        assertTrue(
            "the blank line survives as a blank line: got ${parsed.css}",
            parsed.css.contains("\n\n"),
        )
    }

    @Test
    fun the_baseline_is_the_first_content_line_so_a_deeper_indent_is_kept_in_full() {
        // Four spaces, not two. The block's own indentation is stripped and
        // anything beyond it is content, which is what lets CSS keep its shape.
        val body = "---\ncss: |\n    .a {\n      color: red;\n    }\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertEquals(".a {\n  color: red;\n}", parsed.css)
    }

    @Test
    fun a_key_after_a_block_is_not_swallowed_by_it() {
        val body = "---\ncss: |\n  .a { color: red; }\nheight: 250\ninteractive: false\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertEquals(".a { color: red; }", parsed.css)
        assertEquals(250, parsed.height)
        assertEquals(false, parsed.interactive)
    }

    // ------------------------------------------------------------------
    // The other scalar forms, so a caller that writes them is not punished.
    // ------------------------------------------------------------------

    @Test
    fun a_folded_scalar_joins_wrapped_lines_with_spaces() {
        val body = "---\ncss: >\n  .a { color: red; }\n  .b { color: blue; }\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertEquals(".a { color: red; } .b { color: blue; }", parsed.css)
    }

    @Test
    fun the_chomping_indicators_are_accepted() {
        listOf("|-", "|+", ">-", ">+").forEach { indicator ->
            val body = "---\ncss: $indicator\n  .a { color: red; }\n---\n<p>hi</p>"
            val parsed = PortalFrontMatter.parse(body)
            assertEquals(
                "$indicator must parse as a block scalar, not as the literal text",
                ".a { color: red; }",
                parsed.css,
            )
        }
    }

    // ------------------------------------------------------------------
    // Single-line values, which already worked and must keep working.
    // ------------------------------------------------------------------

    @Test
    fun a_single_line_value_still_parses() {
        val body = "---\ncss: .a { color: red; }\njs: console.log(1)\nheight: 300\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertEquals(".a { color: red; }", parsed.css)
        assertEquals("console.log(1)", parsed.js)
        assertEquals(300, parsed.height)
    }

    @Test
    fun a_quoted_single_line_value_loses_its_quotes() {
        val body = "---\ncss: \".a { color: red; }\"\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertEquals(".a { color: red; }", parsed.css)
    }

    @Test
    fun a_block_scalar_that_begins_and_ends_with_a_quote_is_not_stripped() {
        // Legal JS: a program can legitimately start and end with a quote
        // character. Stripping it would change what the program does, and the
        // quote rule exists for single-line values only.
        val body = "---\njs: |\n  \"use strict\";\n  console.log('x');\n  \"\"\n---\n<p>hi</p>"

        val parsed = PortalFrontMatter.parse(body)
        assertTrue(
            "the block's own quotes must survive: got ${parsed.js}",
            parsed.js.startsWith("\"use strict\";"),
        )
        assertTrue(parsed.js.endsWith("\"\""))
    }

    // ------------------------------------------------------------------
    // Bodies with no frontmatter, and the defaults.
    // ------------------------------------------------------------------

    @Test
    fun a_body_with_no_frontmatter_is_all_html() {
        val parsed = PortalFrontMatter.parse("<div>plain portal</div>")
        assertEquals("<div>plain portal</div>", parsed.html)
        assertEquals("", parsed.css)
        assertEquals("", parsed.js)
        assertNull(parsed.height)
        assertTrue("interactive defaults to true", parsed.interactive)
    }

    @Test
    fun an_absent_key_stays_at_its_default() {
        val parsed = PortalFrontMatter.parse("---\ncss: |\n  .a { color: red; }\n---\n<p>hi</p>")
        assertNull(parsed.height)
        assertTrue(parsed.interactive)
        assertEquals("", parsed.js)
    }

    @Test
    fun a_key_whose_name_merely_starts_with_css_does_not_set_css() {
        // `css-something:` used to match a bare `startsWith("css:")` check on a
        // trimmed line only by accident; an exact key comparison cannot match it
        // at all, which is the stricter anchor the issue asked for.
        val parsed = PortalFrontMatter.parse("---\ncss-something: nope\n---\n<p>hi</p>")
        assertEquals("", parsed.css)
    }
}
