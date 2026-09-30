# MANIFEST — GURU MEDIA MODULE
Every module declared with absolute path, imports, exports and single
responsibility before build (per CLI BUILD CONTRACT Rule 4). Updated when
any file changes. Pinned at the plan approval of 30 September 2026.

## DATABASE LAYER (core/database)

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/entity/MediaItemEntity.kt
Imports: androidx.room.ColumnInfo; androidx.room.Entity; androidx.room.Index; androidx.room.PrimaryKey
Exports: data class MediaItemEntity (one stable persist shape, media_items table)
Responsibility: the library's one media row shape for ingest results.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/entity/MediaZoomLogEntity.kt
Imports: androidx.room.ColumnInfo; androidx.room.Entity; androidx.room.Index; androidx.room.PrimaryKey
Exports: data class MediaZoomLogEntity (media_zoom_log table)
Responsibility: records every zoom performed against saved media.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/dao/MediaLibraryDao.kt
Imports: androidx.room.Dao; androidx.room.Insert; androidx.room.OnConflictStrategy; androidx.room.Query; androidx.room.SkipQueryVerification; androidx.room.Upsert; com.unuslumen.app.database.entity.MediaItemEntity
Exports: interface MediaLibraryDao (insert, getById, getBySha256, getRecent, count, searchByFts, searchByLike, updateOcr, deleteById returning Int)
Responsibility: every library query, real SQL row discipline.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/dao/MediaZoomLogDao.kt
Imports: androidx.room.Dao; androidx.room.Delete; androidx.room.Insert; androidx.room.OnConflictStrategy; androidx.room.Query; androidx.room.Transaction; com.unuslumen.app.database.entity.MediaItemEntity (docs only); com.unuslumen.app.database.entity.MediaZoomLogEntity
Exports: interface MediaZoomLogDao (insert, getByMediaId, countByMediaId, deleteZoomeLogEntryByEnt, delete(transactional), deleteByMediaId returning Int)
Responsibility: zoom log row queries + transactional per-item delete.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/migrations/Migration23To24.kt
Imports: androidx.room.migration.Migration; androidx.sqlite.db.SupportSQLiteDatabase
Exports: val MIGRATION_23_24 (pinned PART D schema: media_items + media_zoom_log + media_items_fts external-content FTS5 triggers + rebuild)
Responsibility: exact migration producing the schema the plan pins.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/GuruDatabase.kt (touched)
Imports added: MediaLibraryDao; MediaZoomLogDao; MediaItemEntity; MediaZoomLogEntity
Exports added: version = 24; abstract fun mediaLibraryDao(), mediaZoomLogDao()
Responsibility: the Room database's one entity list plus dao getters.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/di/DatabaseModule.kt (touched)
Imports added: MIGRATION_23_24
Exports added: addMigrations(...) trailing MIGRATION_23_24; two Koin DAO getters
Responsibility: database build callback with migration registered.

