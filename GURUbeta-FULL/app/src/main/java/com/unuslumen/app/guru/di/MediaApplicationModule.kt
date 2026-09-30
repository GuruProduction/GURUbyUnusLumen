package com.unuslumen.app.guru.di

import com.unuslumen.app.guru.media.MediaAttachmentHook
import com.unuslumen.app.database.dao.MediaLibraryDao
import com.unuslumen.app.database.dao.MediaZoomLogDao
import com.unuslumen.app.guru.media.MediaDeliveryTextProvider
import com.unuslumen.app.guru.media.MediaLibraryRepository
import com.unuslumen.app.domain.media.MediaDeliveryPort
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * MediaApplicationModule — Koin wiring for the on-device media library:
 *
 * - the attachment hook (fires on every media attachment),
 * - the repository (tools and Compose UI both read through it),
 * - the MediaDeliveryPort delivery doc provider — the real
 *   implementation that carries library strip documents into
 *   `attachmentsText`.
 *
 * Everything from the real DAO singletons databaseModule provides.
 * Loaded by GuruApplication.startKoin right after databaseModule, pinned
 * in MEDIA_MODULE_PLAN.md PART E.
 */
val mediaApplicationModule = module {
    single {
        MediaAttachmentHook(
            context = androidContext(),
            mediaLibraryDao = get<MediaLibraryDao>(),
            zoomLogDao = get<MediaZoomLogDao>()
        )
    }
    single {
        MediaLibraryRepository(get<MediaLibraryDao>(), get<MediaZoomLogDao>())
    }
    single<MediaDeliveryPort> {
        get<MediaAttachmentHook>().deliveryTextProvider
    }
    // The three library tools' real executor: the Koin graph carries it, and the
    // ToolRegistration's media row resolved executorClass by Class.forName against
    // this real class. Koin binding by fully-qualified class reference happens
    // exactly here (app source), so the portal-data registration list resolves.
    factory {
        com.unuslumen.app.guru.media.MediaLibraryToolsExecutor(
            context = androidContext(),
            mediaLibraryDao = get<MediaLibraryDao>(),
            mediaZoomLogDao = get<MediaZoomLogDao>()
        )
    }
}