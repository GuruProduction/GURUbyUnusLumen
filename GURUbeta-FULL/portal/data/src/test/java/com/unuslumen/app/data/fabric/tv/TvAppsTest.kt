// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken app names and what each platform calls them.
 *
 * WHY THIS IS WORTH PINNING. "Netflix" is one word to a person and a different identifier on every
 * platform: a channel number on Roku, an app id on Samsung, a reverse-domain id on LG, a package on
 * Android TV. A wrong id launches nothing, and nothing about it fails loudly on this side, so the
 * mappings are asserted rather than trusted.
 */
class TvAppsTest {

    /** The same spoken names must work on every platform, or the model has to know the make. */
    @Test
    fun everyPlatformAnswersToTheSameSpokenNames() {
        for (name in listOf("netflix", "youtube", "prime video", "bbc iplayer", "spotify")) {
            assertNotNull("Roku must know $name", TvApps.rokuId(name))
            assertNotNull("Samsung must know $name", TvApps.samsungId(name))
            assertNotNull("LG must know $name", TvApps.lgId(name))
            assertNotNull("Android TV must know $name", TvApps.androidTvPackage(name))
        }
    }

    /**
     * Netflix is a channel number on Roku, an app id on Samsung, and a plain name on LG.
     *
     * These three are the reference values that prove the table is not one list copied four times.
     */
    @Test
    fun theSameAppIsADifferentIdentifierPerPlatform() {
        assertEquals("Roku calls Netflix channel 12", "12", TvApps.rokuId("netflix"))
        assertEquals("Samsung calls it an app id", "11101200001", TvApps.samsungId("netflix"))
        assertEquals("LG calls it a plain app name", "netflix", TvApps.lgId("netflix"))
        assertEquals(
            "Android TV calls it a package",
            "com.netflix.ninja",
            TvApps.androidTvPackage("netflix"),
        )
    }

    /** A spoken name must fold the way people write it, so capitalisation cannot break a lookup. */
    @Test
    fun spokenNamesFoldBeforeLookup() {
        assertEquals("netflix", TvApps.normalise("Netflix"))
        assertEquals("prime video", TvApps.normalise("Prime  Video"))
        assertEquals("disney plus", TvApps.normalise("Disney+"))
        assertEquals("bbc iplayer", TvApps.normalise("BBC iPlayer"))
        assertEquals("prime video", TvApps.normalise("  prime-video  "))
    }

    /** A plus sign is how people write Disney Plus, and it must land the same app. */
    @Test
    fun aPlusSignIsReadAsPlus() {
        assertEquals(TvApps.lgId("disney plus"), TvApps.lgId("Disney+"))
        assertEquals(TvApps.rokuId("disney plus"), TvApps.rokuId("Disney +"))
    }

    /** An unknown app must return null rather than a wrong identifier. */
    @Test
    fun anUnknownAppIsNotGuessed() {
        assertNull("a made-up app must not resolve", TvApps.rokuId("my own channel"))
        assertNull(TvApps.samsungId("netflicks"))
        assertNull(TvApps.lgId(""))
        assertNull(TvApps.androidTvPackage("not a real app"))
    }

    /** A refusal must name the alternatives, because that is what makes it usable. */
    @Test
    fun aRefusalListsTheNamesTheSetAccepts() {
        val sentence = TvApps.refusal("The Roku at 192.168.0.115", TvVendor.ROKU, "netflicks")
        assertTrue("it must name the set", sentence.contains("192.168.0.115"))
        assertTrue("it must name what was asked for", sentence.contains("netflicks"))
        assertTrue("it must list netflix as an alternative", sentence.contains("netflix"))
        assertTrue("and youtube too", sentence.contains("youtube"))
    }

    /** The spoken list must cover the apps a person in the UK would actually name. */
    @Test
    fun theSpokenListCoversTheHouse() {
        for (required in listOf("netflix", "youtube", "prime video", "bbc iplayer", "disney plus")) {
            assertTrue("'$required' must be in the spoken list", required in TvApps.spoken)
        }
        assertFalse("the list must not be empty", TvApps.spoken.isEmpty())
    }
}
