// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

sealed interface AssistantResult<out T> {
    data class Success<T>(val data: T) : AssistantResult<T>
    data object InvalidKey : Failure
    data object InternetError : Failure
    data object ToolCallLimitExceeded : Failure
    data class OtherError(val message: String? = null): Failure

    sealed interface Failure: AssistantResult<Nothing>
}