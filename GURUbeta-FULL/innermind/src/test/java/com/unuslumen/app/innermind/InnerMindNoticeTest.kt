// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The room's "in development" card behaves as a one-time statement.
 *
 * WHY THIS IS A JVM TEST. The whole rule is three sentences: show it until it is
 * acknowledged, remember the acknowledgement, never show it again. That is
 * decision logic with a store behind it, so it is proven here in milliseconds
 * rather than by opening the room on a phone and tapping a button.
 *
 * The store is faked, which is the point: the interface exists so the rule can be
 * checked without a preferences file, and so the test fails on the rule rather
 * than on whatever the device happens to have saved.
 */
@RunWith(RobolectricTestRunner::class)
class InnerMindNoticeTest {

    /** A store that only remembers what it was told. */
    private class FakeMarks(private var dismissed: Boolean = false) : MindNoticeMarks {
        var writes = 0
            private set

        override fun wasDismissed(): Boolean = dismissed

        override fun dismiss() {
            writes += 1
            dismissed = true
        }
    }

    private fun viewModel(marks: MindNoticeMarks) = InnerMindViewModel(
        source = mockk(relaxed = true),
        noticeMarks = marks,
    )

    @Test
    fun `a fresh install is told the room is in development`() {
        val model = viewModel(FakeMarks())

        assertTrue(
            "The card must be present on the first opening, because the room is unfinished " +
                "and a person not told that reads a missing control as a broken one.",
            model.state.value.developmentNotice,
        )
    }

    @Test
    fun `dismissing puts the card away and records it`() {
        val marks = FakeMarks()
        val model = viewModel(marks)

        model.onEvent(InnerMindEvent.DismissDevelopmentNotice)

        assertFalse(
            "Tapping Got it must take the card off the screen.",
            model.state.value.developmentNotice,
        )
        assertTrue(
            "The refusal must be written, otherwise the card returns on the next visit.",
            marks.wasDismissed(),
        )
    }

    @Test
    fun `an acknowledged install never sees the card again`() {
        val marks = FakeMarks(dismissed = true)

        val model = viewModel(marks)

        assertFalse(
            "A warning that returns every visit is noise that gets tapped without being " +
                "read, so an acknowledged install must open straight onto the map.",
            model.state.value.developmentNotice,
        )
        assertTrue(
            "Reading the mark must not itself write one.",
            marks.writes == 0,
        )
    }
}
