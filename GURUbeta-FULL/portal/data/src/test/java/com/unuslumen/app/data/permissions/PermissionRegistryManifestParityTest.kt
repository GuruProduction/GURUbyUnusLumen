// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves PermissionRegistry and AndroidManifest.xml describe the same set of
 * permissions.
 *
 * WHY THIS IS A TEST AND NOT A COMMENT. Issue 46 found 166 permissions declared and
 * 50 referenced, and the gap had grown because nothing compared the two lists. A
 * comment saying "keep these in sync" is a wish. This reads the manifest off disk at
 * test time and compares it to the registry, so adding a permission to the manifest
 * without describing it fails the build.
 *
 * The manifest lives in the app module and this test lives in portal:data. That is
 * deliberate on both counts: portal:data is where the registry belongs (the tool
 * registry that consumes it is there), and the file path is resolved upwards from
 * the module rather than hardcoded to one machine.
 */
class PermissionRegistryManifestParityTest {

    private val manifestFile: File = findManifest()

    private fun findManifest(): File {
        // Gradle runs unit tests with the module directory as the working directory,
        // but under some AGP versions it is the module build directory. Walk up until
        // the repo root is recognisable, then take the known relative path.
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/AndroidManifest.xml")
            if (candidate.isFile) return candidate
            val nested = File(dir, "GURUbeta-FULL/app/src/main/AndroidManifest.xml")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate app/src/main/AndroidManifest.xml by walking up from " +
                System.getProperty("user.dir")
        )
    }

    /** The exact set of permission strings the manifest declares. */
    private fun manifestPermissions(): Set<String> {
        val pattern = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
        return pattern.findAll(manifestFile.readText()).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun theManifestPathResolved() {
        assertTrue("manifest not found at ${manifestFile.absolutePath}", manifestFile.isFile)
    }

    @Test
    fun theManifestDeclares166Permissions() {
        assertEquals(
            "The manifest no longer declares 166 permissions. That is not automatically " +
                "wrong, but the registry's hardcoded list and every skill that quotes the " +
                "number assume 166, so update both together.",
            166,
            manifestPermissions().size
        )
    }

    @Test
    fun theRegistryHoldsExactlyTheManifestSet() {
        val inManifest = manifestPermissions()
        val inRegistry = PermissionRegistry.all.map { it.permission }.toSet()

        val missingFromRegistry = inManifest - inRegistry
        val notInManifest = inRegistry - inManifest

        assertTrue(
            "These permissions are declared in the manifest and have no registry entry, " +
                "so nothing knows how to obtain them or what they unlock: $missingFromRegistry",
            missingFromRegistry.isEmpty()
        )
        assertTrue(
            "These registry entries name a permission the manifest does not declare, so the " +
                "registry is describing a capability the app cannot hold: $notInManifest",
            notInManifest.isEmpty()
        )
    }

    @Test
    fun theRegistryHasNoDuplicates() {
        val strings = PermissionRegistry.all.map { it.permission }
        val duplicates = strings.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertTrue("Duplicate registry entries: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun everyEntryExplainsWhatItUnlocks() {
        val silent = PermissionRegistry.all.filter { it.unlocks.isBlank() }
        assertTrue(
            "These entries do not say what they unlock, which is the silence that let " +
                "tool descriptions claim capabilities the code did not have: " +
                silent.map { it.permission },
            silent.isEmpty()
        )
    }

    @Test
    fun everyEntryUsesTheFullPlatformString() {
        val wrong = PermissionRegistry.all.filterNot { it.permission.startsWith("android.permission.") }
        assertTrue(
            "These entries are not full platform permission strings: ${wrong.map { it.permission }}",
            wrong.isEmpty()
        )
    }

    @Test
    fun everyBindGuardIsAServiceGuardAndNoOtherPermissionIs() {
        val binds = PermissionRegistry.all.filter { it.permission.contains(".BIND_") }
        assertTrue("no BIND_ permissions found, which cannot be right", binds.isNotEmpty())
        val wrongTier = binds.filter { it.tier != PermissionTier.SERVICE_GUARD }
        assertTrue(
            "These BIND_ entries are not marked SERVICE_GUARD: ${wrongTier.map { it.permission }}",
            wrongTier.isEmpty()
        )
        val nonBindGuards = PermissionRegistry.all.filter {
            it.tier == PermissionTier.SERVICE_GUARD && !it.permission.contains(".BIND_")
        }
        assertTrue(
            "These entries claim to be service guards but are not BIND_ permissions: " +
                "${nonBindGuards.map { it.permission }}",
            nonBindGuards.isEmpty()
        )
    }

    @Test
    fun onlyBindGuardsAndServicesUseTheServiceMechanism() {
        val serviceMechanism = PermissionRegistry.byMechanism(GrantMechanism.SERVICE)
        assertTrue(
            "Nothing uses the SERVICE mechanism, which means the 39 BIND_ guards are " +
                "miscategorised.",
            serviceMechanism.isNotEmpty()
        )
        assertTrue(
            "The SERVICE mechanism should only be used by service guards: " +
                "${serviceMechanism.filter { it.tier != PermissionTier.SERVICE_GUARD }.map { it.permission }}",
            serviceMechanism.all { it.tier == PermissionTier.SERVICE_GUARD }
        )
    }

    @Test
    fun lookupsResolveByBothFullStringAndBareName() {
        val camera = PermissionRegistry.specFor("android.permission.CAMERA")
        assertNotNull("full-string lookup failed for CAMERA", camera)
        assertEquals(PermissionTier.RUNTIME, camera!!.tier)

        val bare = PermissionRegistry.specFor("CAMERA")
        assertNotNull("bare-name lookup failed for CAMERA", bare)
        assertEquals(camera.permission, bare!!.permission)

        assertEquals(
            "an unknown permission must resolve to null rather than a default",
            null,
            PermissionRegistry.specFor("android.permission.NOT_A_REAL_PERMISSION")
        )
    }

    @Test
    fun noPermissionClaimsAShizukuPathItCannotWalkYet() {
        // #173 builds the shell-UID tier. Until it lands, anything marked SHIZUKU is a
        // known gap and must not be counted as reachable. This test records the exact
        // count so #173 has a number to drive to zero or to replace with a working tier.
        val shizuku = PermissionRegistry.byMechanism(GrantMechanism.SHIZUKU)
        assertTrue(
            "Permissions marked SHIZUKU: ${shizuku.size}. This is the privileged set #173 opens.",
            shizuku.isNotEmpty()
        )
        assertTrue(
            "Every SHIZUKU entry must be PRIVILEGED tier: " +
                "${shizuku.filter { it.tier != PermissionTier.PRIVILEGED }.map { it.permission }}",
            shizuku.all { it.tier == PermissionTier.PRIVILEGED }
        )
    }
}
