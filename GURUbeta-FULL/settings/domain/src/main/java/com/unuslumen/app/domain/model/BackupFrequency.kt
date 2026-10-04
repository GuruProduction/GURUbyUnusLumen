// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

enum class BackupFrequency(val value: Int, val hours: Int) {
    HOURLY(1, 1),
    DAILY(2, 24),
    WEEKLY(3, 24 * 7),
    MONTHLY(4, 24 * 30)
}

