// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the policy does and does not block for Python (issues 283 and 284).
 *
 * THE GUARD THAT WAS REMOVED. There used to be a rule here that refused a script
 * containing the word "python" alongside an `open()` call with a write mode. It
 * failed in both directions at once:
 *
 *   - It blocked honest code. `print("python")` next to a legitimate file write
 *     was refused, and so was any script mentioning PYTHONPATH or python3.
 *   - It did not stop writes. pathlib, os.open, shutil, sqlite3 and subprocess all
 *     wrote files freely while the one careful route was refused.
 *
 * The owner's decision is that Python is fully unlocked, which is the honest
 * version of what the guard was already pretending to be. These tests exist so
 * the guard cannot quietly return, and so the sed/perl/awk rules that DO work are
 * not removed by accident at the same time.
 */
class ToolSecurityPolicyPythonTest {

    private val policy = ToolSecurityPolicy()

    private fun scriptTool() = ToolDefinition(
        name = "executePythonScript",
        description = "test",
        category = "shell",
        parameters = listOf(
            ToolParameter(
                name = "script",
                type = ToolParameterType.Script,
                required = true,
                description = "python source",
            ),
        ),
    )

    private fun check(source: String): ToolSecurityPolicy.SecurityCheckResult {
        val args = JsonObject(mapOf("script" to JsonPrimitive(source)))
        return policy.check(scriptTool(), args)
    }

    @Test
    fun aPythonScriptThatWritesFilesIsAllowed() {
        // The exact shape that used to be refused: a write, with the word
        // "python" anywhere in the source.
        val result = check(
            """
            with open("/sdcard/Download/x.txt", "w") as f:
                f.write("hello")

            print("python")
            """.trimIndent(),
        )
        assertFalse(
            "A script that writes a file and mentions python must run. Blocking it is the " +
                "false positive from issue 283.",
            result.blocked,
        )
    }

    @Test
    fun everyWriteRouteIsAllowedBecauseTheBlockIsGone() {
        // Issue 284's four bypasses, plus append mode. All of these were refused or
        // silently allowed depending on which route they took, which is the
        // incoherence that made the guard worse than nothing.
        val sources = listOf(
            "import pathlib; pathlib.Path(\"/tmp/a\").write_text(\"x\")",
            "import os; os.open(\"/tmp/b\", os.O_CREAT | os.O_WRONLY)",
            "open(\"/tmp/c\", \"a\").write(\"x\")",
            "import shutil; shutil.copy(\"/tmp/d\", \"/tmp/e\")",
            "import subprocess; subprocess.run([\"sh\", \"-c\", \"echo x > /tmp/f\"])",
            "import sqlite3; sqlite3.connect(\"/tmp/g.db\")",
            "import zipfile; zipfile.ZipFile(\"/tmp/h.zip\", \"w\")",
        )
        for (source in sources) {
            assertFalse("Should not be blocked: $source", check(source).blocked)
        }
    }

    @Test
    fun theWordPythonAloneIsNotASignalOfAnything() {
        // The old rule matched on a substring, so any of these tripped it.
        val innocent = listOf(
            "print(\"python\")",
            "print(\"pythonic\")",
            "print(\"PYTHON\")",
            "import os; print(os.environ.get(\"PYTHONPATH\"))",
            "import subprocess; subprocess.run([\"python3\", \"--version\"])",
        )
        for (source in innocent) {
            assertFalse("Should not be blocked: $source", check(source).blocked)
        }
    }

    @Test
    fun sedIsStillBlockedInAShellCommand() {
        // The rules that target a specific shell invocation stay. This is the
        // check that proves removing the Python rule did not gut the policy.
        val shellTool = ToolDefinition(
            name = "executeShell",
            description = "test",
            category = "shell",
            parameters = listOf(
                ToolParameter(
                    name = "command",
                    type = ToolParameterType.ShellCommand,
                    required = true,
                    description = "shell command",
                ),
            ),
        )
        val args = JsonObject(mapOf("command" to JsonPrimitive("sed -i 's/a/b/' /tmp/file")))
        assertTrue(
            "sed in a shell command must still be refused",
            policy.check(shellTool, args).blocked,
        )
    }

    @Test
    fun theSedRuleDoesNotReachInsideAPythonString() {
        // An honest statement of a gap that predates this change, recorded rather
        // than fixed here: the sed rule matches shell syntax at the start of the
        // value or after a pipe, so a sed invocation buried in a Python string is
        // not seen. It was never seen, and pretending otherwise in a test would
        // make the suite disagree with the code.
        val result = check("import os; os.system(\"sed -i 's/a/b/' /tmp/file\")")
        assertFalse(
            "The sed rule is shell-shaped and does not inspect Python string " +
                "literals. This test documents that, and should be changed when the " +
                "rule learns to look inside them.",
            result.blocked,
        )
    }

    @Test
    fun awkInPlaceIsStillBlocked() {
        assertTrue(check("awk -i inplace '{print}' /tmp/file").blocked)
    }

    @Test
    fun aPlainPythonScriptPassesCleanly() {
        val result = check(
            """
            import json
            print(json.dumps({"ok": True}))
            """.trimIndent(),
        )
        assertFalse(result.blocked)
        assertTrue(
            "A passing check should carry no reason text",
            result.reason.isEmpty(),
        )
    }
}
