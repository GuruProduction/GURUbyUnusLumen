package com.unuslumen.app.domain.model

enum class BackupFrequency(val value: Int, val hours: Int) {
    HOURLY(1, 1),
    DAILY(2, 24),
    WEEKLY(3, 24 * 7),
    MONTHLY(4, 24 * 30)
}