## PIPELINE LAYER (app guru/media package)

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaTypes.kt
Imports: java.io.File; java.util.UUID (sidecar doc types)
Exports: MediaProbeInfo; SceneKeyFrame; TranscriptChunk; TimelineEntry; TimelineData; IngestResult
Responsibility: the module's one data-shape source of truth, zero behaviour.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaStore.kt
Imports: android.content.Context; java.io.File
Exports: MediaStore object (LIBRARY_DIR_NAME, TRANSCRIPT_JSON_NAME, SCENES_JSON_NAME, POSTER_FILE_NAME, libraryRoot, mediaDir, keyFrameFilePath, transcriptFile, scenesFile, posterFile, zoomFile, storedFile)
Responsibility: the only owner of every on-device location.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaProbe.kt
Imports: android.media.MediaExtractor; android.media.MediaFormat; android.media.MediaMetadataRetriever; java.io.File
Exports: MediaProbe object probe(filePath): MediaProbeInfo?
Responsibility: absolute real metadata about any probed media file.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/FileOcrHelper.kt
Imports: android.content.Context; android.graphics.Bitmap; android.graphics.BitmapFactory; com.unuslumen.app.util.shell.AppRuntimeExec
Exports: FileOcrHelper object ocrWholeImage(context, file): String (suspend)
Responsibility: bundled tesseract OCR of whole-image attachments; the processFile engine's raster path.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/KeyframeExtractor.kt
Imports: android.content.Context; android.graphics.Bitmap; android.graphics.BitmapFactory; android.util.Log; com.unuslumen.app.util.shell.AppRuntimeExec; java.io.File; java.io.FileOutputStream
Exports: KeyframeExtractor object extract(context, filePath, mediaId, durationSec, width, height): List<SceneKeyFrame> (suspend)
Responsibility: duration-scaled scene keyframe strip writing real JPGs on-disk with real per-frame OCR.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/TranscriptionEngine.kt
Imports: android.content.Context; com.unuslumen.app.data.tools.AudioNative; com.unuslumen.app.data.tools.SpeechRecognition; java.io.File; java.io.RandomAccessFile
Exports: TranscriptionEngine object transcribe(context, filePath): Result (suspend), the 25-word cap + real WAV-clock chunks.
Responsibility: on-device Vosk transcription with exact real timing labels.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/TimelineAssembler.kt
Imports: (module-local only; com.unuslumen.app.guru.media types)
Exports: TimelineAssembler object assemble(scenes, chunks, header): TimelineData; data class TimelineDataHeader
Responsibility: strip merge ordering — real chronological Guru-facing document rows.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaSerialization.kt
Imports: com.unuslumen.app.database.entity.MediaItemEntity; kotlinx.serialization.KSerializer; kotlinx.serialization.Serializable; kotlinx.serialization.builtins.ListSerializer; kotlinx.serialization.json.Json
Exports: mediaJson (internal); TranscriptChunkSer; SceneKeyFrameSer; TranscriptSerialization; SceneSerialization; MediaDeliveryTextRenderer; extension
   - MediaItemEntity.mediaDeliveryText(): String
   - SceneSerialization.MediaItemListJsonFromDir(mediaDir): String
   - SceneSerialization.jsonToListIfExist(mediaDir): String (the read path)
   - TranscriptSerialization.TranscriptJsonFromDir(mediaDir): String
Responsibility: one single stable JSON source for the two sidecar documents plus persisted read-back.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaIngestService.kt
Imports: android.content.Context; com.unuslumen.app.database.dao.MediaLibraryDao; com.unuslumen.app.database.entity.MediaItemEntity; kotlinx.coroutines.Dispatchers; kotlinx.coroutines.withContext; java.io.File; java.security.MessageDigest; java.util.UUID
Exports: MediaIngestService class ingest(cachedPath, originalName, mimeType): IngestResult (suspend)
Responsibility: the full pipeline's entrypoint, sha256 idempotent, real writes.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaDeliveryTextProvider.kt
Imports: android.content.Context; com.unuslumen.app.domain.media.MediaDeliveryPort; com.unuslumen.app.domain.model.AiMessage; com.unuslumen.app.domain.model.AiMessageAttachment
Exports: class MediaDeliveryTextProvider (real MediaDeliveryPort) buildDeliveryText(message): String
Responsibility: strip document carrier into attachmentsText on every media message send (real ingest + real render).

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaLibraryRepository.kt
Imports: com.unuslumen.app.database.dao.MediaLibraryDao; com.unuslumen.app.database.dao.MediaZoomLogDao; com.unuslumen.app.database.entity.MediaItemEntity; com.unuslumen.app.database.entity.MediaZoomLogEntity; java.io.File
Exports: class MediaLibraryRepository — (listRecent, count, search, getById, zoomLogFor, deleteItem); data class DeleteOutcome
Responsibility: all library-side reads and the hard-delete path, zero ghosts.

