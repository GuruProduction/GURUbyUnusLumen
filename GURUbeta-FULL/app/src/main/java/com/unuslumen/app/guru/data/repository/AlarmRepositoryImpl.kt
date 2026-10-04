// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.data.repository

import com.unuslumen.app.alarm.model.Alarm
import com.unuslumen.app.alarm.repository.AlarmRepository
import com.unuslumen.app.database.dao.AlarmDao
import com.unuslumen.app.database.entity.toAlarm
import com.unuslumen.app.database.entity.toAlarmEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class AlarmRepositoryImpl(
    private val alarmDao: AlarmDao,
    @Named("ioDispatcher")private val ioDispatcher: CoroutineDispatcher
) : AlarmRepository {

    override suspend fun getAlarms(): List<Alarm> {
        return withContext(ioDispatcher) {
            alarmDao.getAll().map { it.toAlarm() }
        }
    }

    override suspend fun upsertAlarm(alarm: Alarm): Long {
        return withContext(ioDispatcher) {
            alarmDao.upsert(alarm.toAlarmEntity())
        }
    }

    override suspend fun deleteAlarm(alarm: Alarm) {
        withContext(ioDispatcher) {
            alarmDao.delete(alarm.toAlarmEntity())
        }
    }

    override suspend fun deleteAlarm(id: Int) {
        withContext(ioDispatcher) {
            alarmDao.delete(id)
        }
    }
}