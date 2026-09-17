package com.unuslumen.app.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.preferences.domain.model.LayoutConfig

/**
 * Compose-specific extensions for LayoutConfig.
 * Provides dp/sp values that can be used directly in Compose composables.
 */

// Default spacing values (matching the app's original design)
object DefaultSpacing {
    val ExtraSmall = 2.dp
    val Small = 4.dp
    val Medium = 8.dp
    val Large = 16.dp
    val ExtraLarge = 24.dp
    val Huge = 32.dp
}

object DefaultCardStyle {
    val Elevation = 2.dp
    val CornerRadius = 12.dp
    val Padding = 16.dp
    val BorderWidth = 0.dp
}

object DefaultListItemStyle {
    val Padding = 12.dp
    val Spacing = 8.dp
}

object DefaultScreenPadding {
    val Horizontal = 16.dp
    val Vertical = 8.dp
}

object DefaultSectionStyle {
    val Spacing = 24.dp
    val HeaderPadding = 16.dp
}

/**
 * Resolves a LayoutConfig property to a dp value, falling back to the default.
 * Usage: val spacing = layoutConfig.spacingMedium.dpOrDefault
 */
val LayoutConfig?.spacingExtraSmallDp get() = this?.spacingExtraSmall?.dp ?: DefaultSpacing.ExtraSmall
val LayoutConfig?.spacingSmallDp get() = this?.spacingSmall?.dp ?: DefaultSpacing.Small
val LayoutConfig?.spacingMediumDp get() = this?.spacingMedium?.dp ?: DefaultSpacing.Medium
val LayoutConfig?.spacingLargeDp get() = this?.spacingLarge?.dp ?: DefaultSpacing.Large
val LayoutConfig?.spacingExtraLargeDp get() = this?.spacingExtraLarge?.dp ?: DefaultSpacing.ExtraLarge
val LayoutConfig?.spacingHugeDp get() = this?.spacingHuge?.dp ?: DefaultSpacing.Huge

val LayoutConfig?.cardElevationDp get() = this?.cardElevation?.dp ?: DefaultCardStyle.Elevation
val LayoutConfig?.cardCornerRadiusDp get() = this?.cardCornerRadius?.dp ?: DefaultCardStyle.CornerRadius
val LayoutConfig?.cardPaddingDp get() = this?.cardPadding?.dp ?: DefaultCardStyle.Padding
val LayoutConfig?.cardBorderWidthDp get() = this?.cardBorderWidth?.dp ?: DefaultCardStyle.BorderWidth

val LayoutConfig?.listItemPaddingDp get() = this?.listItemPadding?.dp ?: DefaultListItemStyle.Padding
val LayoutConfig?.listItemSpacingDp get() = this?.listItemSpacing?.dp ?: DefaultListItemStyle.Spacing

val LayoutConfig?.screenPaddingHorizontalDp get() = this?.screenPaddingHorizontal?.dp ?: DefaultScreenPadding.Horizontal
val LayoutConfig?.screenPaddingVerticalDp get() = this?.screenPaddingVertical?.dp ?: DefaultScreenPadding.Vertical

val LayoutConfig?.sectionSpacingDp get() = this?.sectionSpacing?.dp ?: DefaultSectionStyle.Spacing
val LayoutConfig?.sectionHeaderPaddingDp get() = this?.sectionHeaderPadding?.dp ?: DefaultSectionStyle.HeaderPadding

val LayoutConfig?.maxContentWidthDp get() = this?.maxContentWidth?.dp

/**
 * Check if this LayoutConfig has any custom values (differs from DEFAULT).
 */
val LayoutConfig?.isCustom get() = this != null && this != LayoutConfig.DEFAULT