// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.ui.R

@Composable
fun PortalResult.Failure.toUserMessage(): String {
    return when (this) {
        PortalResult.InvalidKey -> stringResource(R.string.invalid_api_key)
        PortalResult.InternetError -> stringResource(R.string.no_internet_connection)
        PortalResult.ToolCallLimitExceeded -> stringResource(R.string.tool_call_limit_exceeded)
        is PortalResult.OtherError -> message.orEmpty().ifBlank { stringResource(R.string.unexpected_error) }
    }
}