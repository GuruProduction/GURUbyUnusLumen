// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.luxify

import com.unuslumen.app.data.tools.registry.permission.TeamThreeToolDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every bundled skill, checked as data rather than trusted as prose.
 *
 * WHY THIS EXISTS. A skill is a SKILL.md file that the app scans at boot and hands to the model
 * as a capability. Nothing validated them, so a skill with a typo in its frontmatter, or one
 * naming a tool that does not exist, would ship and fail at the moment somebody used it. The
 * frontmatter is parsed by hand-written code, which means a missing `name:` field silently makes
 * the whole file invisible rather than raising anything.
 *
 * WHAT THIS PROVES: every bundled skill parses, has a name, a description and a when_to_use, and
 * every tool it names in allowed-tools is a tool this build actually registers.
 *
 * WHAT IT CANNOT PROVE: that the instructions inside are good, or that the model follows them.
 * That is a matter of writing, not of parsing.
 */
class BundledSkillsTest {

    /**
     * Every skill directory under the bundled assets.
     *
     * The test runs with the module directory or the build directory as its working directory
     * depending on the AGP version, so the assets are found by walking up until the tree is
     * recognisable. A missing directory FAILS rather than passing, because a test that cannot
     * find its subject proves nothing.
     */
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
                File(System.getProperty("user.dir").orEmpty()).absolutePath,
        )
    }

    private fun skillFiles(): List<File> = skillsDir()
        .listFiles()
        ?.filter { it.isDirectory }
        ?.map { File(it, "SKILL.md") }
        ?.filter { it.isFile }
        .orEmpty()

    @Test
    fun thereAreBundledSkillsToCheck() {
        val count = skillFiles().size
        println("bundled skills found: $count")
        assertTrue("no skills were found, so this test proves nothing", count > 0)
    }

    /**
     * Every skill parses, which means its frontmatter is well formed and it declares a name.
     *
     * A file that does not parse is skipped by the scanner without a word, so this is the only
     * place a broken one becomes visible.
     */
    @Test
    fun everyBundledSkillParses() {
        val failures = skillFiles().mapNotNull { file ->
            val parsed = runCatching { LuxifyParser.parse(file.readText()) }.getOrNull()
            if (parsed == null) "${file.parentFile.name}: does not parse" else null
        }
        assertTrue("these skills are invisible to the app: $failures", failures.isEmpty())
    }

    @Test
    fun everySkillDeclaresANameDescriptionAndTrigger() {
        val problems = mutableListOf<String>()
        for (file in skillFiles()) {
            val parsed = LuxifyParser.parse(file.readText()) ?: continue
            if (parsed.name.isBlank()) problems += "${file.parentFile.name}: no name"
            if (parsed.description.isBlank()) problems += "${file.parentFile.name}: no description"
            if (parsed.whenToUse.isBlank()) problems += "${file.parentFile.name}: no when_to_use"
            if (parsed.bodyMarkdown.isBlank()) problems += "${file.parentFile.name}: empty body"
        }
        assertTrue("skills with missing fields: $problems", problems.isEmpty())
    }

    /**
     * A skill's directory name must match its own `name` field.
     *
     * TWO NAMES FOR ONE THING. The scanner loads by directory and the app keys by the name in the
     * file, so a mismatch produces a skill that lists under one name and is invoked by another.
     */
    @Test
    fun theSkillsWrittenInThisBatchMatchTheirDirectory() {
        // A mismatch is not automatically a defect: the scanner loads by directory and the app
        // keys by the parsed name, so a skill whose two names differ still works, it simply
        // lists under one name and is addressed by the other. `morning_briefing` is the existing
        // example, declaring itself `morning-briefing`.
        //
        // So this asserts the mismatch SET rather than absence. A new mismatch appearing is a
        // decision somebody made and has to be recorded here; an old one staying put is fine.
        // What it stops is a skill added in this batch quietly shipping under two names.
        val mismatched = skillFiles().mapNotNull { file ->
            val parsed = LuxifyParser.parse(file.readText()) ?: return@mapNotNull null
            val dirName = file.parentFile.name
            if (parsed.name.trim() != dirName) "$dirName->${parsed.name}" else null
        }.sorted()

        println("directory/name mismatches: $mismatched")
        assertEquals(
            "a new directory/name mismatch appeared, which means one skill answers to two names: " +
                "mismatches were $mismatched",
            listOf("morning_briefing->morning-briefing"),
            mismatched,
        )
    }

    /**
     * Every tool a skill names must be a tool this build registers.
     *
     * THIS IS THE ASSERTION THAT STOPS A SKILL FROM INVENTING A CAPABILITY. The model reads the
     * allowed-tools list and treats it as what it can reach. A name that does not exist reads as
     * a capability that will fail at the moment it is used, which is the same defect class as a
     * tool description claiming behaviour the code does not have.
     */
    @Test
    fun everyAllowedToolIsAToolTheBuildRegisters() {
        val registered = registeredToolNames()
        println("registered tool names known to this test: ${registered.size}")

        val invented = mutableListOf<String>()
        for (file in skillFiles()) {
            val parsed = LuxifyParser.parse(file.readText()) ?: continue
            for (tool in parsed.allowedTools) {
                if (tool.isBlank()) continue
                // A skill may name a tool from a domain this test cannot see, so only names that
                // look like tools and are absent from every registry source are reported.
                if (tool !in registered && tool !in toolsDeclaredOutsideTheDomainRegistry()) {
                    invented += "${file.parentFile.name} names '$tool'"
                }
            }
        }
        assertTrue("skills naming tools that do not exist: $invented", invented.isEmpty())
    }

    /** Every tool name the domain registry declares. */
    private fun registeredToolNames(): Set<String> =
        TeamThreeToolDomains.toolNames().toSet()

    /**
     * Tool names declared in the older tool sets, which are not in the domain registry.
     *
     * The app has two registration paths: the domain registry this batch added, and the older
     * per-tool-set list. A skill is allowed to reference either, so this reads the older list
     * from its source file rather than hardcoding names that would drift.
     */
    private fun toolsDeclaredOutsideTheDomainRegistry(): Set<String> {
        val names = mutableSetOf<String>()
        var dir: File? = skillsDir()
        var root: File? = dir
        while (root != null && !File(root, "portal/data").isDirectory) root = root.parentFile

        val toolsDir = root?.let { File(it, "portal/data/src/main/java/com/unuslumen/app/data/tools") }
            ?: return names

        toolsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Definitions.kt") }
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                // const val NAME = "toolName" is the shape both registries use.
                Regex("""const val [A-Z0-9_]+\s*=\s*"([a-zA-Z][a-zA-Z0-9_]*)"""")
                    .findAll(text)
                    .forEach { names += it.groupValues[1] }
            }
        return names
    }
}
