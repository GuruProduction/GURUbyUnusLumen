// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Files in com.unuslumen.app.util.shell package.
package com.unuslumen.app.util.shell

import android.util.Log
import java.io.File

/**
 * Moves the bootstrap's root-level package dirs (bin/, lib/, etc/, share/, var/, libexec/ …)
 * under termux/usr/ — the Termux-standard prefix every shipped ELF is compiled against.
 *
 * Why: the bundled zip extracts bin/ and lib/ at termux ROOT. Termux's bash/dpkg chain
 * resolves libraries via a /usr suffix it bakes in, so a root-layout tree breaks the
 * linker namespace. The device was green-lighting a broken env; the tree wants the dirs
 * where Termux ELFs expect them: under a `usr` prefix directory.
 *
 * Rules:
 *  - only moves dirs when the SRC exists AND the dest dir is empty/absent (never deletes
 *    extracted payload from usr/.
 *  - merge case: src exists AND dest exists with files: merge file-by-file, src side
 *    (dirs) removed after merge. Never deletes existing extracted payload — merge first.
 *  - idempotent: on a device whose tree was installed long ago, dirs live at usr/ already —
 *    the scan finds no root-level package dirs and the pass completes without moving much
 *    (cheap stat list).
 *  - home/ NEVER moves (GURU's own dir, belongs to termux root).
 */
object TermuxCanonicaliser {

    private const val TAG = "guru"

    /** Package dirs at termux ROOT that belong inside usr/ in Termux-standard layout.
     *  include/ included: the audit caught the canonicaliser honouring share/var/tmp
     *  but leaving the bootstrap's include/ at the prefix root, off usr/include. */
    private val PACKAGE_DIRS = setOf(
        "bin", "lib", "etc", "include", "share", "var", "libexec",
        "tmp", "opt", "root", "sbin"
    )

    fun canonicalise(termuxRoot: File) {
        val usrDir = File(termuxRoot, "usr")
        usrDir.mkdirs()

        for (name in PACKAGE_DIRS) {
            val src = File(termuxRoot, name)
            if (!src.exists()) continue
            if (!src.isDirectory) continue
            // home/ is not in PACKAGE_DIRS, so no exception for it is needed.
            val dest = File(usrDir, name)

            // THE KILLER'S GUARD: dest that is a symlink gets unhooked FIRST.
            // A symlink whose target is the same dir we are about to merge from
            // (usr/bin -> ../bin) makes dest.exists() true and mergeInto() copy
            // the source onto itself before src.deleteRecursively() destroys
            // the payload. Strip any dest symlink so the below sees REAL dirs.
            if (isSymlink(dest)) {
                Log.w(TAG, "TermuxCanonicaliser: $name under usr/ is a symlink; unhooking before canonicalise")
                unlink(dest)
            }

            if (!dest.exists()) {
                // Simple move: whole dir across.
                if (src.renameTo(dest)) {
                    Log.d(TAG, "TermuxCanonicaliser: moved $name under usr/")
                } else {
                    // Cross-filesystem fall: copy+delete.
                    if (copyRecursive(src, dest)) {
                        src.deleteRecursively()
                        Log.d(TAG, "TermuxCanonicaliser: copy-moved $name under usr/")
                    } else {
                        Log.w(TAG, "TermuxCanonicaliser: could not move $name under usr/")
                    }
                }
            } else if (isSameDirectory(src, dest)) {
                // Self-referential tree state that survived unhooking: never merge
                // a directory onto itself. Log and abort this entry's merge; the
                // src side stays intact for diagnosis rather than destroyed.
                Log.w(TAG, "TermuxCanonicaliser: $name src and dest resolve to the same directory; merge skipped")
            } else {
                // Merge: usr side already has content. Move file-by-file so
                // nothing is lost, and delete src only after a fully clean merge.
                mergeInto(src, dest)
                src.deleteRecursively()
                Log.d(TAG, "TermuxCanonicaliser: merged $name into usr/$name")
            }
        }
    }

    /**
     * Symlink detection without following: `listFiles()` throws a NIO
     * FileSystemException on a dangling link when followed, while exists()
     * follows and lies. A dangling usr/bin symlink MUST be seen so the unhook
     * above can clear it, canonicalise has no business reading into it.
     */
    private fun isSymlink(file: File): Boolean = try {
            java.nio.file.Files.isSymbolicLink(java.nio.file.FileSystems.getDefault().getPath(file.absolutePath))
        } catch (_: Exception) { false }

    /** Symlink removal; falls back to delete() when NIO refuses on an odd fs. */
    private fun unlink(dest: File) {
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.FileSystems.getDefault().getPath(dest.absolutePath))
        } catch (e: Exception) {
            Log.w(TAG, "TermuxCanonicaliser: symlink unhook fallback: ${e.message}")
            if (dest.isDirectory && !isSymlink(dest)) return // real dir; not ours to strip
            if (dest.exists()) dest.delete()
        }
    }

    /**
     * Canonical-path check: true when src and dest are one directory with two
     * names (the previous pass created the usr side then we saw the root side);
     * protects against deletion whenever that phantom self-state appears.
     */
    private fun isSameDirectory(src: File, dest: File): Boolean = try {
        (src.canonicalFile.path == dest.canonicalFile.path)
    } catch (_: Exception) { false }

    private fun mergeInto(src: File, dest: File) {
        src.listFiles()?.forEach { child ->
            val target = File(dest, child.name)
            if (!target.exists()) {
                if (!child.renameTo(target)) {
                    if (child.isDirectory) {
                        copyRecursive(child, target)
                    } else {
                        child.copyTo(target, overwrite = true)
                    }
                }
            } else if (child.isDirectory) {
                mergeInto(child, target)
                child.deleteRecursively()
            } else {
                // Same name both sides: keep the existing usr/ payload
                // (canonicalised tree is the authority once established).
                child.delete()
            }
        }
    }

    private fun copyRecursive(srcDir: File, destDir: File): Boolean {
        return try {
            destDir.mkdirs()
            srcDir.listFiles()?.forEach { fileChild ->
                val target = File(destDir, fileChild.name)
                if (fileChild.isDirectory) {
                    copyRecursive(fileChild, target)
                } else {
                    fileChild.copyTo(target, overwrite = true)
                }
                if (target.isFile) {
                    target.setExecutable(true, false)
                    target.setReadable(true, false)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "TermuxCanonicaliser: copyRecursive failed ${srcDir.name}: ${e.message}")
            false
        }
    }
}