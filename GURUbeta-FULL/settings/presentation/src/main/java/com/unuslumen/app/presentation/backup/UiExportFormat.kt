package com.unuslumen.app.presentation.backup

import com.unuslumen.app.domain.model.BackupFormat
import com.unuslumen.app.ui.R

enum class UiExportFormat(
    val format: BackupFormat,
    val labelRes: Int,
    val iconRes: Int,
) {
    JSON(
        format = BackupFormat.JSON,
        labelRes = R.string.export_format_json,
        iconRes = R.drawable.ic_json,
    ),
    MARKDOWN(
        format = BackupFormat.MARKDOWN,
        labelRes = R.string.export_format_markdown,
        iconRes = R.drawable.ic_markdown
    )
}