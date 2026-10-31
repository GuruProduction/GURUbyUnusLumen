// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import org.koin.core.module.Module

/**
 * DomainToolModule — one device-senses domain's whole wiring, in its own file.
 *
 * THE PROBLEM THIS SOLVES. A new tool normally needs three shared files touched:
 * its definitions object added to GeneratedToolRegistrations, its executor bound
 * in AiDataModule, and possibly a dispatcher change. Three agents adding tools in
 * parallel therefore conflict on every single issue, and every rebase is a
 * hand-merge of unrelated lists. That is what the batch brief warns about, and it
 * is a coordination cost paid forty-four times.
 *
 * With this seam, a domain owns exactly one file. It declares its tool sets and
 * its Koin bindings together, and the two aggregator call sites are edited once
 * here rather than once per tool. A domain file never mentions another domain, so
 * two agents can never touch the same line.
 *
 * WHY THE DEFINITIONS AND THE EXECUTOR LIVE TOGETHER. They were split historically
 * — definitions in a central list, executors in a DI module — and that split is
 * how issue 40 and 44 happened: a tool description and the code behind it drifted
 * apart because nothing forced them to change in one place. A domain file that
 * declares both makes drift a compile error instead of a support ticket.
 */
interface DomainToolModule {
    /** Stable identifier for logs and for the report back to the batch owner. */
    val id: String

    /**
     * The tool sets this domain registers, each a ToolSetRegistration carrying
     * its definitions and naming its executor class.
     *
     * Empty is a true answer while a domain has no tools yet; it is never a
     * placeholder for work that was meant to happen.
     */
    val registrations: List<ToolSetRegistration>

    /**
     * The Koin bindings for this domain's executors and any helper it owns.
     *
     * Declared as a function rather than a val so a factory binding is created
     * fresh each time Koin builds its module graph, which is what the existing
     * per-executor `factory { }` bindings do.
     */
    fun bindings(): Module
}
