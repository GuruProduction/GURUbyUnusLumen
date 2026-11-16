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

    /**
     * Every skill name the registry mentions, in both of the shapes the helpers accept.
     *
     * THE HELPERS TAKE THE SKILL TWO WAYS. `guard` and `privileged` take it by name
     * (`skill = "..."`), while `normal`, `runtime` and `appOp` take it positionally as the third
     * argument. A parser that reads only the named form reports most of the registry's links as
     * missing, which is the mistake this test made on its first run.
     */
    private fun namedSkills(): Set<String> {
        val text = registrySource().readText()

        val byName = Regex("""skill\s*=\s*"([a-z0-9_-]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }

        // helper("NAME", tool, skill, "unlocks") — the third argument, when it is not null.
        val positional = Regex(
            """(?:normal|runtime|appOp)\(\s*"[A-Z_.]+",\s*(?:null|"[^"]*"),\s*(?:null|"([a-z0-9_-]+)")""",
        )
            .findAll(text)
            .mapNotNull { it.groupValues[1].takeIf { name -> name.isNotEmpty() } }

        return (byName + positional).toSet()
    }

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
     * THE CORE ASSERTION. A permission may name no skill, but never one that is absent.
     *
     * A null skill is an honest statement that the capability has no written instructions yet.
     * A name that does not resolve is a claim that something is there when it is not, and a model
     * told to read it either invents a procedure or reports the app as broken.
     */
    @Test
    fun everySkillAPermissionNamesExists() {
        val existing = existingSkills()
        val named = namedSkills()
        val missing = (named - existing).sorted()

        println("named skills ${named.size}, existing ${existing.size}, missing $missing")
        assertTrue(
            "these permissions name a skill that does not exist, so a model told to read it " +
                "would find nothing: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The reverse direction, so the test above cannot be satisfied by naming nothing at all.
     *
     * These are skills that genuinely teach a capability this build has, so their link from the
     * registry has to survive any future sweep.
     */
    @Test
    fun theSkillsThatExistAndAreNamedStayNamed() {
        val named = namedSkills()
        val expected = listOf(
            "app-management", "battery-and-power", "bluetooth", "calendar-and-accounts",
            "call-log-and-contacts", "device_admin", "diagnostics", "network-and-wifi",
            "nfc-and-media", "notifications", "phone_calls", "phone_state", "quick_tiles",
            "screen_capture", "settings-and-device-control", "storage", "ui_automation",
            "usage-and-notifications",
        )
        val lost = expected.filter { it !in named }
        assertTrue("these skills exist but nothing names them: $lost", lost.isEmpty())
    }
}
