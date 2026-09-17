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