## TOOL LAYER

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaToolDefinitionsPlus.kt
Imports: com.unuslumen.app.data.tools.registry.ToolDefinition; ToolExecutor; ToolParameter; ToolParameterType; ToolSetRegistration; kotlin.reflect.KClass
Exports: GuruMediaTools object; MediaToolDefinitionsPlus object (three definitions + fully-qualified KClass resolution)
Responsibility: three definitions the engine gets; nothing named ingest.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaLibraryToolsExecutor.kt
Imports: android.graphics.Bitmap; android.graphics.BitmapFactory; android.util.Log; com.unuslumen.app.data.tools.registry.ToolExecutionResult; ToolExecutor; com.unuslumen.app.database.dao.MediaLibraryDao; com.unuslumen.app.database.dao.MediaZoomLogDao; com.unuslumen.app.database.entity.MediaZoomLogEntity; kotlinx.coroutines.Dispatchers; kotlinx.coroutines.withContext; kotlinx.serialization.builtins.ListSerializer; kotlinx.serialization.json.Json / JsonObject / buildJsonObject / put; java.io.File; java.io.FileOutputStream; java.text.SimpleDateFormat; java.util.Date; java.util.Locale
Exports: MediaLibraryToolsExecutor class (mediaZoom; mediaSearch; mediaRecall, real SQL); MediaResultData
Responsibility: the tools' one real executor: every call produces real bytes.

## HOOK + DI

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/MediaAttachmentHook.kt
Imports: android.content.Context; android.util.Log; com.unuslumen.app.domain.model.AiMessage; com.unuslumen.app.domain.model.AiMessageAttachment; com.unuslumen.app.database.dao.MediaLibraryDao; com.unuslumen.app.database.dao.MediaZoomLogDao
Exports: MediaAttachmentHook class (onMediaMessage ingest path; backfillAttachedCache idempotent one-time boot backfill; libraryRepository, deliveryTextProvider)
Responsibility: the wire from attachments into the library (ingest), before chat reads the media message.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/di/MediaApplicationModule.kt
Imports: MediaAttachmentHook; MediaDeliveryTextProvider(MediaDeliveryPort); MediaLibraryDao; MediaZoomLogDao; MediaLibraryRepository; org.koin.android.ext.koin.androidContext; org.koin.dsl.module
Exports: val mediaApplicationModule (all four bindings above, exactly per plan PART E order)
Responsibility: Koin wiring binding the whole media library from real DAOs exactly once per process.

## DOMAIN PORT (portal/domain)

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/domain/src/main/java/com/unuslumen/app/domain/media/MediaDeliveryPort.kt
Imports: com.unuslumen.app.domain.model.AiMessage
Exports: fun interface MediaDeliveryPort
Responsibility: domain boundary the presentation layer reaches without carrying any app-package import.

## TOOL REGISTRATION (portal/data)

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/tools/GuruMediaToolDefinitions.kt
Imports: com.unuslumen.app.data.tools.registry. (ToolDefinition + ToolExecutor + ToolParameter + ToolParameterType + ToolResultExtractor + ToolSetRegistration); kotlin.reflect.KClass
Exports: GuruMediaTools object, MediaToolDefinitionsPlus object (real ToolSetRegistration carrying all three definitions; resolve executor class via Class.forName bound exactly at runtime)
Responsibility: the tool registration binding list's real data; loaded by GeneratedToolRegistrations.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/tools/registry/MediaLibraryToolsRegistration.kt
Imports: (none, documentation)
Exports: MediaLibraryToolsRegistration documentation object carrying the ZOOM / SEARCH / RECALL constants for reference.
Responsibility: the KSP-less chain's real registration note, one reference document per plan; all real tools listed in the data file above.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/tools/registry/ToolRegistration.kt (touched)
Imports added: com.unuslumen.app.data.tools.MediaToolDefinitionsPlus
Exports added: one registrations list row for the media library tool family
Responsibility: the three media tools' live registration at app startup.

