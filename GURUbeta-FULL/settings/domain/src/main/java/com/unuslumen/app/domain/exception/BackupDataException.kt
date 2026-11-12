// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.exception

sealed class BackupDataException : Exception() {

    data class InvalidBackupLocation(
        val uri: String,
    ) : BackupDataException()

    data class CouldNotCreateDirectory(
        val directoryName: String,
        val parent: String,
    ) : BackupDataException()

    data class CouldNotCreateFile(
        val fileName: String,
        val parent: String,
    ) : BackupDataException()

    data object CouldNotReadFile : BackupDataException()

    /**
     * The chosen file is valid JSON but is not a content export.
     *
     * SEPARATE FROM [CouldNotReadFile] BECAUSE THE ACTION IS DIFFERENT. A file that cannot be
     * parsed at all usually means the wrong file was picked, and the picker does not filter by
     * type, so that is the common case and worth saying plainly. A file that parses and carries
     * none of the five content lists is a valid JSON document of some other shape, and picking
     * it again will not change what it is.
     */
    data object NotAContentExport : BackupDataException()

    /**
     * A format was asked for that this reader cannot handle.
     *
     * Carries the format name so the message can say which one, because the alternative is a
     * generic failure that reads to the owner as though their file was the problem.
     */
    data class ImportFormatNotSupported(
        val format: String,
    ) : BackupDataException()

    data class CouldNotWriteFile(
        val fileName: String,
        val parent: String,
    ) : BackupDataException()

    data class GenericError(
        val details: String? = null,
    ) : BackupDataException()
}
