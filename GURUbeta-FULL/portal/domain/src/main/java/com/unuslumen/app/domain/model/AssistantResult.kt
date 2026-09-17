package com.unuslumen.app.domain.model

sealed interface AssistantResult<out T> {
    data class Success<T>(val data: T) : AssistantResult<T>
    data object InvalidKey : Failure
    data object InternetError : Failure
    data object ToolCallLimitExceeded : Failure
    data class OtherError(val message: String? = null): Failure

    sealed interface Failure: AssistantResult<Nothing>
}