package com.unuslumen.app.adspace

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val adSpaceModule = module {
    single { AdPackCache(androidContext()) }
    single { AdSpaceManager(androidContext(), get()) }
}