package com.unuslumen.app.data

import com.unuslumen.app.domain.di.BookmarksDomainModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.dsl.module
import org.koin.ksp.generated.module

@Module
@ComponentScan("com.unuslumen.app.data")
internal class BookmarksDataModule

val bookmarksDataModule = module {
    includes(BookmarksDataModule().module, BookmarksDomainModule().module)
}