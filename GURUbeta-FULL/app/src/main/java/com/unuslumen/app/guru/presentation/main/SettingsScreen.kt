package com.unuslumen.app.guru.presentation.main

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.guru.BuildConfig
import com.unuslumen.app.guru.presentation.app_lock.AppLockManager
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.presentation.SettingsViewModel
import com.unuslumen.app.presentation.components.SettingsDropdownRow
import com.unuslumen.app.presentation.components.SettingsNavLinkRow
import com.unuslumen.app.presentation.components.SettingsPageBrush
import com.unuslumen.app.presentation.components.SettingsRowDivider
import com.unuslumen.app.presentation.components.SettingsSectionCard
import com.unuslumen.app.presentation.components.SettingsSectionTitle
import com.unuslumen.app.presentation.components.SettingsSwitchRow
import com.unuslumen.app.ui.FirstDayOfWeekSettings
import com.unuslumen.app.ui.FontSizeSettings
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.StartUpScreenSettings
import com.unuslumen.app.ui.ThemeSettings
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.getFontSizeName
import com.unuslumen.app.ui.getName
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.ui.toInt
import com.unuslumen.app.util.Constants
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Settings — rebuilt in the Portal design language: warm ink on aged cream
 * paper, gold monospace section keys, hairline gold cards. Model Connect sits
 * at the top because that is the first thing a new user must do.
 *
 * Appearance block: theme (cycled), startup screen, font, font size,
 * first day of week, block screenshots, lock app, Material You.
 * Links: Model Connect, Export/Import, About, Product — all functional.
 */
@Composable
fun SettingsScreen(
    navController: NavHostController,
    appLockManager: AppLockManager,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val versionName = remember { context.getPackageInfo().versionName ?: BuildConfig.VERSION_NAME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LocalisedSnackbarHost(snackbarHostState) },
        topBar = {
            guruAppBar(stringResource(R.string.settings))
        }
    ) { paddingValues ->
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .background(SettingsPageBrush)
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ─── Model Connect — first thing everyone needs ───
            item {
                SettingsSectionCard {
                    SettingsNavLinkRow(
                        title = stringResource(R.string.integrations),
                        icon = painterResource(R.drawable.ic_integrations),
                        onClick = { navController.navigate(Screen.IntegrationsScreen) },
                    )
                    SettingsRowDivider()
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.integrations_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ─── Appearance ───
            item {
                SettingsSectionTitle(
                    text = "appearance",
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                )
                SettingsSectionCard {
                    item_themeCycle(viewModel)
                    SettingsRowDivider()
                    item_startupScreen(viewModel)
                    SettingsRowDivider()
                    item_font(viewModel)
                    SettingsRowDivider()
                    item_fontSize(viewModel)
                    SettingsRowDivider()
                    item_firstDayOfWeek(viewModel)
                }
            }

            // ─── Privacy ───
            item {
                SettingsSectionTitle(
                    text = "privacy",
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                )
                SettingsSectionCard {
                    item_blockScreenshots(viewModel)
                    SettingsRowDivider()
                    item_lockApp(viewModel, appLockManager, snackbarHostState)
                }
            }

            // ─── Data ───
            item {
                SettingsSectionTitle(
                    text = "data",
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                )
                SettingsSectionCard {
                    SettingsNavLinkRow(
                        monoKey = "io",
                        title = stringResource(R.string.export_import),
                        onClick = { navController.navigate(Screen.ImportExportScreen) },
                    )
                }
            }

            // ─── About ───
            item {
                SettingsSectionTitle(
                    text = "about",
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                )
                SettingsSectionCard {
                    SettingsNavLinkRow(
                        monoKey = "ver",
                        title = stringResource(R.string.app_version) + "  $versionName",
                        onClick = { uriHandler.openUri(Constants.GITHUB_RELEASES_LINK) },
                    )
                    SettingsRowDivider()
                    SettingsNavLinkRow(
                        monoKey = "git",
                        title = stringResource(R.string.project_on_github),
                        onClick = { uriHandler.openUri(Constants.PROJECT_GITHUB_LINK) },
                    )
                    SettingsRowDivider()
                    SettingsNavLinkRow(
                        monoKey = "sec",
                        title = stringResource(R.string.privacy_policy),
                        onClick = { uriHandler.openUri(Constants.PRIVACY_POLICY_LINK) },
                    )
                }
            }

            // ─── Product ───
            item {
                SettingsSectionTitle(
                    text = "product",
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
                )
                SettingsSectionCard {
                    SettingsNavLinkRow(
                        monoKey = "bug",
                        title = stringResource(R.string.request_feature_report_bug),
                        onClick = { uriHandler.openUri(Constants.GITHUB_ISSUES_LINK) },
                    )
                    SettingsRowDivider()
                    SettingsNavLinkRow(
                        monoKey = "map",
                        title = stringResource(R.string.project_roadmap),
                        onClick = { uriHandler.openUri(Constants.PROJECT_ROADMAP_LINK) },
                    )
                }
            }

            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}

/** Theme picker — auto / light / dark. */
@Composable
private fun item_themeCycle(viewModel: SettingsViewModel) {
    val theme by viewModel
        .getSettings(intPreferencesKey(PrefsConstants.SETTINGS_THEME_KEY), ThemeSettings.AUTO.value)
        .collectAsStateWithLifecycle(ThemeSettings.AUTO.value)
    val label = when (theme) {
        ThemeSettings.LIGHT.value -> "light"
        ThemeSettings.DARK.value -> "dark"
        else -> "auto"
    }
    SettingsDropdownRow(
        title = stringResource(R.string.app_theme),
        selectedLabel = label,
        icon = painterResource(R.drawable.ic_paint_roller),
        options = listOf(
            stringResource(R.string.auto_theme) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.SETTINGS_THEME_KEY), ThemeSettings.AUTO.value) },
            stringResource(R.string.light_theme) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.SETTINGS_THEME_KEY), ThemeSettings.LIGHT.value) },
            stringResource(R.string.dark_theme) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.SETTINGS_THEME_KEY), ThemeSettings.DARK.value) },
        ),
    )
}

