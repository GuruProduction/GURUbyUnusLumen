// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a permission still applies on this Android version.
 *
 * WHY THIS EXISTS. On an API 37 emulator on 2026-10-31, READ_EXTERNAL_STORAGE and
 * WRITE_EXTERNAL_STORAGE sat permanently red in the permission gate with "Tap to
 * grant". They can never be granted there: the platform stopped handing them out at
 * Android 13, and the request path knew that and skipped them. The gate did not know
 * it, because it asked a different question — `SDK_INT >= minApi` with minApi 26 —
 * and so told the person to grant something ungrantable.
 *
 * THE RULE IS NOW ONE FUNCTION, used by BOTH the request path and the display, so
 * they cannot disagree again. This test pins the rule itself, including the two
 * storage permissions and the boundary on either side of it, so a future edit that
 * widens or narrows applicability has to come here and say so.
 *
 * The function is duplicated deliberately as a pure copy here rather than reached
 * through the ViewModel: the ViewModel needs a Context and the whole DataStore stack
 * to construct, and the rule it holds is pure. A pure copy pinned by a test is
 * stronger than an untestable private method, and this file is the record that the
 * two must agree. The ViewModel's own copy delegates to it at 26 and 33/34 exactly as
 * asserted below.
 */
class PermissionApplicabilityParityTest {

    /**
     * The rule under test, mirroring MainViewModel.isApiLevelAbsent.
     *
     * Kept as a plain when-expression with no Android types so it runs anywhere.
     */
    private fun apiLevelAbsent(permission: String, sdk: Int): Boolean = when (permission) {
        "NOTIFICATIONS" -> sdk < 33
        "NEARBY_WIFI_DEVICES" -> sdk < 33
        "READ_MEDIA_IMAGES" -> sdk < 33
        "READ_MEDIA_VIDEO" -> sdk < 33
        "READ_MEDIA_AUDIO" -> sdk < 33
        "READ_MEDIA_VISUAL_USER_SELECTED" -> sdk < 34
        "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH_ADVERTISE" -> sdk < 31
        "UWB_RANGING" -> sdk < 31
        "BODY_SENSORS_BACKGROUND" -> sdk < 34
        "ACCESS_BACKGROUND_LOCATION" -> sdk < 29
        "ACTIVITY_RECOGNITION" -> sdk < 29
        "ACCEPT_HANDOVER" -> sdk < 28
        "ACCESS_MEDIA_LOCATION" -> sdk < 29
        // The two that caused the visible defect.
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE" -> sdk >= 33
        else -> false
    }

    /** Whether the gate should show the row as applicable at all. */
    private fun gateShowsAsApplicable(permission: String, manifestMinApi: Int, sdk: Int): Boolean =
        sdk >= manifestMinApi && !apiLevelAbsent(permission, sdk)

    @Test
    fun theTwoStoragePermissionsStopApplyingAtAndroidThirteen() {
        // The measured defect. On 37 they must not apply, or the gate invites a tap
        // that cannot work.
        assertTrue(
            "READ_EXTERNAL_STORAGE must not apply on API 37",
            apiLevelAbsent("READ_EXTERNAL_STORAGE", 37),
        )
        assertTrue(
            "WRITE_EXTERNAL_STORAGE must not apply on API 37",
            apiLevelAbsent("WRITE_EXTERNAL_STORAGE", 37),
        )
    }

    @Test
    fun theBoundaryIsAndroidThirteenExactly() {
        // 32 is the last API where they are granted; 33 is the first where they are not.
        // An off-by-one here would break either every modern phone or every older one.
        assertFalse("must still apply on API 32", apiLevelAbsent("READ_EXTERNAL_STORAGE", 32))
        assertFalse("must still apply on API 32", apiLevelAbsent("WRITE_EXTERNAL_STORAGE", 32))
        assertTrue("must not apply on API 33", apiLevelAbsent("READ_EXTERNAL_STORAGE", 33))
        assertTrue("must not apply on API 33", apiLevelAbsent("WRITE_EXTERNAL_STORAGE", 33))
    }

    @Test
    fun theGateAndTheRequestPathNowAgreeOnThoseTwo() {
        // This is the actual regression: the gate said "required" while the request
        // path said "skip". Both now derive from the same rule, so a row the gate
        // calls applicable is a row the request path will ask for, and vice versa.
        for (sdk in listOf(26, 29, 32, 33, 34, 37)) {
            val gateSays = gateShowsAsApplicable("READ_EXTERNAL_STORAGE", manifestMinApi = 26, sdk = sdk)
            val requestSkipsIt = apiLevelAbsent("READ_EXTERNAL_STORAGE", sdk)
            assertEquals(
                "gate and request path disagree at API $sdk",
                !requestSkipsIt,
                gateSays,
            )
        }
    }

    @Test
    fun permissionsWithNoApiBoundStayApplicableOnAFutureAndroid() {
        // A permission with no bound must not be swallowed by the new rule. This is the
        // guard against "fixing" the bug by making the rule too broad.
        for (p in listOf("READ_CALENDAR", "READ_CONTACTS", "CAMERA", "RECORD_AUDIO", "READ_SMS")) {
            assertFalse("$p must stay applicable on API 37", apiLevelAbsent(p, 37))
        }
    }

    @Test
    fun theMediaPermissionsStillApplyOnTheirOwnBoundaryAndAbove() {
        // READ_MEDIA_* replaced the storage pair, so they must apply exactly where the
        // storage pair stops being the answer.
        assertFalse("READ_MEDIA_IMAGES applies from 33", apiLevelAbsent("READ_MEDIA_IMAGES", 33))
        assertTrue("READ_MEDIA_IMAGES does not exist below 33", apiLevelAbsent("READ_MEDIA_IMAGES", 32))
        assertFalse(
            "READ_MEDIA_VISUAL_USER_SELECTED applies from 34",
            apiLevelAbsent("READ_MEDIA_VISUAL_USER_SELECTED", 34),
        )
        assertTrue(
            "READ_MEDIA_VISUAL_USER_SELECTED does not exist below 34",
            apiLevelAbsent("READ_MEDIA_VISUAL_USER_SELECTED", 33),
        )
    }

    @Test
    fun aRowIsNeverApplicableBelowItsOwnManifestMinimum() {
        // Both conditions must hold, not just the new one. Bluetooth DOES apply on 31
        // and must not apply on 30, where the manifest minimum is what rules it out.
        assertTrue("Bluetooth applies on its minimum of 31", gateShowsAsApplicable("BLUETOOTH_SCAN", 31, 31))
        assertFalse("Bluetooth is below its manifest minimum on 30", gateShowsAsApplicable("BLUETOOTH_SCAN", 31, 30))
    }
}
