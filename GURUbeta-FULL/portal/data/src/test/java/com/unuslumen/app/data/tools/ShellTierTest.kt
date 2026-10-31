// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.util.shell.ShellResult
import com.unuslumen.app.util.shell.ShellTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the execution-tier contract, because issue 40 was a description that
 * promised more than the device could do.
 *
 * THE DEFECT. `executeShellCommand` said "Uses ADB ... NO limits" while the
 * executor silently ran at app UID, where `/proc/net/arp`, `dumpsys` and
 * `settings get` all return Permission denied. A model reading that description
 * attempted those, read the denial as a transient failure, and retried against a
 * permanent wall. Nothing on the result said which tier had served the call.
 *
 * The fix has two halves and both are asserted here: the result carries the tier,
 * and no tier can describe itself in words that overstate what it can do.
 */
class ShellTierTest {

    @Test
    fun aResultDefaultsToTheWeakestTierNeverTheStrongest() {
        // Defaulting to ROOT would be the same class of lie this field removes.
        // A result that does not say which tier ran must be assumed to be the
        // least capable one.
        val r = ShellResult(exitCode = 0, stdout = "", stderr = "", success = true)
        assertEquals(
            "An unstamped result must default to APP_UID, never to ROOT, because " +
                "assuming the strongest tier is what produced issue 40.",
            ShellTier.APP_UID,
            r.tier,
        )
    }

    @Test
    fun theTierSurvivesTheResultItIsAttachedTo() {
        val r = ShellResult(0, "out", "", true, ShellTier.ADB)
        assertEquals(ShellTier.ADB, r.tier)
        // copy() must preserve it, because the executor stamps results by copy.
        assertEquals(ShellTier.ADB, r.copy(stdout = "changed").tier)
    }

    @Test
    fun noTierClaimsUnlimitedAccess() {
        for (tier in ShellTier.values()) {
            val text = tier.capabilities().lowercase()
            assertFalse(
                "$tier describes itself with an unqualified promise. That is the exact " +
                    "wording issue 40 was filed about.",
                text.contains("no limits") || text.contains("unrestricted shell access") && tier != ShellTier.ROOT,
            )
        }
    }

    @Test
    fun theWeakestTierSaysWhatItCannotDo() {
        val appUid = ShellTier.APP_UID.capabilities().lowercase()
        assertTrue(
            "APP_UID must name the things that will be denied, or a caller will keep " +
                "attempting them.",
            appUid.contains("/proc") || appUid.contains("dumpsys"),
        )
        assertTrue(
            "APP_UID must say a denial is permanent, so it is not retried.",
            appUid.contains("permanent") || appUid.contains("must not be retried"),
        )
    }

    @Test
    fun everyTierIsNamedAndDistinct() {
        val names = ShellTier.values().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(listOf("ROOT", "ADB", "APP_UID", "NONE")))
    }
}