/** Startup screen picker. */
@Composable
private fun item_startupScreen(viewModel: SettingsViewModel) {
    val screen by viewModel
        .getSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.LOBBY.value)
        .collectAsStateWithLifecycle(StartUpScreenSettings.LOBBY.value)
    val screenName = when (screen) {
        StartUpScreenSettings.LOBBY.value -> stringResource(R.string.lobby)
        StartUpScreenSettings.DASHBOARD.value -> stringResource(R.string.dashboard)
        StartUpScreenSettings.NOTES.value -> stringResource(R.string.notes)
        StartUpScreenSettings.JOURNAL.value -> stringResource(R.string.journal)
        StartUpScreenSettings.BOOKMARKS.value -> stringResource(R.string.bookmarks)
        StartUpScreenSettings.CALENDAR.value -> stringResource(R.string.calendar)
        StartUpScreenSettings.PORTAL.value -> stringResource(R.string.portal)
        else -> stringResource(R.string.lobby)
    }
    SettingsDropdownRow(
        title = stringResource(R.string.start_up_screen),
        selectedLabel = screenName,
        icon = painterResource(R.drawable.ic_home),
        options = listOf(
            stringResource(R.string.lobby) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.LOBBY.value) },
            stringResource(R.string.dashboard) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.DASHBOARD.value) },
            stringResource(R.string.notes) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.NOTES.value) },
            stringResource(R.string.journal) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.JOURNAL.value) },
            stringResource(R.string.bookmarks) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.BOOKMARKS.value) },
            stringResource(R.string.calendar) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.CALENDAR.value) },
            stringResource(R.string.portal) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.DEFAULT_START_UP_SCREEN_KEY), StartUpScreenSettings.PORTAL.value) },
        ),
    )
}

