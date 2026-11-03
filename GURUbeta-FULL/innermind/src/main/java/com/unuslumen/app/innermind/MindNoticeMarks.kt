// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.innermind

import android.content.Context

/**
 * Whether the room's "in development" notice has been dismissed.
 *
 * WHY THE ROOM SAYS THIS OUT LOUD. The Inner Mind is unfinished: the map draws,
 * but editing, deleting and the fold behaviour are not all there yet, and a
 * person who is not told that will read a missing control as a broken one. The
 * notice says it plainly the first time the room is opened, and then stops
 * talking, because a warning that returns on every visit is not a warning, it is
 * noise that gets tapped without being read.
 *
 * WHY THIS IS AN INTERFACE AND NOT A CALL INTO THE SCREEN. The view model must
 * not hold a Context, and a JVM test must be able to answer the question without
 * writing a preferences file. The real implementation is one small class below.
 */
interface MindNoticeMarks {

    /** Whether the notice has already been acknowledged on this install. */
    fun wasDismissed(): Boolean

    /** Remember that it has, so it is not shown again. */
    fun dismiss()
}

/**
 * The marks, held in the app's own preferences.
 *
 * Deliberately a separate file from the rest of the app's settings: this notice
 * is temporary, and deleting one file plus one binding has to be enough to
 * retire it when the room is finished.
 */
class PrefsMindNoticeMarks(context: Context) : MindNoticeMarks {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun wasDismissed(): Boolean = prefs.getBoolean(KEY_DISMISSED, false)

    override fun dismiss() {
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    private companion object {
        const val PREFS_NAME = "guru_innermind_state"
        const val KEY_DISMISSED = "development_notice_dismissed"
    }
}
