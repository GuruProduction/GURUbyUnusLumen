package com.unuslumen.app.ui.snackbar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.unuslumen.app.ui.R

sealed class LocalisedSnackbarMessage(
    @param:StringRes val stringRes: Int,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val actionLabelRes: Int? = null,
    val color: @Composable () -> Color,
    val contentColor: @Composable () -> Color,
    override val duration: SnackbarDuration = if (actionLabelRes != null) SnackbarDuration.Long else SnackbarDuration.Short,
    override val withDismissAction: Boolean = false,
) : SnackbarVisuals {

    override val message: String = ""
    override val actionLabel: String? = null

    class Error(
        @StringRes stringResource: Int,
        @StringRes actionLabelRes: Int? = null,
    ) : LocalisedSnackbarMessage(
        stringRes = stringResource,
        iconRes = R.drawable.ic_info,
        actionLabelRes = actionLabelRes,
        color = { MaterialTheme.colorScheme.errorContainer },
        contentColor = { MaterialTheme.colorScheme.onErrorContainer }
    )

}

suspend fun SnackbarHostState.showSnackbar(
    @StringRes stringRes: Int,
    @StringRes actionLabelRes: Int? = null
): SnackbarResult {
    return showSnackbar(
        LocalisedSnackbarMessage.Error(
            stringResource = stringRes,
            actionLabelRes = actionLabelRes
        )
    )
}