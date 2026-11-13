// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * The dpkg bookkeeping that decides whether an installed package can actually be used.
 *
 * WHY THIS IS A SEPARATE, ANDROID-FREE, OBJECT (issue 31).
 *
 * A Termux package is built against `/data/data/com.termux/files`, and dpkg records that path
 * verbatim in two places: `var/lib/dpkg/info/<pkg>.list`, which is the list of files the package
 * owns, and `var/lib/dpkg/status`, which is the database `dpkg -l` reads. Inside this app neither
 * path exists. The install still reports `install ok installed` because dpkg only cares that its
 * own writes through the compatibility root reached a writable tree.
 *
 * The consequence is a package that is installed and unusable in three different ways at once,
 * and all three are answerable from the TEXT of those two records rather than from a device:
 *
 *  1. A `.list` naming the upstream prefix describes files that exist nowhere. Anything that
 *     reads it — `dpkg -L`, a package's own postinst, a person — is looking at a fiction.
 *  2. `relocatePrefixPaths` rewrites those files, but it is marker-gated and runs at init, so a
 *     package installed AFTER that pass keeps the upstream paths until the next cold start.
 *  3. A bare `dpkg -l` in a shell reads the compiled-in admin dir, which is the upstream path,
 *     so it reports "no packages found" while `apt` (which is given `--admindir` explicitly)
 *     can see the same package. Two tools, one database, two answers.
 *
 * So the rules that decide all of this live here, as pure functions over strings, and the
 * device-side pass is only the loop that applies them. That is what makes them provable on the
 * JVM in seconds instead of only on a handset.
 */
object DpkgRecords {

    /** The prefix every shipped Termux package was built against. */
    const val UPSTREAM_FILES_ROOT = "/data/data/com.termux/files"

    /**
     * Rewrite the upstream root in a dpkg record onto this install's root.
     *
     * THE REPLACEMENT IS THE TERMUX ROOT, NOT THE PREFIX. A package path is
     * `data/data/com.termux/files/usr/bin/figlet` with no leading slash in the record, and the
     * compatibility root created by TermuxProvider maps `<termuxRoot>/data/data/com.termux/files/usr`
     * onto `<termuxRoot>/usr`. Both the SLASHED and the BARE form are replaced, because
     * `dpkg`'s generated `status` file writes the bare form in some fields and the `.list`
     * file writes the slashed one.
     *
     * Returns the input unchanged when it names neither form, so a caller can compare identity
     * to decide whether a write is needed.
     */
    fun relocate(record: String, termuxRoot: String): String {
        if (record.isEmpty()) return record
        val slashed = UPSTREAM_FILES_ROOT
        val bare = UPSTREAM_FILES_ROOT.removePrefix("/")
        var out = record
        // The slashed form first: replacing the bare one first would leave a stray leading
        // slash in front of the replacement and produce `//<root>/usr/...`.
        if (out.contains(slashed)) out = out.replace(slashed, termuxRoot)
        if (out.contains(bare)) out = out.replace(bare, termuxRoot)
        return out
    }

    /** Whether a record still names the upstream prefix in either of its two written forms. */
    fun namesUpstream(record: String): Boolean =
        record.contains(UPSTREAM_FILES_ROOT) ||
            record.contains(UPSTREAM_FILES_ROOT.removePrefix("/"))

