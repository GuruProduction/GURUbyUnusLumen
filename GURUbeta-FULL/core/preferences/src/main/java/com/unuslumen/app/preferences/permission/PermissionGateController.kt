// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.preferences.permission

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.koin.core.annotation.Single

@Single
class PermissionGateController {

    private val _showGateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val showGateRequests = _showGateRequests.asSharedFlow()

    fun requestShow() {
        _showGateRequests.tryEmit(Unit)
    }
}