package com.unuslumen.app.ui.errors

import androidx.annotation.StringRes
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarMessage
import com.unuslumen.app.util.errors.NoteException

@StringRes
fun NoteException.toMessageResId(): Int {
    return when (this) {
        NoteException.FileNotFound -> R.string.error_note_file_not_found
        NoteException.CreateFileFailed -> R.string.error_note_create_file_failed
        NoteException.NoteWithSameNameAlreadyExists -> R.string.error_note_file_with_same_name_exists
        NoteException.WriteFileFailed -> R.string.error_note_write_file_failed
        NoteException.RenameFileFailed -> R.string.error_note_rename_file_failed
        NoteException.MoveFileFailed -> R.string.error_note_move_file_failed
        NoteException.DeleteFileFailed -> R.string.error_note_delete_file_failed
        NoteException.CreateFolderFailed -> R.string.error_note_create_folder_failed
        NoteException.RenameFolderFailed -> R.string.error_note_rename_folder_failed
        NoteException.DeleteFolderFailed -> R.string.error_note_delete_folder_failed
        NoteException.InvalidUri -> R.string.error_note_invalid_uri
        NoteException.PermissionDenied -> R.string.error_note_permission_denied
        NoteException.InvalidFileName -> R.string.error_note_invalid_file_name
        NoteException.UnknownError -> R.string.error_note_unknown_error
        NoteException.FolderWithSameNameExists -> R.string.error_folder_exists
    }
}

fun NoteException.toSnackbarError() = LocalisedSnackbarMessage.Error(toMessageResId())

