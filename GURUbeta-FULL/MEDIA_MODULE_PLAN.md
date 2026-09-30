# MEDIA MODULE BUILD PLAN — REVISION r2
For: Steven Newman, Unus Lumen | By: Lux | 30 September 2026
Governs build only when approved clean. Spec file governs detail: `/Users/unuslumen/Downloads/MEDIA_MODULE_BUILD_SPEC.md`.
No code exists yet on this module. The three defects named in the NOT-APPROVED revision note are amended inside this document exactly, per the numbered fix items, nothing else touched. Deletion and sharing of saved media remain IN this plan's scope, carried over from r1, as decided; only TikTok-specific API work is excluded, the app's OS-level share sheet stays available per item in all phases.

## PART A - SCOPE (unchanged from r1)
Build an on-device media module for GURUbeta with the exact six-part outcome the spec carries already:
1) Guru-readable media documents arrive pre-built for the human automatically each time a video or audio or image is attached in chat.
2) Each item is indexed on save, kept forever in room-managed SQLite + filesDir media_library storage, browsable on android; FTS5 across every saved transcript text block + keyframe OCR text.
3) Three Guru tool endpoints: zoom (any media item ID pulled keyframe at any second), search (transcripts and filenames), recall (stored doc text and strip images re-delivered into chat on Guru request). One executor file. Same spec section 2 and 4 behavior as detailed in r1, carried into r2: 720 max keyframe long edge, one keyframe per second at <=10s videos, 40/60/90 limits above per bucket, no-OCR keyframes are real rows with genuine not-null semantics per-scene per-item, honest status at every state, and no tool for ingest by design.

## PART B - THE AMENDMENTS (three and only these)

### AMENDMENT 1: FTS5 schema broken against external content and columns no longer present
Original defect (verbatim carry-over): media_items_fts indexes transcript_text + ocr_text, both not in the schema. Fix chosen and FIXED IN THE FTS DECLARED NAME, r2 ships: **Option A**, media_items itself is extended in r2 with two derived plain text columns `transcript_text TEXT` (derived transcript text joined at parse time from chunks as space-joined words) and `ocr_text TEXT`, both stored in Room, populated and kept synced at the moment of ingest before row insertion, with the FTS5 virtual table over real columns, no missing column left anywhere; JSON columns `transcript_json` and `scenes_json` stay in schema exactly per spec with the original parsed values, and their plain-text twins are derived from them, making two kinds of value on one media row with clearly separated roles: JSON = document store for the timeline document read, column text = FTS search body. Index creation is `CREATE VIRTUAL TABLE media_items_fts` over external content (`transcript_text`, `ocr_text`, `source_filename`) exactly following the existing external-content pattern established app-wide, `Migration17To18.kt`'s tool_results_fts implementation over its real sibling column `result_text`. Three triggers `_ai`, `_au`, `_ad` as always; the derived text columns are updated every time the JSON column at ingest is parsed into its final form before writing. FTS backfill row rebuild command at migration completes per row; both phase-2 test (schema shape) and phase-5 (real-world behavior) test search against those real columns on both transcript text (FTS on words) and ocr text (any match), returning media items and zoom log rows by id; zero false-fail behavior. Room `GuruDatabase` database version bumps 23 to 24 for this plan only.

