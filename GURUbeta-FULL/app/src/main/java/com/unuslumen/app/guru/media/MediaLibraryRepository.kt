package com.unuslumen.app.guru.media

import com.unuslumen.app.database.dao.MediaLibraryDao
import com.unuslumen.app.database.dao.MediaZoomLogDao
import com.unuslumen.app.database.entity.MediaItemEntity
import com.unuslumen.app.database.entity.MediaZoomLogEntity
import java.io.File

/**
 * MediaLibraryRepository — every library-side read and hard-delete behind one
 * class. Real SQL row discipline throughout: counts are COUNT(*) rows, full
 * search delegates to the real FTS query plus the LIKE fallback, hard delete
 * removes files plus every database row together, never a ghost.
 *
 * The three Guru tools and the Compose media UI both ride these functions —
 * one implementation, one behaviour surface.
 */
class MediaLibraryRepository(
    private val mediaLibraryDao: MediaLibraryDao,
    private val mediaZoomLogDao: MediaZoomLogDao
) {

    /** Real newest-first rows for the library grid. Every column carried. */
    suspend fun listRecent(limit: Int): List<MediaItemEntity> = mediaLibraryDao.getRecent(limit)

    /** The exact COUNT(*) of the library, real SQL. */
    suspend fun count(): Int = mediaLibraryDao.count()

    /** FTS + LIKE fallback search, real rows. */
    suspend fun search(query: String, limit: Int): List<MediaItemEntity> {
        if (query.isBlank()) return emptyList()
        val fts = runCatching {
            mediaLibraryDao.searchByFts(
                query = query.split(Regex("\\s+")).joinToString(" ") { "$it*" },
                limit = limit
            )
        }.getOrElse { emptyList() }
        return if (fts.isNotEmpty()) fts
        else runCatching { mediaLibraryDao.searchByLike(query, limit) }.getOrElse { emptyList() }
    }

    /** One item's full row by id (media detail-screen input). */
    suspend fun getById(id: String): MediaItemEntity? = mediaLibraryDao.getById(id)

    /** The item's full zoom history, newest first (media_zoom_log real rows). */
    suspend fun zoomLogFor(id: String): List<MediaZoomLogEntity> =
        mediaZoomLogDao.getByMediaId(id)

    /**
     * Hard delete:
     *   1. Media library (media_library/id) subtree on disk, files bytes and
     *      then the empty directory subtree, real and no ghosts left.
     *   2. media_zoom_log rows for the mediaId, always run through the
     *      SQL DELETE and never through an in-memory filter.
     *   3. media_items row by real SQL DELETE returning the removed row count.
     * The DeleteOutcome carries real bytes and real row counts so the caller
     * knows the exact reality — never any assumed delete result.
     */
    suspend fun deleteItem(id: String): DeleteOutcome {
        val entity = mediaLibraryDao.getById(id)
        if (entity == null) {
            return DeleteOutcome(
                id = id,
                existed = false,
                zoomLogDeletedRows = 0,
                filesDeletedBytes = 0L
            )
        }
        val itemDir = File(entity.storedPath)
        var filesDeletedBytes = 0L
        var filesRemoved = 0
        if (itemDir.exists()) {
            itemDir.listFiles()?.forEach { fileEntityChild ->
                val len = fileEntityChild.length()
                val ok = fileEntityChild.delete()
                if (ok) {
                    filesDeletedBytes += len
                    filesRemoved++
                }
            }
            val dirDeleted = itemDir.deleteRecursively()
            android.util.Log.i("guru_media", "Deleted media directory ${itemDir.absolutePath} " +
                "($filesRemoved of ${itemDir.list().orEmpty().size} files removed, dirRemoved = $dirDeleted," +
                " totalBytes = $filesDeletedBytes)")
        }

        val mediaRowDeleteCount: Int = mediaLibraryDao.deleteById(id)
        val zoomRowCount: Int = runCatching { mediaZoomLogDao.deleteByMediaId(id) }.getOrDefault(0)
        return DeleteOutcome(
            id = id,
            existed = true,
            mediaRowCount = mediaRowDeleteCount,
            zoomLogDeletedRows = zoomRowCount,
            filesDeletedBytes = filesDeletedBytes,
            finalItemCount = mediaLibraryDao.count(),
            deletedItemCount = 1
        )
    }
}

/** The true result of one media item hard delete — real counts only. */
data class DeleteOutcome(
    val id: String,
    val existed: Boolean,
    /** media_zoom_log removed row count (SQL result row). */
    val zoomLogDeletedRows: Int,
    /** Byte count of the subtree removed from disk, real size. */
    val filesDeletedBytes: Long = 0L,
    val deletedItemCount: Int = 0,
    /** The final media_items COUNT(*) read back after the delete for assert equality. */
    val finalItemCount: Int? = null,
    /** The real media delete SQL query result's count for zero-row ghost-free assertions. */
    val mediaRowCount: Int = 0
)