## UI SURFACES

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/ui/src/main/java/com/unuslumen/app/ui/navigation/Screen.kt (touched)
Exports added: data object MediaScreen; data class MediaDetailScreen(mediaId)
Responsibility: routes.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/ui/MediaViewModel.kt
Imports: android.app.Application; androidx.lifecycle.ViewModel; androidx.lifecycle.viewModelScope; com.unuslumen.app.database.entity.MediaItemEntity; com.unuslumen.app.guru.media.MediaLibraryRepository; kotlinx.coroutines.flow.MutableStateFlow/StateFlow/asStateFlow; kotlinx.coroutines.launch; org.koin.android.annotation.KoinViewModel
Exports: MediaViewModel (refreshList; search; loadDetail; deleteItem; items; isEmpty; detailItem)
Responsibility: feeds every media surface real DB SQL, no fabricated rows.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/ui/MediaScreen.kt
Imports: compose foundation; material3 OutlinedTextField; material3 assist components; NavHostController; rememberNavController; MediaItemEntity; database rows; ui-theme DarkGray/guruTheme; koinViewModel; java.io.File; SimpleDateFormat/Locale/TimeUnit; coil.compose.AsyncImage (the real coil loading)
Exports: MediaScreen (Library Grid + search + navigate-to-detail)
Responsibility: the front door's grid reads real state from the DB only.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/ui/MediaDetailScreen.kt
Imports: compose state/runtime/nav components; coil.compose.AsyncImage; Serialization helpers; DarkGray; NavHostController; java.io.File
Exports: MediaDetailScreen (timeline + zoom + share + delete with real DB rows)
Responsibility: the strip read surface of one row, real full-res zoom, sharing.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/media/ui/MediaDetailScreenFragments.kt
Retired: content reduced to a no-op comment while the single-file detail screen lives in MediaScreen.kt + MediaDetailScreen.kt. No compiled code, no dependency on this file.
Responsibility: none (kept only as a marker of the retired path).

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/presentation/main/LobbyScreen.kt (touched)
Exports added: TintMedia and Media lobby tile (badgeCount = counts.media; navigates to Screen.MediaScreen, tile after the projects row)
Responsibility: the tile order pinned per plan PART F Phase 4.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/presentation/main/LobbyViewModel.kt (touched)
Imports added: com.unuslumen.app.guru.media.MediaLibraryRepository
Exports added: media count read in LobbyCounts; refreshMediaCount() launched every Lobby entry; LobbyViewModel(...) carries and gets the real repository; media badge count refreshed in init + every lobby re-compile
Responsibility: the media badge count reads real SQL COUNT(*) from the DAO.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/GuruApplication.kt (touched)
Imports added: mediaApplicationModule + com.unuslumen.app.guru.media.MediaAttachmentHook's Koin graph binding
Exports added:
  - media module loaded from startKoin RIGHT AFTER databaseModule (PART E order)
  - MediaAttachmentHook inject + runCatching { backfillAttachedCache() } at boot launch after ToolRegistration completes
Responsibility: application boot wiring of the media module.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/AndroidManifest.xml (touched)
Exports added: FileProvider with the ${applicationId}.fileprovider authority, xml/guru_file_paths paths
Responsibility: real shares of stored library items can open exactly right.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/res/xml/guru_file_paths.xml
Imports: (resource XML, paths only)
Exports: guru_file_paths, files-path media_library/, cache-path attached_files/, files-path media_zoom_frames/
Responsibility: real share paths pinned under the module's one subtree.

### /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/build.gradle.kts (touched)
Exports added: libs.coil.compose in dependencies for the AsyncImage surface
Responsibility: coil dependency for the media art loading.

---

Every file at its real absolute path on disk, every import traced to a real file, zero stubs and zero TODO strings in the media module's source paths, entry point (GuruApplication.onCreate startKoin chain) loads and every real result was printed from real build lines. Definition of done per contract.