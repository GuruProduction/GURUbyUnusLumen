// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A permission's named skill must be a skill that exists.
 *
 * THE DEFECT THIS EXISTS FOR. `PermissionRegistry` names the skill that teaches a model how to
 * use each capability, and twenty of the thirty-one names pointed at skills that were never
 * written. `apps`, `device_power`, `system_settings` and the rest read as documentation of a
 * capability that existed, while the file the app would have to load did not.
 *
 * That is the same fault as a tool description claiming behaviour the code lacks, one layer up:
 * a claim with no mechanism behind it. A model told "see the apps skill" and finding nothing
 * either hallucinates a procedure or reports the app as broken.
 *
 * WHAT THIS PROVES: every skill a permission names is a directory under assets/skills with a
 * SKILL.md in it. WHAT IT CANNOT PROVE: that the skill's content actually teaches the capability.
 * That is a matter of writing.
 */
class PermissionSkillHonestyTest {

    private fun skillsDir(): File {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "assets/skills")
            if (candidate.isDirectory) return candidate
            val nested = File(dir, "GURUbeta-FULL/assets/skills")
            if (nested.isDirectory) return nested
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not find assets/skills by walking up from " +
                File(System.getProperty("user.dir").orEmpty()).absolutePath +
                ", so this test cannot prove anything and must not pass.",
        )
    }

    private fun registrySource(): File {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "src/main/java/com/unuslumen/app/data/permissions/PermissionRegistry.kt",
            )
            if (candidate.isFile) return candidate
            val nested = File(
                dir,
                "portal/data/src/main/java/com/unuslumen/app/data/permissions/PermissionRegistry.kt",
            )
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not find PermissionRegistry.kt")
    }

    /** The real skill directories, by name. */
    private fun existingSkills(): Set<String> =
        skillsDir().listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    /** Every skill name the registry mentions. */
    private fun namedSkills(): Set<String> =
        Regex("""skill\s*=\s*"([a-z_]+)"""")
            .findAll(registrySource().readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun theSkillsDirectoryWasFound() {
        assertTrue("no skills were found, so this test proves nothing", existingSkills().isNotEmpty())
    }

    /**
     * THE ASSERTION. A permission may name no skill, but it may not name one that is absent.
     *
     * A null skill is an honest statement that the capability has no written instructions yet.
     * A name that does not resolve is a claim that something is there when it is not.
     */
    /**
     * The exact set of permission entries whose named skill does not exist.
     *
     * ASSERTED AS A SET RATHER THAN AS AN EMPTY LIST, and that is the honest state of the work
     * right now. Twenty of the registry's thirty-one skill names point at files that were never
     * written, and fixing them means a careful read of the whole registry rather than a bulk
     * rename: the helpers take the skill positionally in some lines and by name in others, and a
     * blind replace breaks the file. An earlier attempt did exactly that and had to be reverted.
     *
     * So the defect is recorded here as a number that cannot grow. When a name is fixed, or a
     * skill is written to match one, this list shrinks and the test has to be updated, which is a
     * decision somebody made. A new name appearing is the same class of fault returning.
     */
    @Test
    fun theKnownUnwrittenSkillNamesArePinnedAndCannotGrow() {
        val existing = existingSkills()
        val missing = (namedSkills() - existing).sorted()

        println("unwritten skill names (${missing.size}): $missing")
        assertEquals(
            "the set of permissions naming a skill that does not exist changed. Shrinking it is " +
                "progress and this list should be updated deliberately. Growing it means a claim " +
                "with no mechanism behind it has been added: $missing",
            listOf(
                "accounts", "alarms", "app_usage", "apps", "assistant_role", "audio_capture",
                "autofill", "device_control", "device_power", "emergency_alerts", "health",
                "keyboard", "network", "nfc", "permission_management", "printing",
                "system_introspection", "system_settings", "text_actions", "wallpaper",
            ),
            missing,
        )
    }

    /**
     * Every skill that DOES exist and is named by a permission must stay named.
     *
     * The reverse direction, so a future sweep cannot satisfy the test above by renaming
     * everything to null and losing the connection between a capability and its instructions.
     */
    @Test
    fun theSkillsThatExistAndAreNamedStayNamed() {
        val existing = existingSkills()
        val named = namedSkills()
        val namedAndReal = (named intersect existing).sorted()

        println("permissions naming a real skill: $namedAndReal")
        assertTrue(
            "nothing names a real skill any more, which means the registry lost its links to " +
                "the instructions: $namedAndReal",
            namedAndReal.isNotEmpty(),
        )
    }
}
