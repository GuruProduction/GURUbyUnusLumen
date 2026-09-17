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