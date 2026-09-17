package com.unuslumen.app.guru.di

import android.util.Log
import com.unuslumen.app.guru.dataStore
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.Logger
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val platformModule = module {
    single { androidContext().dataStore }
    single { OkHttp.create() }
    single<Logger> { AndroidHttpLogger() }

}

class AndroidHttpLogger: Logger {
    override fun log(message: String) {
        Log.i("Ktor", message)
    }
}