// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The dispatcher's refusals, proved against a real registry and a real permission state.
 *
 * WHY THIS FILE EXISTS. Issue 537's fix routes the project agent's tool calls through
 * `prepare`, and the whole value of that change is that a call which cannot be authorised
 * does not run. If `prepare` refused but something downstream executed anyway, the fix would
 * be decoration. These tests pin the refusal as an outcome the caller must handle, and pin
 * that the executor is never reached.
 *
 * WHY ROBOLECTRIC AND NOT A PLAIN JVM TEST. `prepare` calls `ContextCompat.checkSelfPermission`,
 * which needs a real Context. A fake would prove that a fake refuses. Robolectric runs
 * Android's own framework code on the JVM, so the permission decision is the platform's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DispatcherRefusalTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    /**
     * An executor that records that it was reached.
     *
     * NOT A LAMBDA, because `ToolExecutor` is an interface with a defaulted second method
     * rather than a fun interface. Recording the invocation is the point: a refusal that
     * still reached this would make the whole correction decorative.
     */
    private class RecordingExecutor(
        private val name: String,
        private val invoked: MutableList<String>,
    ) : ToolExecutor {
        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
            invoked += name
            return ToolExecutionResult.success(TestPayload(ok = true), """{"ok":true}""")
        }
    }

    /** A registry holding one tool that declares a dangerous permission, and one that declares none. */
    private fun registry(invoked: MutableList<String>): ToolRegistry {
        val registry = ToolRegistry()

        registry.register(
            ToolDefinition(
                name = "needsCamera",
                description = "A tool that declares the camera permission, used to prove a missing " +
                    "grant is refused before anything runs.",
                category = "test",
                parameters = emptyList(),
                permissions = listOf(Manifest.permission.CAMERA),
            ),
            RecordingExecutor("needsCamera", invoked),
            extractor = null,
        )

        registry.register(
            ToolDefinition(
                name = "needsNothing",
                description = "A tool that declares no permission, so a granted path can be proved to " +
                    "reach its executor.",
                category = "test",
                parameters = emptyList(),
                permissions = emptyList(),
            ),
            RecordingExecutor("needsNothing", invoked),
            extractor = null,
        )

        return registry
    }

    private fun dispatcher(registry: ToolRegistry) = ToolDispatcher(
        registry = registry,
        nameResolver = ToolNameResolver(),
        securityPolicy = ToolSecurityPolicy(),
        argumentRepair = ToolArgumentRepair(),
        context = context,
        observationRecorder = null,
    )

    private fun denied(permission: String) {
        shadowOf(context).denyPermissions(permission)
    }

    private fun granted(permission: String) {
        shadowOf(context).grantPermissions(permission)
    }

    @Test
    fun aMissingPermissionIsRefusedAsAPermissionAndNeverReachesTheExecutor() = runBlocking {
        denied(Manifest.permission.CAMERA)
        val invoked = mutableListOf<String>()

        val prepared = dispatcher(registry(invoked)).prepare("needsCamera", buildJsonObject { })

        assertTrue(
            "a tool whose permission is not granted must be refused, not prepared: $prepared",
            prepared is ToolDispatcher.Preparation.Refused,
        )
        prepared as ToolDispatcher.Preparation.Refused
        assertEquals(
            "the refusal must say it was a permission, so a caller records the right event",
            ToolDispatcher.RefusalKind.PERMISSION_DENIED,
            prepared.kind,
        )
        assertTrue(
            "the executor must never have been reached: $invoked",
            invoked.isEmpty(),
        )
    }

    @Test
    fun anUnknownToolIsRefusedAndNeverReachesAnything() = runBlocking {
        val invoked = mutableListOf<String>()
        val prepared = dispatcher(registry(invoked)).prepare("noSuchTool", buildJsonObject { })

        assertTrue(prepared is ToolDispatcher.Preparation.Refused)
        prepared as ToolDispatcher.Preparation.Refused
        assertEquals(ToolDispatcher.RefusalKind.UNKNOWN_TOOL, prepared.kind)
        assertTrue(invoked.isEmpty())
    }

    @Test
    fun aGrantedToolIsPreparedAndThenExecutes() = runBlocking {
        granted(Manifest.permission.CAMERA)
        val invoked = mutableListOf<String>()
        val dispatcher = dispatcher(registry(invoked))

        val prepared = dispatcher.prepare("needsCamera", buildJsonObject { })
        assertTrue("a granted tool prepares: $prepared", prepared is ToolDispatcher.Preparation.Ready)
        prepared as ToolDispatcher.Preparation.Ready

        // Prepared is not executed. The boundary exists so a caller records its start event
        // between the two, and that ordering is the whole reason the seam is named.
        assertTrue("preparing must not run the tool: $invoked", invoked.isEmpty())

        val result = dispatcher.executePrepared(prepared.invocation)
        assertTrue("the call then succeeds: ${result.error}", result.success)
        assertEquals(listOf("needsCamera"), invoked)
    }

    @Test
    fun aToolWithNoPermissionsPreparesAndRuns() = runBlocking {
        val invoked = mutableListOf<String>()
        val dispatcher = dispatcher(registry(invoked))
        val prepared = dispatcher.prepare("needsNothing", buildJsonObject { })
        assertTrue(prepared is ToolDispatcher.Preparation.Ready)
        dispatcher.executePrepared((prepared as ToolDispatcher.Preparation.Ready).invocation)
        assertEquals(listOf("needsNothing"), invoked)
    }

    /**
     * The refusal is a sentence a caller can act on, not an empty string.
     *
     * A refusal with no reason is the same failure as an empty result: the caller cannot tell
     * what blocked it, so it either retries blindly or reports nothing happened.
     */
    @Test
    fun aRefusalCarriesASentenceNamingTheCause() = runBlocking {
        denied(Manifest.permission.CAMERA)
        val invoked = mutableListOf<String>()
        val prepared = dispatcher(registry(invoked)).prepare("needsCamera", buildJsonObject { })
        prepared as ToolDispatcher.Preparation.Refused
        assertTrue(
            "the refusal must say something a caller can act on: '${prepared.reason}'",
            prepared.reason.length > 20,
        )
    }
}

/** A minimal serializable payload, so the test's tool returns a typed result. */
@kotlinx.serialization.Serializable
private data class TestPayload(val ok: Boolean) : ToolResultData
