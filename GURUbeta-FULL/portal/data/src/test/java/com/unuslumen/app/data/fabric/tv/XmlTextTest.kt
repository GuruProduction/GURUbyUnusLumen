// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Holds the entity decoding, because issues 216 and 223 are the same defect: a television's
 * real name contains an apostrophe, the set serves it as `&apos;`, and both the name a person
 * reads and the name `tvPress` can address came out wrong.
 *
 * THE CLOSED LOOP THE TICKET DESCRIBES. `tvDiscover` reported `Alan&apos;s TV`, so a press
 * with that exact string failed to match, and the literal `&apos;` failed too because the
 * matcher compared against something else. The tool's own advice was to run discovery again,
 * which returned the identical undecodable name. The documented flow could not complete, and
 * it read as a dead television rather than as a string bug.
 */
class XmlTextTest {

    @Test
    fun theApostropheThatBrokeTheRoundTripIsDecoded() {
        assertEquals(
            "This is the exact string from the ticket. Left encoded, it is a name nobody " +
                "can type back into tvPress.",
            "Alan's TV",
            XmlText.decode("Alan&apos;s TV"),
        )
    }

    @Test
    fun allFourOtherPredefinedEntitiesAreDecoded() {
        assertEquals("a & b", XmlText.decode("a &amp; b"))
        assertEquals("say \"hello\"", XmlText.decode("say &quot;hello&quot;"))
        assertEquals("<tag>", XmlText.decode("&lt;tag&gt;"))
    }

    @Test
    fun aNumericApostropheIsTheSameApostrophe() {
        // Devices write the apostrophe three ways, and a person cannot tell which one they got.
        assertEquals("Alan's TV", XmlText.decode("Alan&#39;s TV"))
        assertEquals("Alan's TV", XmlText.decode("Alan&#x27;s TV"))
    }

    @Test
    fun anAmpersandIsDecodedLastSoOnePassCannotDoubleDecode() {
        // `&amp;apos;` means the literal text `&apos;`, which is what the device wrote. An
        // implementation that replaced `&amp;` first would produce an apostrophe the device
        // never sent, and that is how a decoder turns into a mangler.
        assertEquals("&apos;", XmlText.decode("&amp;apos;"))
    }

    @Test
    fun aBareAmpersandWithNoEntityIsLeftAlone() {
        assertEquals("Tom & Jerry", XmlText.decode("Tom & Jerry"))
    }

    @Test
    fun aNameWithNoEntityIsReturnedUnchangedAndCheaply() {
        // The common case, and it must not be altered in any way.
        assertEquals("Living Room TV", XmlText.decode("Living Room TV"))
    }

    @Test
    fun nullStaysNullAndEmptyStaysEmpty() {
        assertNull(XmlText.decode(null))
        assertEquals("", XmlText.decode(""))
    }

    @Test
    fun anOutOfRangeNumericReferenceIsLeftAsWritten() {
        // A reference no character can satisfy must not throw and must not silently vanish.
        assertEquals("&#1114112;", XmlText.decode("&#1114112;"))
    }

    @Test
    fun aDecodedNameIsTheNameTheResolverCanMatch() {
        // The whole point: what discovery reports is what a press can be given.
        val reported = XmlText.decodeAndTrim("  Alan&apos;s TV  ")
        assertEquals("Alan's TV", reported)
        assertEquals(
            "A press given the reported name must find it, and the match is a plain " +
                "contains, so the decoded form has to be what is stored.",
            true,
            reported!!.contains("Alan's TV", ignoreCase = true),
        )
    }
}
