package com.unuslumen.app.database.converters

import androidx.room.TypeConverter
import com.unuslumen.app.domain.model.Mood
import com.unuslumen.app.domain.model.SubTask
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DBConverters {

    @TypeConverter
    fun fromSubTasksList(value: List<SubTask>): String {
        return Json.encodeToString(value)
    }
    @TypeConverter
    fun toSubTasksList(value: String): List<SubTask> {
        val json = Json {
            ignoreUnknownKeys = true
        }
        return json.decodeFromString<List<SubTask>>(value)
    }

    @TypeConverter
    fun toMood(value: Int) = Mood.entries.firstOrNull { it.value == value } ?: Mood.OKAY
    @TypeConverter
    fun fromMood(value: Mood) = value.value

    @TypeConverter
    fun fromFloatList(value: List<Float>): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toFloatList(value: String): List<Float> {
        if (value.isBlank()) return emptyList()
        // Defensive: handle older schema writes that may have stored "[]" (JSON
        // empty array) instead of the empty string for an empty embedding list.
        // Without this, value.split(",") would produce ["[]"] and toFloat()
        // would throw NumberFormatException on the bracket characters.
        if (value == "[]") return emptyList()
        return value.split(",").mapNotNull { part ->
            part.trim().takeIf { it.isNotEmpty() }?.toFloatOrNull()
        }
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return value.joinToString("|||")
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.split("|||")
    }
}