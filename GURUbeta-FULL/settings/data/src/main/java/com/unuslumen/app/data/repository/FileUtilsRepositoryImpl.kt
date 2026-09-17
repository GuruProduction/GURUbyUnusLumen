package com.unuslumen.app.data.repository

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.unuslumen.app.domain.repository.FileUtilsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Named

@Factory
class FileUtilsRepositoryImpl(
    private val context: Context,
    @Named("ioDispatcher") private val ioDispatcher: CoroutineDispatcher
): FileUtilsRepository {
    override suspend fun takePersistablePermission(uri: String) {
        withContext(ioDispatcher) {
            val contentResolver = context.contentResolver
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri.toUri(), takeFlags)
        }
    }

    override suspend fun getPathFromUri(uri: String): String {
         return runCatching { uri.toUri().path?.substringAfter(":") }.getOrNull() ?: uri
    }
}