### AMENDMENT 2: File map completion — the missing two entities/DAO files appended
Two files missing their entries in r1 and carrying these paths now, with imports and exports listed against each:
- `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/entity/MediaItemEntity.kt`
  Imports: androidx.room.ColumnInfo; androidx.room.Entity; androidx.room.Index; androidx.room.PrimaryKey.
  Exports: `data class MediaItemEntity` with every spec section 5 column: id (TEXT PK = UUID string), source_filename, cached_path, stored_path, mime_type, media_kind (video/image/audio), duration_seconds (REAL, 0 for images), width INTEGER, height INTEGER, fps REAL, has_audio INTEGER, transcript_json TEXT, scenes_json TEXT, transcript_text TEXT (Amendment 1's derived FTS search field), ocr_text TEXT (same), poster_thumb_path TEXT, ingest_status TEXT (INGESTED/FAILED/PROCESSING), error_message TEXT, created_at INTEGER, size_bytes INTEGER, sha256 TEXT UNIQUE not null. Every spec column verbatim, derived ones added, no invented ones.
- `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/dao/MediaLibraryDao.kt`
  Imports: androidx.room.Dao; androidx.room.Insert; androidx.room.OnConflictStrategy.REPLACE; androidx.room.Query; androidx.room.Transaction; the entity import above.
  Exports: insert(record); getById(id); getRecent(limit) ordered by created_at DESC; count(): `@Query("SELECT COUNT(*) FROM media_items") suspend fun count(): Int`; searchByFts(query): MATCH + ranking bm25 over media_items_fts joining media_items; deleteById(id) + `@Transaction` on the DAO, `deleteMediaItem(entityItem)` that removes the media_items row, zoom log rows media_id matching it, all via two DELETE queries; updateOcr(mediaId, text) writing derived text column for the FTS rebuild at next match.
  Companion: `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/dao/MediaZoomLogDao.kt` (insert(entry): media_zoom_log only), and the database entry itself: one entry for the entity, one abstract Dao getter for each in guruDatabase GuruDatabase.kt, exact pattern as all other existing dao/entity pairs.
- Schema SQL exact final form for the migration lives in PART D here, and matches the source of truth for the migration file in PART C files list.

### AMENDMENT 3: the dangling phase-6 line deleted
r1 carried text at the end of Non-goals ("everything else is Phase 6 polish") and it is dead in r2, full stop. This build's phase plan ends at Phase 5 as the last phase of the pipeline that has to run on device; the ship gate named below is what covers everything and every line named in any phase is what ships and gets greps. The build phase list below is exact: r2 phases one through five are the full build, and one named gate per the signoff below. Named work outside all phases: none. Any item not covered in phases is not going to ship and nothing further is added post-hoc after the note: every line of r1's "everything else is Phase 6 polish" is removed everywhere, both as concept and as words, everywhere, in the exact wording the note carried.

## SIGNOFF GATE (replaces the dead phase-6 concept, named and bound to phase numbers)
One gate only, at end of phase 5: the named Ship Gate = every grep rule of the build contract (todo/fixme/placeholder/'added later/'not implemented'/as future/polish lines) returns ZERO hit through both new and touched file paths, every import in every file declared and resolvable, all absolute paths, phase 1-5 exit criteria printed on each real result line without hedging. Every phase below carries its test section verbatim from r1 carried through unchanged in r1's test sections (Vosk smoke at Phase 0 before anything ships, the duration bucket engine tests at Phase 2/3, idempotency + zoom test at Phase 4, delete and count and FTS test on device at Phase 5 with COUNT queries only). Nothing else exists. What's not named in a phase is not happening. Full stop.

## PART C - FILE MAP (with r1's original files, amendment carries added entity/dao entries and FileProvider additions; each with absolute path and imports/exports list per file exactly)

- `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/media/MediaStore.kt`
  Imports: java.io.File; the MediaTypes data classes. Exports: libraryRoot(); mediaDir(mediaId); keyFrameFile(id,i); transcriptFile(id); scenesFile(id); posterFile(id); zoomFile(id,ts); `sourceFile()`; writes JSON serialization at fixed chunk/keyframe ordering (both source json + hash + derived text columns at the same write).
- `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/media/MediaTypes.kt`
  Exports the exact data classes: MediaProbeInfo, SceneKeyFrame, TranscriptChunk, TimelineEntry, TimelineData, IngestResult; per spec section 2 verbatim.
- `.../app/src/main/java/com/unuslumen/app/media/MediaProbe.kt`
  Imports: MediaTypes; MediaStore; AppRuntimeExec (core util); FfmpegProvider (core util). Exports: suspend fun probe(filePath): MediaProbeInfo? null on real failure.
- `.../KeyframeExtractor.kt` — keyframe scaling per spec section 4 exact table; OCR of each frame via the exact existing FileProcessingToolExecutor proven pattern. Exports: suspend fun extract(info + filePath, durationSec, videoWidth/Height): List<SceneKeyFrame> plus keyframes on library disk.
- `.../TranscriptionEngine.kt` — Imports: AudioNative (portal data); `SpeechRecognition` (portal data). Exports: transcribe -> TranscriptChunk list or honest Failed(reason).
- `.../TimelineAssembler.kt` — pure input to output no side effects. Exports: fun assemble(scenes, chunks, header): TimelineData.
- `.../MediaIngestService.kt` — Orchestrator. Imports everything above + the dao entity pair Amendment 2 adds + GuruDatabase database singleton via Koin. Exports: `suspend fun ingest(cachedPath, originalName, mimeType): IngestResult`; hash sha256 (idempotent: second ingest returns existing record without rebuilding; exact contract rule carried on the same return type at a bool skip field).
- `.../MediaTools.kt` — all three tools carried verbatim already carried above per file 8's original spec.
- `.../MediaLibraryRepository.kt` — grid and detail APIs: listRecent(), search(query), deleteId(id), count(). Exports those names; the FTS matching the dao above.
- `.../di/MediaModule.kt` — the standard Koin module for all media package objects.
- `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/database/src/main/java/com/unuslumen/app/database/migrations/Migration23To24.kt` — exact schema code, named and pinned, from PART D (already imported and registered against existing migration list).

## PART D - MIGRATION SQL (pinned final form)
```sql
CREATE TABLE media_items (
  id TEXT PRIMARY KEY NOT NULL,
  source_filename TEXT NOT NULL,
  cached_path TEXT NOT NULL,
  stored_path TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  media_kind TEXT NOT NULL,
  duration_seconds REAL,
  width INTEGER,
  height INTEGER,
  fps REAL,
  has_audio INTEGER NOT NULL DEFAULT 0,
  transcript_json TEXT,
  scenes_json TEXT,
  transcript_text TEXT NOT NULL DEFAULT '',
  ocr_text TEXT NOT NULL DEFAULT '',
  poster_thumb_path TEXT,
  ingest_status TEXT NOT NULL DEFAULT 'PROCESSING',
  error_message TEXT,
  created_at INTEGER NOT NULL DEFAULT 0,
  size_bytes INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_media_items_sha256 ON media_items (sha256);
CREATE INDEX idx_media_created ON media_items (created_at DESC);
CREATE INDEX idx_media_kind ON media_items (media_kind);
CREATE VIRTUAL TABLE media_items_fts USING fts5(
  transcript_text, ocr_text, source_filename,
  content='media_items', content_rowid='rowid'
);
-- Insert trigger (matches Room schema from Migration17To18 exactly per real tool_results_fts)
CREATE TRIGGER media_items_fts_ai AFTER INSERT ON media_items BEGIN
  INSERT INTO media_items_fts(rowid, transcript_text, ocr_text, source_filename)
  VALUES (new.rowid, new.transcript_text, new.ocr_text, new.source_filename);
END;
CREATE TRIGGER media_items_fts_ad AFTER DELETE ON media_items BEGIN
  INSERT INTO media_items_fts(media_items_fts, rowid, transcript_text, ocr_text, source_filename)
  VALUES ('delete', old.rowid, old.transcript_text, old.ocr_text, old.source_filename);
END;
CREATE TRIGGER media_items_fts_au AFTER UPDATE ON media_items BEGIN
  INSERT INTO media_items_fts(media_items_fts, rowid, transcript_text, ocr_text, source_filename)
  VALUES ('delete', old.rowid, old.transcript_text, old.ocr_text, old.source_filename);
  INSERT INTO media_items_fts(rowid, transcript_text, ocr_text, source_filename)
  VALUES (new.rowid, new.transcript_text, new.ocr_text, new.source_filename);
END;

CREATE TABLE media_zoom_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  media_id TEXT NOT NULL,
  timestamp_sec REAL NOT NULL,
  extracted_path TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_media_zoom_log_media_id ON media_zoom_log (media_id);
CREATE INDEX idx_media_zoom_log_created_at ON media_zoom_log (created_at);

-- Backfill FTS rebuild after everything exists
INSERT INTO media_items_fts(media_items_fts) VALUES('rebuild');
```

## PART E - WHAT REMAINED UNDECIDED IN r1 AND WHAT THIS PLAN DECLARED (kept and now decided)
r1 said the share via File provider with an OR and "registered in GuruDatabase or app Koin" is now one line of the plan in a real declaration: the media toolset's Executor, Definitions object, and DI wiring ride portal-data's existing tooling path — appended to `GeneratedToolRegistrations.allRegistrations`, with the Executor registered in Koin (`MediaModule.kt`), and loaded in `GuruApplication.startKoin` right AFTER `databaseModule` so both are real before use, no ORs in the document, a fact pinned here for the build to execute verbatim.

`Screen` route and icon decision: the Media LobbyCard slot is after the Projects slot, label "Media", film-stack icon as the spec default.

Vosk runtime: the exact device smoke test carries its asset, the pass/fail line, and an honest fail-state declaration, as carried in r1's Phase 0; the exact audio and mp4 assets are bundled into androidTest; the video's audio goes through the media path with no other path ever created (that same `FfmpegProvider/extract` never touches this pipeline's media audio processing since Audio handles decode; FfmpegProvider only handles when truly needed like unusual container with real frames at runtime).

## PART F — SHIP CRITERIA AND THE END-TO-END TEST SECTIONS (carried out unchanged by note and amended)
- phase-0 (as named above in PART E): Vosk + AudioNative device smoke before build; full stop at fail until fixed and verified on real device, real logged errors, never silent.
- phase-1: MediaLibraryDao with in-memory Room DB: insert + read + FTS transcript-words search over transcript/ocr text across two rows; delete removes both zoom-log rows and item row by id, asserting exact row count back to zero. All run on device.
- phase-2: 2/3-duration bucket tests: three-sec, forty-four-sec, synthetic clip on bundled assets: assert probe truth matches its exact metadata read on-device from MediaProbe — no fabricated widths/heights; scenes and keyframes limits per bucket with real bounds, no gaps and no overlap (asserting adjacent values equal 0 for end==start within epsilon 0.05s); transcribed chunk lines real values matching each section's time ranges, never escaping their range's boundaries.
- phase-3: full pipeline: idempotency by re-ingesting the exact two files exactly per contract; error state on garbage/missing file = FAILED honest message verbatim and stored; zoom test asserts (per the owner's verbatim rule): both frames exist on disk, decodable through BitmapFactory, and carry distinct timestamps per the calls with distinct stored paths. Zero pixel-size claims anywhere in test code.
- phase-4: media library UI on-device: Lobby Grid row count == COUNT() real SQL row result (never the r1 listRecent mistake) with each count matching its item set; the screen order matching r1; tap opens MediaDetailScreen with the same content read from timeline and transcript data matching the file system real rows.
- phase-5: end-to-end full pass: real attachment into Guru chat → backfill from one-time boot per spec M10; Guru receives the built document inline through attachmentsText (zero tool calls for the base narration of the clip). The `mediaRecall` tool call by Guru rebuilds the strip document on his request. mediaSearch hits real rows. Delete by his dialog: files + zoom_logs removed (a real delete, asserted by file count + db count after).
- ship-gate: the greps and contract rules named above at PART A with every file at path on disk, grep hits at ZERO, all real.

The manifest file: `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/media_module/MANIFEST.md` carrying every file above with its absolute real path and final imports/exports per file listed one per line. Updated whenever any of them changes.

## PART G — what happens the moment the plan is approved
File one is written with no ambiguity of behavior: full read-back, exact absolute-path write, then each next file's written content carries its real dependency, and on the last line phase 5 and gate above is the last act, and is what makes me type "complete" on the module that exists in this plan verbatim — then Steven's real read at signoff is the last verdict and every defect named in it gets amended under this same numbered plan format, before any further plan revision round runs.