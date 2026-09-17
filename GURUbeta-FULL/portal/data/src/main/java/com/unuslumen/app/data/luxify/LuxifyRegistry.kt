package com.unuslumen.app.data.luxify

import com.unuslumen.app.domain.model.LuxifySkill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LuxifyRegistry(
    private val scanner: LuxifyScanner
) {
    private val _skills = MutableStateFlow<List<LuxifySkill>>(emptyList())
    val skills: StateFlow<List<LuxifySkill>> = _skills.asStateFlow()

    suspend fun loadSkills() {
        _skills.value = scanner.scanAll()
    }

    fun getActiveSkills(): List<LuxifySkill> {
        return _skills.value.filter { it.enabled }
    }

    fun getAllSkills(): List<LuxifySkill> {
        return _skills.value
    }

    fun getSkillByName(name: String): LuxifySkill? {
        return _skills.value.find { it.name == name }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _skills.value = _skills.value.map { skill ->
            if (skill.id == id) skill.copy(enabled = enabled) else skill
        }
    }

    fun addSkill(skill: LuxifySkill) {
        _skills.value = (_skills.value + skill).distinctBy { it.name }
    }

    fun removeSkill(id: String) {
        _skills.value = _skills.value.filter { it.id != id }
    }

    fun getSummary(): LuxifySummaryData {
        val all = _skills.value
        return LuxifySummaryData(
            totalSkills = all.size,
            enabledSkills = all.count { it.enabled },
            bundledSkills = all.count { it.source == "bundled" },
            userSkills = all.count { it.source == "user" }
        )
    }
}

data class LuxifySummaryData(
    val totalSkills: Int,
    val enabledSkills: Int,
    val bundledSkills: Int,
    val userSkills: Int
)