/** App font picker — the full FontRegistry library. */
@Composable
private fun item_font(viewModel: SettingsViewModel) {
    val fontName by viewModel
        .getSettings(stringPreferencesKey(PrefsConstants.APP_FONT_NAME_KEY), "Rubik (default)")
        .collectAsStateWithLifecycle("Rubik (default)")
    val context = LocalContext.current
    val userFonts = remember { com.unuslumen.app.ui.theme.FontRegistry.listUserFonts(context) }
    val options = com.unuslumen.app.ui.theme.FontRegistry.labels(userFonts)
    SettingsDropdownRow(
        title = stringResource(R.string.app_font),
        selectedLabel = fontName,
        icon = painterResource(R.drawable.ic_font),
        options = options.map { label ->
            label to {
                viewModel.saveSettings(
                    stringPreferencesKey(PrefsConstants.APP_FONT_NAME_KEY),
                    label
                )
            }
        },
    )
}

/** Font size picker. */
@Composable
private fun item_fontSize(viewModel: SettingsViewModel) {
    val fontSize by viewModel
        .getSettings(intPreferencesKey(PrefsConstants.FONT_SIZE_KEY), FontSizeSettings.NORMAL.value)
        .collectAsStateWithLifecycle(FontSizeSettings.NORMAL.value)
    SettingsDropdownRow(
        title = stringResource(R.string.font_size),
        selectedLabel = fontSize.getFontSizeName(),
        icon = painterResource(R.drawable.ic_font_size),
        options = FontSizeSettings.entries.map { sizeItem ->
            stringResource(sizeItem.title) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.FONT_SIZE_KEY), sizeItem.value) }
        },
    )
}

/** First day of week picker. */
@Composable
private fun item_firstDayOfWeek(viewModel: SettingsViewModel) {
    val day by viewModel
        .getSettings(intPreferencesKey(PrefsConstants.FIRST_DAY_OF_WEEK_KEY), FirstDayOfWeekSettings.SUNDAY.value)
        .collectAsStateWithLifecycle(FirstDayOfWeekSettings.SUNDAY.value)
    SettingsDropdownRow(
        title = stringResource(R.string.first_day_of_week),
        selectedLabel = stringResource(FirstDayOfWeekSettings.fromValue(day).title),
        icon = painterResource(R.drawable.ic_calendar),
        options = FirstDayOfWeekSettings.entries.map { dayOption ->
            stringResource(dayOption.title) to { viewModel.saveSettings(intPreferencesKey(PrefsConstants.FIRST_DAY_OF_WEEK_KEY), dayOption.value) }
        },
    )
}

/** Block screenshots toggle. */
@Composable
private fun item_blockScreenshots(viewModel: SettingsViewModel) {
    val on by viewModel
        .getSettings(booleanPreferencesKey(PrefsConstants.BLOCK_SCREENSHOTS_KEY), false)
        .collectAsStateWithLifecycle(false)
    SettingsSwitchRow(
        title = stringResource(R.string.block_screenshots),
        checked = on,
        icon = painterResource(R.drawable.ic_block_screenshot),
        onCheck = { viewModel.saveSettings(booleanPreferencesKey(PrefsConstants.BLOCK_SCREENSHOTS_KEY), it) },
    )
}

/** Lock app toggle with biometric capability check. */
@Composable
private fun item_lockApp(
    viewModel: SettingsViewModel,
    appLockManager: AppLockManager,
    snackbarHostState: androidx.compose.material3.SnackbarHostState,
) {
    val on by viewModel
        .getSettings(booleanPreferencesKey(PrefsConstants.LOCK_APP_KEY), false)
        .collectAsStateWithLifecycle(false)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SettingsSwitchRow(
        title = stringResource(R.string.lock_app),
        checked = on,
        icon = painterResource(R.drawable.ic_lock),
        onCheck = { check ->
            if (appLockManager.canUseFeature()) {
                viewModel.saveSettings(booleanPreferencesKey(PrefsConstants.LOCK_APP_KEY), check)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(R.string.no_auth_method)
                }
            }
        },
    )
}

fun Context.getPackageInfo(): PackageInfo {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        packageManager.getPackageInfo(packageName, 0)
    }
}