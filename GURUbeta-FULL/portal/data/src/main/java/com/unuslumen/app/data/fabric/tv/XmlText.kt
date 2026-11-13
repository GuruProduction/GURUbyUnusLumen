// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

/**
 * Decoding the text a device put in its own description document.
 *
 * WHY THIS EXISTS, AND WHY IT IS NOT A ONE-LINE REPLACE. Issues 216 and 223 are the same
 * defect: `tvDiscover` reported a name containing `&apos;` and `tvPress` could not match it.
 * The set's real name is `Alan&apos;s TV` in the XML, which means `Alan's TV`, and the whole
 * documented flow is discover-then-address-by-name, so the flow could not complete. The
 * tool's own error advice was to run discovery again, which returned the same undecodable
 * name: a closed loop that reads as a dead television rather than as a string bug.
 *
 * WHERE IT GOES. Decoding happens once, at the point the text is extracted from the
 * document, so every consumer downstream — the tool result, the resolver, the store — sees
 * the same string. Decoding at the resolver instead would fix the match and leave the name
 * wrong in the output a person reads, which is the half of the bug that made no sense.
 *
 * WHY NOT A DOM PARSE. [SsdpDescriptions] uses one where the whole document is available,
 * and that is the better tool when it is. This is used by call sites that have already
 * extracted a value by regex from a fragment, including banner strings that are not XML
 * documents at all, so it decodes the five predefined entities and numeric references that
 * a UPnP description can legally contain, and leaves everything else alone.
 */
object XmlText {

    /**
     * The five predefined XML entities, plus the apostrophe spelled numerically.
     *
     * `&apos;` is the one that broke the round trip, and it is decoded first because it is
     * the only one whose absence a person notices as a name that cannot be typed.
     */
    private val NAMED = listOf(
        "&apos;" to "'",
        "&quot;" to "\"",
        "&lt;" to "<",
        "&gt;" to ">",
        // LAST, ALWAYS. Replacing `&amp;` first would turn `&amp;apos;` into `&apos;` and then
        // into `'`, which is a double decode: the device wrote an ampersand followed by the
        // letters a-p-o-s, and the reader should see exactly that.
        "&amp;" to "&",
    )

    /** `&#39;` and `&#x27;` are the same apostrophe written numerically. */
    private val NUMERIC = Regex("""&#(x?)([0-9a-fA-F]+);""")

    /**
     * Decode a value taken from a description or a banner.
     *
     * Named entities first in the order above, then numeric references, and the ampersand
     * last so one pass cannot re-decode its own output. A blank input stays blank rather than
     * becoming a placeholder, because "the device named nothing" is a fact the caller needs.
     */
    fun decode(raw: String?): String? {
        if (raw == null) return null
        if (raw.isEmpty()) return raw
        if (!raw.contains('&')) return raw

        var out: String = raw
        // Numeric references are decoded BEFORE the named ones so a value written as
        // `&#38;apos;` — an ampersand followed by the letters — survives as `&apos;` text
        // rather than being turned into an apostrophe that the device never sent.
        out = NUMERIC.replace(out) { match ->
            val hex = match.groupValues[1].isNotEmpty()
            val code = match.groupValues[2].toIntOrNull(if (hex) 16 else 10)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
        }

        for ((entity, replacement) in NAMED) {
            if (out.contains(entity)) out = out.replace(entity, replacement)
        }

        return out
    }

    /** [decode] and then trim, which is what every extraction site actually wants. */
    fun decodeAndTrim(raw: String?): String? = decode(raw)?.trim()
}
