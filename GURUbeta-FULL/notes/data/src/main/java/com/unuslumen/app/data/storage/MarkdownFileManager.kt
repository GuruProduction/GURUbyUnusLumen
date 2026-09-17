@file:Suppress("NOTHING_TO_INLINE")

package com.unuslumen.app.data.storage

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.util.errors.NoteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory
class MarkdownFileManager(
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher,
    private val applicationContext: Context
) {

    private val contentResolver = applicationContext.contentResolver

    private fun isValidFileName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.length >= MAX_FILE_NAME_LENGTH) return false
        if (name.any { it in INVALID_CHARS }) return false
        if (name.first() == ' ' || name.last() == ' ') return false
        if (name.first() == '.' || name.last() == '.') return false
        if (name == "." || name == "..") return false
        return true
    }

    private suspend fun generateUntitledTitle(folderUri: Uri): String = withContext(ioDispatcher) {
        val existingNotes = getAllNotesInFolder(folderUri)
        val existingUntitledFiles = existingNotes.map { it.title }.filter { 
            it.startsWith("Untitled") 
        }.sorted()
        
        if (existingUntitledFiles.isEmpty()) {
            return@withContext "Untitled"
        }
        
        val numbers = existingUntitledFiles.mapNotNull { title ->
            when {
                title == "Untitled" -> 0
                title.matches(Regex("^Untitled (\\d+)$")) -> {
                    title.substringAfter("Untitled ").toIntOrNull()
                }
                else -> null
            }
        }.sorted()
        
        // Find the first available number
        var nextNumber = 1
        for (num in numbers) {
            if (num < nextNumber) continue
            if (num == nextNumber) {
                nextNumber++
            } else {
                break
            }
        }
        
        return@withContext "Untitled $nextNumber"
    }

    private val upsertMutex = Mutex()


    private val observers = mutableListOf<ContentObserver>()
    private val mainLooperHandler = Handler(Looper.getMainLooper())


    private inline fun <T> Uri.observeUpdates(crossinline getData: () -> T): Flow<T> =
        callbackFlow {
            val scope = CoroutineScope(ioDispatcher + SupervisorJob())

            val contentObserver = object : ContentObserver(mainLooperHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    scope.launch { send(getData()) }
                }
            }
            contentResolver.registerContentObserver(this@observeUpdates, true, contentObserver)
            observers.add(contentObserver)

            send(getData())

            awaitClose {
                scope.cancel()
                contentResolver.unregisterContentObserver(contentObserver)
                observers.remove(contentObserver)
            }
        }.flowOn(ioDispatcher)


    fun getFolderNotesFlow(folderUri: Uri) =
        folderUri.observeUpdates { getAllNotesInFolder(folderUri) }

    fun getAllNotesFlow(rootUri: Uri) =
        rootUri.observeUpdates { getAllNotesRecursive(rootUri) }

    private fun getAllNotesRecursive(rootUri: Uri): List<Note> {
        val allNotes = mutableListOf<Note>()
        
        allNotes.addAll(getAllNotesInFolder(rootUri))

        val folders = getAllFoldersInFolder(rootUri)
        folders.forEach { folder ->
            allNotes.addAll(getAllNotesRecursive(folder.id.toUri()))
        }

        return allNotes
    }

    private fun getAllNotesInFolder(folderUri: Uri): List<Note> {
        val notes = mutableListOf<Note>()

        try {
            val childrenUri =
                DocumentFile.fromTreeUri(applicationContext, folderUri) ?: return emptyList()
            childrenUri.listFiles().forEach { documentFile ->
                if (documentFile.isFile && documentFile.name?.endsWith(FILE_EXT) == true) {
                    val name = documentFile.name ?: return@forEach
                    val dateModified = documentFile.lastModified()
                    notes.add(
                        Note(
                            title = name.substringBeforeLast("."),
                            content = "",
                            createdDate = dateModified,
                            updatedDate = dateModified,
                            pinned = false,
                            folderId = folderUri.toString(),
                            id = documentFile.uri.toString()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return notes
    }

    fun getFolderFoldersFlow(folderUri: Uri) =
        folderUri.observeUpdates { getAllFoldersInFolder(folderUri) }

    private fun getAllFoldersInFolder(folderUri: Uri): List<NoteFolder> {
        val folders = mutableListOf<NoteFolder>()

        try {
            val childrenUri =
                DocumentFile.fromTreeUri(applicationContext, folderUri) ?: return emptyList()
            childrenUri.listFiles().forEach { documentFile ->
                if (documentFile.isDirectory && documentFile.name?.startsWith(".") == false) {
                    folders.add(
                        NoteFolder(
                            name = documentFile.name ?: "Unnamed Folder",
                            id = documentFile.uri.toString()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return folders
    }

    suspend fun getFolder(folderUri: Uri): NoteFolder = withContext(ioDispatcher) {
        try {
            contentResolver.query(
                folderUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val name = cursor.getString(nameIndex)
                    NoteFolder(
                        name = name,
                        id = folderUri.toString()
                    )
                } else {
                    throw NoteException.InvalidUri
                }
            } ?: throw NoteException.InvalidUri
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.UnknownError
        }
    }

    suspend fun getNote(noteUri: Uri): Note = withContext(ioDispatcher) {
        try {
            val cursor = contentResolver.query(
                noteUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null
            ) ?: throw NoteException.FileNotFound

            cursor.use {
                if (it.moveToFirst()) {
                    val nameIndex =
                        it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val dateIndex =
                        it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    val name = it.getString(nameIndex)
                    val dateModified = it.getLong(dateIndex)

                    val content =
                        contentResolver.openInputStream(noteUri)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        } ?: ""

                    Note(
                        title = name.substringBeforeLast("."),
                        content = content,
                        createdDate = dateModified,
                        updatedDate = dateModified,
                        pinned = false,
                        id = noteUri.toString()
                    )
                } else {
                    throw NoteException.FileNotFound
                }
            }
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.UnknownError
        }
    }

    private suspend fun getFileName(noteUri: Uri): String? = withContext(ioDispatcher) {
        try {
            contentResolver.query(
                noteUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex =
                        cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun fileExists(uri: Uri): Boolean = withContext(ioDispatcher) {
        try {
            val doc = DocumentFile.fromSingleUri(applicationContext, uri)
            doc?.exists() == true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun upsertNote(note: Note, currentFolderId: String?, rootUri: Uri): String =
        upsertMutex.withLock {
            return@withLock withContext(ioDispatcher) {
                try {
                    var fileUpdateThrowable: Throwable? = null
                    val noteUri = if (note.id.isNotBlank()) {
                        // will throw the rename/move error if exist but after saving the file content
                        try {
                            updateExistingNote(note, currentFolderId, rootUri)
                        } catch (t: Exception) {
                            fileUpdateThrowable = t
                            note.id.toUri()
                        }
                    } else {
                        createNewFile(note, rootUri)
                    }

                    writeToUri(noteUri, note.content)
                    notifyFileChanged(note.folderId?.toUri() ?: rootUri)

                    fileUpdateThrowable?.let { throw it }

                    noteUri.toString()
                } catch (e: NoteException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                    throw NoteException.UnknownError
                }
            }
        }

    private suspend fun updateExistingNote(
        note: Note,
        currentFolderId: String?,
        rootUri: Uri
    ): Uri {
        val currentUri = note.id.toUri()

        if (!fileExists(currentUri)) {
            return createNewFile(note, rootUri)
        }

        val currentFileName = getFileName(currentUri)?.removeSuffix(FILE_EXT)
        val currentFolderUri = currentFolderId?.toUri() ?: rootUri
        val targetFolderUri = note.folderId?.toUri() ?: rootUri
        val newFileName = note.title.ifBlank {
            generateUntitledTitle(targetFolderUri)
        }

        val fileNameChanged = currentFileName != newFileName
        val folderChanged = currentFolderUri.toString() != targetFolderUri.toString()

        return when {
            folderChanged && fileNameChanged -> {
                val movedUri = moveFile(currentUri, currentFolderUri, targetFolderUri)
                renameFile(movedUri, newFileName, targetFolderUri)
            }

            folderChanged -> {
                moveFile(currentUri, currentFolderUri, targetFolderUri)
            }

            fileNameChanged -> {
                renameFile(currentUri, newFileName, targetFolderUri)
            }

            else -> currentUri
        }
    }

    private fun notifyFileChanged(uri: Uri) {
        contentResolver.notifyChange(uri, null)
    }

    private fun moveFile(uri: Uri, parentUri: Uri?, newParentUri: Uri): Uri {
        return try {
            DocumentsContract.moveDocument(
                contentResolver,
                uri,
                parentUri?.asParentDocumentUri() ?: throw NoteException.InvalidUri,
                newParentUri.asParentDocumentUri()
            ) ?: throw NoteException.MoveFileFailed
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.MoveFileFailed
        }
    }

    private fun Uri.asParentDocumentUri(): Uri {
        return when {
            DocumentsContract.isDocumentUri(
                applicationContext,
                this
            ) && DocumentsContract.isTreeUri(this) ->
                DocumentsContract.buildDocumentUriUsingTree(
                    this,
                    DocumentsContract.getDocumentId(this)
                )

            DocumentsContract.isDocumentUri(applicationContext, this) ->
                this

            DocumentsContract.isTreeUri(this) ->
                DocumentsContract.buildDocumentUriUsingTree(
                    this,
                    DocumentsContract.getTreeDocumentId(this)
                )

            else -> this
        }
    }


    private suspend fun renameFile(uri: Uri, newTitle: String, folderUri: Uri): Uri {
        val newName = newTitle.ifBlank { generateUntitledTitle(folderUri) }.trim()
        if (!isValidFileName(newName)) throw NoteException.InvalidFileName
        val fullFileName = newName + FILE_EXT
        fullFileName.verifyNameAndGetDocumentFile(folderUri)
        return try {
            DocumentsContract.renameDocument(
                contentResolver,
                uri,
                fullFileName
            ) ?: throw NoteException.RenameFileFailed
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.RenameFileFailed
        }
    }

    private fun writeToUri(uri: Uri, content: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.bufferedWriter().use { writer ->
                    writer.write(content)
                }
            } ?: throw NoteException.WriteFileFailed
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.WriteFileFailed
        }
    }

    private suspend fun createNewFile(note: Note, rootUri: Uri): Uri {
        val targetFolderUri = note.folderId?.toUri() ?: rootUri
        val base = note.title.ifBlank { generateUntitledTitle(targetFolderUri) }.trim()
        if (!isValidFileName(base)) throw NoteException.InvalidFileName
        val fileName = base + FILE_EXT

        val dir = fileName.verifyNameAndGetDocumentFile(targetFolderUri)
        return dir.createFile("text/markdown", fileName)?.uri
            ?: throw NoteException.CreateFileFailed
    }

    private inline fun String.verifyNameAndGetDocumentFile(folderUri: Uri): DocumentFile {
        val dir = DocumentFile.fromTreeUri(
            applicationContext,
            folderUri
        ) ?: throw NoteException.InvalidUri
        val existing = dir.listFiles().firstOrNull { it.isFile && it.name == this }?.uri
        if (existing != null) throw NoteException.NoteWithSameNameAlreadyExists
        return dir
    }

    suspend fun deleteNote(note: Note, rootUri: Uri) = withContext(ioDispatcher) {
        try {
            if (!DocumentsContract.deleteDocument(contentResolver, note.id.toUri())) {
                throw NoteException.DeleteFileFailed
            }
            notifyFileChanged(note.folderId?.toUri() ?: rootUri)
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.DeleteFileFailed
        }
    }

    suspend fun createFolder(folderName: String, parentUri: Uri): String =
        withContext(ioDispatcher) {
            try {
                val parentDir = DocumentFile.fromTreeUri(applicationContext, parentUri)
                    ?: throw NoteException.InvalidUri
                
                val existing = parentDir.listFiles().firstOrNull { it.isDirectory && it.name.equals(folderName, ignoreCase = true) }
                if (existing != null) throw NoteException.FolderWithSameNameExists

                val newFolder =
                    parentDir.createDirectory(folderName) ?: throw NoteException.CreateFolderFailed
                notifyFileChanged(newFolder.uri)
                newFolder.uri.toString()
            } catch (e: NoteException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                throw NoteException.CreateFolderFailed
            }
        }

    suspend fun updateFolder(folderUri: Uri, newName: String, rootUri: Uri) =
        withContext(ioDispatcher) {
            try {
                val folder = DocumentFile.fromTreeUri(applicationContext, folderUri.asParentDocumentUri())
                    ?: throw NoteException.InvalidUri
                val parentDir = folder.parentFile ?: throw NoteException.InvalidUri

                if (folder.name.equals(newName, ignoreCase = true)) return@withContext

                val existing = parentDir.listFiles().firstOrNull { 
                    it.isDirectory && it.name.equals(newName, ignoreCase = true)
                }
                if (existing != null) throw NoteException.FolderWithSameNameExists

                DocumentsContract.renameDocument(contentResolver, folderUri, newName)
                    ?: throw NoteException.RenameFolderFailed
                notifyFileChanged(rootUri)
            } catch (e: NoteException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                throw NoteException.RenameFolderFailed
            }
        }

    suspend fun deleteFolder(folderUri: Uri, rootUri: Uri) = withContext(ioDispatcher) {
        try {
            if (!DocumentsContract.deleteDocument(contentResolver, folderUri)) {
                throw NoteException.DeleteFolderFailed
            }
            notifyFileChanged(rootUri)
        } catch (e: NoteException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            throw NoteException.DeleteFolderFailed
        }
    }

    suspend fun searchNotes(query: String, rootUri: Uri): List<Note> = withContext(ioDispatcher) {
        val allNotes = mutableListOf<Note>()

        try {
            allNotes.addAll(searchNotesInFolder(query, rootUri))

            val folders = getAllFoldersInFolder(rootUri)
            folders.forEach { folder ->
                allNotes.addAll(searchNotesInFolder(query, folder.id.toUri()))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext allNotes
    }

    suspend fun searchFolderByName(name: String, rootUri: Uri): List<NoteFolder> = withContext(ioDispatcher) {
        val folders = getAllFoldersInFolder(rootUri)
        folders.filter { it.name.contains(name, ignoreCase = true) }
    }

    private suspend fun searchNotesInFolder(query: String, folderUri: Uri): List<Note> =
        withContext(ioDispatcher) {
            val matchingNotes = mutableListOf<Note>()
            val allNotes = getAllNotesInFolder(folderUri)

            allNotes.forEach { note ->
                try {
                    val fullNote = getNote(note.id.toUri())
                    if (fullNote.title.contains(query, ignoreCase = true) ||
                        fullNote.content.contains(query, ignoreCase = true)
                    ) {
                        matchingNotes.add(fullNote.copy(folderId = folderUri.toString()))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return@withContext matchingNotes
        }

}