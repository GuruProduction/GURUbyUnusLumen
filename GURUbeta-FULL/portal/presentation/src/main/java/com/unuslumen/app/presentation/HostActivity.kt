package com.unuslumen.app.presentation

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * Unwrap the context chain to the owning Activity. Used to scope the streaming
 * chat ViewModels to the Activity lifetime instead of the navigation back
 * stack entry, so a back press can never cancel an in-flight LLM stream.
 */
tailrec fun Context.findHostActivity(): ComponentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is ComponentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}