    /**
     * What a `.list` file says about the package it belongs to.
     *
     * The distinction that matters is not "how many rows" but "can this package be run". A row
     * under the prefix's `bin/` is the package's own executable; a row under `share/` is the
     * data that executable reads. Issue 31's figlet case is exactly a binary that arrived with
     * its fonts missing, so a list whose binaries are present and whose data rows are not is a
     * different answer from a list that is simply empty.
     */
    data class PackageFiles(
        /** Every path the package claims, as written in the record. */
        val rows: List<String>,
        /** Rows that name an executable under the prefix's bin or sbin. */
        val binaries: List<String>,
        /** Rows that name data under share, lib, etc, var or opt. */
        val data: List<String>,
        /** Rows still naming the upstream prefix, which cannot resolve through a plain path. */
        val upstreamRows: List<String>,
        /** Rows that already name THIS install's root, so they resolve as written. */
        val ownRows: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /**
     * Read a `.list` record into the facts a caller needs.
     *
     * Rows are taken verbatim — no normalisation this function invented — except for the
     * install-root row, which names nothing the package owns. Two convenience views are derived
     * on top: which rows are binaries and which are data.
     */
    fun readList(record: String, termuxRoot: String = UPSTREAM_FILES_ROOT): PackageFiles {
        val rows = record.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            /*
             * THE INSTALL-ROOT ROW IS NOT A PACKAGE FILE.
             *
             * dpkg writes `/.` as the first row of every .list, and it names the install root
             * itself rather than anything the package owns. Counting it as content is not
             * harmless: it names neither tree, so it is one more row that is not evidence about
             * which root the record describes, and a caller asking "did this package's files
             * land" would be checking the filesystem root. The device-side check caught it as a
             * missing path of "/." on every package.
             */
            .filter { !isInstallRootRow(it) }
            .toList()
        val binaries = rows.filter { isUnder(it, "bin") || isUnder(it, "sbin") }
        val data = rows.filter {
            isUnder(it, "share") || isUnder(it, "lib") || isUnder(it, "etc") ||
                isUnder(it, "var") || isUnder(it, "opt")
        }
        val upstream = rows.filter { namesUpstream(it) }
        // A row is "ours" when it is anchored under this install's own root. That is the fact
        // the state below actually needs, because a package list legitimately carries ANCESTOR
        // directories (`/data`, `/data/data`) that name neither tree in full.
        val own = rows.filter { it.startsWith("$termuxRoot/") || it == termuxRoot }
        return PackageFiles(rows, binaries, data, upstream, own)
    }

    /**
     * Whether a row names the install root rather than a file the package owns.
     *
     * dpkg's `.list` begins with `/.` for the root and `./` for it too in some encodings, and a
     * directory row for a parent directory is not content either. A row counts as the root when
     * it holds no name at all once the leading dot and slashes are removed.
     */
    fun isInstallRootRow(row: String): Boolean {
        val trimmed = row.trim()
        // A row that holds nothing but dots and slashes names the root: `/.`, `./`, `.`, `/`.
        // Anything with a name in it is content. Written as a character test rather than a
        // chain of removePrefix calls because the prefix order matters for the mixed form `/.`,
        // and only one order happens to work.
        return trimmed.isNotEmpty() && trimmed.all { it == '.' || it == '/' }
    }

    /**
     * Whether one recorded row lives under a named directory of the prefix.
     *
     * The row can be either form — the slashed upstream one, or this install's absolute root —
     * and both are checked by looking for `/usr/<dir>/` anywhere in the path. That is
     * deliberately loose about the root: a package that has not yet been relocated still names
     * the upstream prefix, and the classification of binary-versus-data does not depend on
     * which root the row uses.
     */
    fun isUnder(row: String, dir: String): Boolean = row.contains("/usr/$dir/") || row.contains("/$dir/")

    /**
     * Whether a row is one of dpkg's ancestor directories rather than content of the package.
     *
     * MEASURED, NOT ASSUMED (issue 31). Every package's `.list` opens by walking down to its own
     * prefix: `/.`, `/data`, `/data/data`, `/data/data/com.termux`, `/data/data/com.termux/files`,
     * `.../usr`, and only then the files the package owns. Those leading rows existed inside the
     * package author's build root and none of them is payload a user can run.
     *
     * The device run is what makes this concrete. With the ancestors counted as content, all
     * twenty-four bootstrap packages reported `present = declared - 1`, the missing path being
     * `/data/data/com.termux` every single time. The payload had landed in full; the checker was
     * counting a directory that no install ever creates inside the mapping. So an ancestor row is
     * excluded from the existence question, which is different from calling it delivered.
     */
    fun isAncestorRow(row: String, termuxRoot: String = UPSTREAM_FILES_ROOT): Boolean {
        if (isInstallRootRow(row)) return true
        val root = termuxRoot.trimEnd('/')
        /*
         * BOTH FORMS ARE CHECKED, because the row may be written either way and the check must
         * not depend on which. A `.list` that has not been relocated names `.../files/usr`; the
         * same row after a rewrite names `<root>/usr`. Comparing only the relocated form made
         * `.../files/usr` fail as "not an ancestor", which is exactly backwards: it is the
         * mapping's own entry point and never payload.
         */
        val forms = listOf(
            row.trim().removePrefix("./").trimEnd('/'),
            relocate(row, termuxRoot).removePrefix("./").trimEnd('/'),
        )
        val scaffolds = listOf(
            "/data",
            "/data/data",
            "/data/data/com.termux",
            UPSTREAM_FILES_ROOT,
            "$UPSTREAM_FILES_ROOT/usr",
            root,
            "$root/data",
            "$root/data/data",
            "$root/data/data/com.termux",
            "$root/data/data/com.termux/files",
            "$root/data/data/com.termux/files/usr",
            "$root/usr",
        )
        return forms.any { it in scaffolds }
    }

    /**
     * Resolve one recorded row to the path it names on THIS install.
     *
     * Used by the post-install check that asks whether a package's data actually landed. A row
     * is rewritten through [relocate] and then anchored at the termux root, which is the same
     * mapping dpkg's instdir uses, so the answer is the path the kernel would open.
     */
    fun resolveRow(row: String, termuxRoot: String): String {
        // The install-root row names the root itself, not a path beneath it, so it is answered
        // before any anchoring: left to the branches below it would come back as a bare "/."
        // and a caller checking existence would ask about the device's root directory.
        if (isInstallRootRow(row)) return termuxRoot
        // dpkg writes rows beginning with `./` for directories.
        val trimmed = row.trim().removePrefix("./")
        val root = termuxRoot.trimEnd('/')
        return when {
            trimmed.isEmpty() -> root
            /*
             * A ROW IS RESOLVED FROM dpkg'S INSTDIR, WHICHEVER ROOT IT NAMES.
             *
             * This is the reading half of issue 31's first fault, and the device run is what
             * corrected it. dpkg unpacks with `--instdir=<termuxRoot>`, so EVERY path a package
             * records is resolved FROM that directory, even though it is written with a leading
             * slash. A row of `/data/data/com.termux/files/usr/bin/figlet` therefore names
             * `<termuxRoot>/data/data/com.termux/files/usr/bin/figlet`, and the compatibility
             * symlink — one link over the whole of `usr` — carries it into the real prefix.
             *
             * The first version of this function relocated the row FIRST and then anchored it.
             * That produced `<termuxRoot>/usr/bin/figlet`, which happens to be the same file,
             * and it reported every one of the bootstrap's twenty-four packages as incomplete
             * with the single miss being `/data/data/com.termux` — an ancestor of the mapping
             * that no install creates. The payload had all landed. Anchoring at the root without
             * rewriting is both the simpler rule and the one dpkg actually applies.
             *
             * A row that already names this install is used as written, so a record a rewrite has
             * already reached resolves the same way.
             */
            trimmed == root || trimmed.startsWith("$root/") -> trimmed
            trimmed.startsWith("/") -> "$root$trimmed"
            else -> "$root/$trimmed"
        }
    }

    /**
     * The verdict on an install, from the records dpkg left behind.
     *
     * A package can be in `status` as installed and have delivered nothing, which is the exact
     * shape issue 31 reported. These four states keep that apart from a clean install and from
     * a package that was never unpacked at all:
     *
     *  - NOT_RECORDED: no `.list`, so dpkg never unpacked it. A failed install.
     *  - RECORDED_NOTHING: a `.list` with no rows. dpkg claims the package and owns no files.
     *  - RECORDED_UPSTREAM_ONLY: not one row names this install's root, so the record describes
     *    a tree that is not this one and nothing in it can be resolved without a rewrite.
     *  - RECORDED: at least one row already names this install, so the caller checks existence.
     */
    enum class InstallRecordState { NOT_RECORDED, RECORDED_NOTHING, RECORDED_UPSTREAM_ONLY, RECORDED }

    /**
     * THE TEST IS "DOES ANY ROW NAME THIS INSTALL", not "does every row name upstream".
     *
     * The first version of this rule asked the second question, and a real dpkg `.list` is not
     * uniform: it carries ancestor directories as it walks down — `/.`, `/data`, `/data/data`,
     * `/data/data/com.termux` — and then the file rows. Those ancestors name neither tree in
     * full, so a list whose every FILE row is upstream still has rows that are not, and the
     * equality test answered false. The ticket's own figlet list is that shape.
     *
     * Asking whether the list contains a single row anchored under this install is the fact
     * that decides whether existence checks mean anything, and it is true of exactly the
     * records that a rewrite has already reached.
     */
    fun installState(files: PackageFiles?): InstallRecordState = when {
        files == null -> InstallRecordState.NOT_RECORDED
        files.isEmpty -> InstallRecordState.RECORDED_NOTHING
        files.ownRows.isEmpty() -> InstallRecordState.RECORDED_UPSTREAM_ONLY
        else -> InstallRecordState.RECORDED
    }

    /**
     * One sentence a person can act on, naming only what the records showed.
     *
     * This is the text that goes back on the install result, and its job is to stop the surface
     * reporting `install ok installed` as success when the data never arrived. Every branch
     * names an observed fact rather than a guess, which is the same discipline the three-state
     * probes elsewhere in this package follow.
     */
    fun describeInstallState(state: InstallRecordState, packageName: String): String = when (state) {
        InstallRecordState.NOT_RECORDED ->
            "dpkg left no file list for $packageName, so the package was not unpacked. " +
                "An 'install ok installed' line from apt in this state means the unpack step " +
                "did not reach the prefix."
        InstallRecordState.RECORDED_NOTHING ->
            "$packageName is recorded as installed and owns no files at all, which is not a " +
                "working install."
        InstallRecordState.RECORDED_UPSTREAM_ONLY ->
            "$packageName's file list still names ${UPSTREAM_FILES_ROOT}, which does not exist " +
                "on this install, so nothing in the list can be resolved without a path rewrite."
        InstallRecordState.RECORDED ->
            "$packageName is recorded with a file list that names this install's tree."
    }

    /**
     * The names of every package dpkg knows about, from the `status` database.
     *
     * Read as the `Package:` stanza field rather than by matching "installed", because a package
     * can be half-configured and still matter. Returns them in the order they appear, which is
     * alphabetical in a database dpkg wrote itself.
     */
    fun installedPackages(statusRecord: String): List<String> =
        statusRecord.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("Package: ") }
            .map { it.removePrefix("Package: ").trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /**
     * Whether a package's own `status` stanza says it is fully installed.
     *
     * The `Status:` line is three words: want, eflag, status. Only `... ok installed` means the
     * package is configured and ready; `... unpacked` and `... half-configured` are the states a
     * half-applied instdir leaves behind, and both were visible in the ticket.
     */
    fun saysInstalled(statusRecord: String, packageName: String): Boolean {
        var inStanza = false
        for (line in statusRecord.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Package: ")) {
                inStanza = trimmed.removePrefix("Package: ").trim() == packageName
                continue
            }
            if (inStanza && trimmed.startsWith("Status: ")) {
                return trimmed.removePrefix("Status: ").trim().endsWith(" ok installed")
            }
        }
        return false
    }
}
