package com.unuslumen.app.domain.model

sealed interface PortalResult<out T> {
    data class Success<T>(val data: T) : PortalResult<T>
    data object InvalidKey : Failure
    data object InternetError : Failure
    data object ToolCallLimitExceeded : Failure
    data class OtherError(val message: String? = null): Failure

    sealed interface Failure: PortalResult<Nothing>
}
