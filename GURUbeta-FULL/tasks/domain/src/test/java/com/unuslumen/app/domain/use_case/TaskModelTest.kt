// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Priority
import com.unuslumen.app.domain.model.SubTask
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.model.TaskFrequency
import com.unuslumen.benasher44.uuid.Uuid
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task and SubTask are persisted and serialised. These tests pin the defaults,
 * the enum wire values (which are stored in the database and must never shift),
 * and the SubTask JSON compatibility shims that protect old data from
 * proguard-era field renaming.
 */
class TaskModelTest {

    @Test
    fun `a task defaults to an open low priority item`() {
        val task = Task(title = "Ship it", id = "t1")
        assertTrue(!task.isCompleted)
        assertEquals(Priority.LOW, task.priority)
        assertEquals(TaskFrequency.DAILY, task.frequency)
        assertEquals(1, task.frequencyAmount)
        assertTrue(!task.recurring)
        assertTrue(task.subTasks.isEmpty())
        assertEquals(null, task.alarmId)
    }

    @Test
    fun `priority wire values are stable`() {
        // These ints are written to the database. If they shift, every saved
        // task changes priority silently.
        assertEquals(0, Priority.LOW.value)
        assertEquals(1, Priority.MEDIUM.value)
        assertEquals(2, Priority.HIGH.value)
    }

    @Test
    fun `task frequency wire values are stable`() {
        assertEquals(0, TaskFrequency.EVERY_MINUTES.value)
        assertEquals(1, TaskFrequency.HOURLY.value)
        assertEquals(2, TaskFrequency.DAILY.value)
        assertEquals(3, TaskFrequency.WEEKLY.value)
        assertEquals(4, TaskFrequency.MONTHLY.value)
        assertEquals(5, TaskFrequency.ANNUAL.value)
    }

    @Test
    fun `tasks compare by value not identity`() {
        val a = Task(title = "Ship it", id = "t1")
        val b = Task(title = "Ship it", id = "t1")
        val c = Task(title = "Ship it", id = "t2")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `a subtask generates a stable uuid when one is not supplied`() {
        val first = SubTask(title = "step")
        val second = SubTask(title = "step")
        assertTrue(first.id != second.id)
    }

    @Test
    fun `subtask reads the old single letter proguard names`() {
        // Old app versions wrote "a"/"b"/"c" after obfuscation. The JsonNames
        // shims are what keep those rows loadable. Losing them means a user's
        // older subtasks vanish after upgrade.
        val json = Json { ignoreUnknownKeys = true }
        val id = Uuid.randomUUID()
        val legacy = """{"a":"buy milk","b":true,"c":"$id"}"""
        val decoded = json.decodeFromString<SubTask>(legacy)
        assertEquals("buy milk", decoded.title)
        assertTrue(decoded.isCompleted)
        assertEquals(id, decoded.id)
    }

    @Test
    fun `subtask round trips through its canonical names`() {
        val json = Json
        val original = SubTask(title = "buy milk", isCompleted = false)
        val encoded = json.encodeToString(SubTask.serializer(), original)
        val decoded = json.decodeFromString(SubTask.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `task round trips through json`() {
        val json = Json
        val original = Task(
            title = "Ship it",
            description = "release build",
            priority = Priority.HIGH,
            subTasks = listOf(SubTask(title = "sign")),
            dueDate = 1234L,
            recurring = true,
            frequency = TaskFrequency.WEEKLY,
            frequencyAmount = 2,
            id = "t1",
        )
        val encoded = json.encodeToString(Task.serializer(), original)
        val decoded = json.decodeFromString(Task.serializer(), encoded)
        assertEquals(original, decoded)
    }
}
