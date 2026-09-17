package com.unuslumen.app.util.errors

sealed class NoteException : Throwable() {
    data object FileNotFound : NoteException()
    data object CreateFileFailed : NoteException()
    data object NoteWithSameNameAlreadyExists : NoteException()
    data object FolderWithSameNameExists : NoteException()
    data object WriteFileFailed : NoteException()
    data object RenameFileFailed : NoteException()
    data object MoveFileFailed : NoteException()
    data object DeleteFileFailed : NoteException()
    data object CreateFolderFailed : NoteException()
    data object RenameFolderFailed : NoteException()
    data object DeleteFolderFailed : NoteException()
    data object InvalidUri : NoteException()
    data object PermissionDenied : NoteException()
    data object InvalidFileName : NoteException()
    data object UnknownError : NoteException()
}