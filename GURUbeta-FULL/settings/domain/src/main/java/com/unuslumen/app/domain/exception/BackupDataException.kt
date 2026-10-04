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

    data class CouldNotWriteFile(
        val fileName: String,
        val parent: String,
    ) : BackupDataException()

    data class GenericError(
        val details: String? = null,
    ) : BackupDataException()
}
