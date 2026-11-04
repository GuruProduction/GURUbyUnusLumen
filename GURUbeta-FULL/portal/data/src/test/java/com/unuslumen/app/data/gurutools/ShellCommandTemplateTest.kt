// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the shell-parameter contract, because issue 303 was a contract that
 * existed only in the head of whoever wrote the executor.
 *
 * THE DEFECT, measured on device in build 3.40. A shell tool declaring three
 * parameters produced:
 *
 *   `echo A=[$1]`                              -> A=[]
 *   `echo A=[${requiredOne}]`                  -> A=[]
 *   `echo A=[$requiredOne]`                    -> A=[first]
 *
 * Two of the three reasonable spellings were silently empty. The tool ran,
 * exited 0, and looked like the caller had passed a blank. Every test here is
 * one of those spellings, plus the cases that must NOT be substituted: a real
 * shell variable, an undeclared name, and a value that tries to become syntax.
 */
class ShellCommandTemplateTest {

    private val order = listOf("requiredOne", "optionalOne", "addedLater")
    private val params = mapOf(
        "requiredOne" to "first",
        "optionalOne" to "second",
        "addedLater" to "third",
    )

    // ---- the three forms that must all work ----

    @Test
    fun bareDollarNameSubstitutes() {
        val out = ShellCommandTemplate.render("echo A=[\$requiredOne]", params, order)
        assertEquals("echo A=['first']", out.command)
        assertEquals(listOf("requiredOne"), out.substituted)
    }

    @Test
    fun bracedNameSubstitutes() {
        val out = ShellCommandTemplate.render("echo A=[\${requiredOne}]", params, order)
        assertEquals("echo A=['first']", out.command)
        assertEquals(listOf("requiredOne"), out.substituted)
    }

    @Test
    fun positionalSubstitutesInDeclarationOrder() {
        val out = ShellCommandTemplate.render("echo A=[\$1] B=[\$2] C=[\$3]", params, order)
        assertEquals("echo A=['first'] B=['second'] C=['third']", out.command)
        assertEquals(order, out.substituted)
    }

    @Test
    fun bracedPositionalSubstitutes() {
        val out = ShellCommandTemplate.render("echo C=[\${3}]", params, order)
        assertEquals("echo C=['third']", out.command)
    }

    @Test
    fun twoDigitPositionalIsReadWhole() {
        val many = (1..12).map { "p$it" }
        val values = many.associateWith { "v${it.removePrefix("p")}" }
        val out = ShellCommandTemplate.render("echo [\$12]", values, many)
        assertEquals("echo ['v12']", out.command)
    }

    @Test
    fun legacyParamsPrefixStillWorks() {
        val out = ShellCommandTemplate.render("echo [\${params.requiredOne}]", params, order)
        assertEquals("echo ['first']", out.command)
    }

    // ---- the cases that must be left alone ----

    @Test
    fun realShellVariablesAreNotSubstituted() {
        val out = ShellCommandTemplate.render("echo \$HOME \$PATH \$? \$\$", params, order)
        assertEquals("echo \$HOME \$PATH \$? \$\$", out.command)
        assertTrue(out.substituted.isEmpty())
    }

    @Test
    fun anUndeclaredNameIsLeftForTheShell() {
        val out = ShellCommandTemplate.render("echo \$NOT_A_PARAM", params, order)
        assertEquals("echo \$NOT_A_PARAM", out.command)
        assertTrue(out.substituted.isEmpty())
    }

    @Test
    fun anEscapedDollarIsALiteral() {
        val out = ShellCommandTemplate.render("echo \\\$requiredOne", params, order)
        assertEquals("echo \$requiredOne", out.command)
    }

    @Test
    fun aLoneDollarIsALiteral() {
        val out = ShellCommandTemplate.render("echo 100$", params, order)
        assertEquals("echo 100$", out.command)
    }

    @Test
    fun positionalZeroIsTheShellName() {
        // $0 is the shell's own argv[0], never a tool parameter, so it must not
        // consume the first declared name.
        val out = ShellCommandTemplate.render("echo \$0", params, order)
        assertTrue("'\$0' must not resolve to the first parameter", out.substituted.isEmpty())
    }

    // ---- quoting: a value can never become syntax ----

    @Test
    fun aValueContainingAQuoteCannotBreakOut() {
        val attack = mapOf("requiredOne" to "'; rm -rf / #'")
        val out = ShellCommandTemplate.render("echo \$requiredOne", attack, listOf("requiredOne"))
        // The value survives verbatim inside one single-quoted word, with each
        // embedded quote closed, escaped and reopened.
        assertEquals("echo ''\\''; rm -rf / #'\\'''", out.command)
    }

    @Test
    fun aValueContainingADollarIsNotSubstitutedAgain() {
        // The critical property of the single-pass scan: if a VALUE could be
        // re-scanned, a caller could inject a reference to another argument, and
        // with positional forms it could inject a different parameter entirely.
        val sneaky = mapOf("requiredOne" to "\$optionalOne", "optionalOne" to "second")
        val out = ShellCommandTemplate.render("echo \$requiredOne", sneaky, listOf("requiredOne", "optionalOne"))
        assertEquals("echo '\$optionalOne'", out.command)
        assertEquals(listOf("requiredOne"), out.substituted)
    }

    @Test
    fun aNullValueBecomesAnEmptyWord() {
        val out = ShellCommandTemplate.render("echo [\$optionalOne]", mapOf("optionalOne" to null), order)
        assertEquals("echo ['']", out.command)
    }

    // ---- positional past the declared list ----

    @Test
    fun aPositionalPastTheDeclarationIsReportedRatherThanSilent() {
        val out = ShellCommandTemplate.render("echo [\$9]", params, order)
        assertEquals("echo ['']", out.command)
        assertEquals(listOf(9), out.positionalOutOfRange)
    }

    // ---- the authoring-time warning (issue 303's third half) ----

    @Test
    fun aDeclaredParameterTheCommandNeverMentionsIsNamed() {
        val unused = ShellCommandTemplate.unreferencedParameters("echo \$requiredOne", order)
        assertEquals(listOf("optionalOne", "addedLater"), unused)
    }

    @Test
    fun aParameterUsedPositionallyCountsAsReferenced() {
        val unused = ShellCommandTemplate.unreferencedParameters("echo \$1 \$2 \$3", order)
        assertTrue("Positional references must count as use, or a correct tool is warned about", unused.isEmpty())
    }

    @Test
    fun aCommandThatMentionsNothingNamesEveryParameter() {
        val unused = ShellCommandTemplate.unreferencedParameters("echo hello", order)
        assertEquals(order, unused)
    }
}
