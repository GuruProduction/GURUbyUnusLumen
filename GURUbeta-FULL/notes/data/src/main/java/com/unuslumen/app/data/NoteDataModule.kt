package com.unuslumen.app.data

import androidx.core.net.toUri
import com.unuslumen.app.data.impl.MarkdownNoteRepositoryImpl
import com.unuslumen.app.data.impl.RoomNoteRepositoryImpl
import com.unuslumen.app.domain.di.NoteDomainModule
import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ksp.generated.module

@Module
@ComponentScan("com.unuslumen.app.data")
internal class NoteDataModule

val noteDataModule = module {
    includes(NoteDataModule().module, NoteDomainModule().module)
}

val noteRoomModule = module {
    factory<NoteRepository> {
        RoomNoteRepositoryImpl(get(), get(named("ioDispatcher")))
    }
}

fun noteMarkdownModule(rootUri: String) = module {
    factory<NoteRepository> {
        MarkdownNoteRepositoryImpl(get(), rootUri.toUri())
    }
}
