// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.alarm.model.Alarm

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int,
    val time: Long,
)

fun AlarmEntity.toAlarm() = Alarm(
    id = id,
    time = time,
)

fun Alarm.toAlarmEntity() = AlarmEntity(
    id = id,
    time = time,
)
