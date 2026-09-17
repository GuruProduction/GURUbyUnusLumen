package com.unuslumen.app.guru.presentation.main

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.unuslumen.app.adspace.AdSpaceManager
import com.unuslumen.app.guru.presentation.app_lock.AppLockManager
import com.unuslumen.app.guru.presentation.app_lock.AuthScreen
import com.unuslumen.app.presentation.PortalScreen
import com.unuslumen.app.presentation.ComingSoonScreen
import com.unuslumen.app.presentation.BookmarkDetailsScreen
import com.unuslumen.app.presentation.BookmarkSearchScreen
import com.unuslumen.app.presentation.BookmarksScreen
import com.unuslumen.app.presentation.ProjectsScreen
import com.unuslumen.app.presentation.ProjectDetailScreen
import com.unuslumen.app.presentation.CalendarEventDetailsScreen
import com.unuslumen.app.presentation.CalendarScreen
import com.unuslumen.app.presentation.ConversationHistoryScreen
import com.unuslumen.app.presentation.JournalChartScreen
import com.unuslumen.app.presentation.JournalEntryDetailsScreen
import com.unuslumen.app.presentation.JournalScreen
import com.unuslumen.app.presentation.JournalSearchScreen
import com.unuslumen.app.presentation.MemoryScreen
import com.unuslumen.app.presentation.luxify.SkillsScreen
import com.unuslumen.app.presentation.NoteDetailsScreen
import com.unuslumen.app.presentation.NoteFolderDetailsScreen
import com.unuslumen.app.presentation.NotesScreen
import com.unuslumen.app.presentation.NotesSearchScreen
import com.unuslumen.app.presentation.TaskDetailScreen
import com.unuslumen.app.guru.presentation.main.SettingsScreen
import com.unuslumen.app.guru.presentation.main.LobbyScreen
import com.unuslumen.app.presentation.backup.ImportExportScreen
import com.unuslumen.app.presentation.integrations.IntegrationsScreen
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.StartUpScreenSettings
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.preferences.domain.model.GuruTheme
import com.unuslumen.app.preferences.domain.model.LayoutConfig
import com.unuslumen.app.ui.theme.guruTheme
import com.unuslumen.app.ui.theme.FontRegistry
import com.unuslumen.app.ui.toFontSizeScale
import com.unuslumen.app.ui.toStartUpScreen
import com.unuslumen.app.ui.rootRoute
import com.unuslumen.app.util.Constants
import com.unuslumen.app.util.permissions.Permission
import com.unuslumen.app.preferences.permission.PermissionGateController
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun guruApp(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel,
    appLockManager: AppLockManager
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var appUnlocked by remember { mutableStateOf(true) }
    val adSpaceManager = koinInject<AdSpaceManager>()
    val useMaterialYou by viewModel.useMaterialYou.collectAsStateWithLifecycle(false)
    val lifecycleOwner = LocalLifecycleOwner.current
    val font = viewModel.fontName.collectAsStateWithLifecycle("Rubik (default)")
    val fontSize = viewModel.fontSize.collectAsStateWithLifecycle(1)
    val guruTheme by viewModel.guruTheme.collectAsStateWithLifecycle(GuruTheme.DEFAULT)
    val layoutConfig by viewModel.layoutConfig.collectAsStateWithLifecycle(LayoutConfig.DEFAULT)
    var startDestination: Screen by remember { mutableStateOf(Screen.LobbyScreen) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (viewModel.lockApp.first()) {
                    appUnlocked = false
                    appLockManager.showAuthPrompt()
                }
                appLockManager.resultFlow.collectLatest { authResult ->
                    when (authResult) {
                        is AppLockManager.AuthResult.Error -> {
                            snackbarHostState.showSnackbar(
                                authResult.message
                            )
                        }

                        AppLockManager.AuthResult.Failed -> {
                            snackbarHostState.showSnackbar(R.string.auth_failed)
                        }

                        AppLockManager.AuthResult.NoHardware, AppLockManager.AuthResult.HardwareUnavailable -> {
                            snackbarHostState.showSnackbar(R.string.auth_no_hardware)
                        }

                        AppLockManager.AuthResult.Success -> {
                            appUnlocked = true
                        }

                        AppLockManager.AuthResult.NoneEnrolled -> {
                            // User disabled biometric authentication
                            viewModel.disableAppLock()
                            appUnlocked = true
                        }
                    }
                }
            }
        }
    }
    guruTheme(
        fontFamily = FontRegistry.currentFamily(font.value),
        fontSizeScale = fontSize.value.toFontSizeScale(),
        guruTheme = guruTheme,
        layoutConfig = layoutConfig
    ) {
        val context = LocalContext.current
        val permissionState by viewModel.permissionGateState.collectAsStateWithLifecycle()

        LaunchedEffect(Unit) {
            viewModel.refreshPermissionState(context)
        }

        val permissionGateController = koinInject<PermissionGateController>()
        LaunchedEffect(Unit) {
            permissionGateController.showGateRequests.collect {
                viewModel.showPermissionGate(context)
            }
        }

        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        val navController = rememberNavController()

        // Startup screen preference, resolved before the NavHost is built so its
        // start destination is correct from birth and never mutated mid-session.
        // LOBBY while the preference is in flight is safe: DataStore answers in
        // single-digit milliseconds and then the graph re-enters on the new key.
        val startDestination by remember(viewModel) {
            viewModel.defaultStartUpScreen.map { it.toStartUpScreen().rootRoute() }
        }.collectAsStateWithLifecycle(initialValue = Screen.LobbyScreen)

        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { LocalisedSnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            NavHost(
                startDestination = startDestination,
                navController = navController,
            ) {
                composable<Screen.Main> {
                    MainScreen(
                        startUpScreen = startDestination,
                        mainNavController = navController,
                        appLockManager = appLockManager,
                        modifier = Modifier
                            .consumeWindowInsets(WindowInsets.systemBars)
                            .padding(paddingValues)
                    )
                }
                composable<Screen.TaskDetailScreen>(
                    deepLinks =
                        listOf(
                            navDeepLink {
                                uriPattern =
                                    "${Constants.TASK_DETAILS_URI}/{${Constants.TASK_ID_ARG}}"
                            }
                        ),
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.TaskDetailScreen>()
                    TaskDetailScreen(
                        navController = navController,
                        args.taskId
                    )
                }
                composable<Screen.NotesScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    NotesScreen(navController = navController)
                }
                composable<Screen.NoteDetailsScreen>(
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern = "${Constants.NOTE_DETAILS_URI}/{${Constants.NOTE_ID_ARG}}"
                        }
                    ),
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.NoteDetailsScreen>()
                    NoteDetailsScreen(
                        navController,
                        args.noteId,
                        args.folderId
                    )
                }
                composable<Screen.NoteSearchScreen>(
                    enterTransition = { slideUpTransition() },
                    exitTransition = { slideDownTransition() },
                ) {
                    NotesSearchScreen(navController = navController)
                }
                composable<Screen.JournalScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    JournalScreen(navController = navController)
                }
                composable<Screen.JournalChartScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    JournalChartScreen()
                }
                composable<Screen.JournalSearchScreen>(
                    enterTransition = { slideUpTransition() },
                    exitTransition = { slideDownTransition() },
                ) {
                    JournalSearchScreen(navController = navController)
                }
                composable<Screen.JournalDetailScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.JournalDetailScreen>()
                    JournalEntryDetailsScreen(
                        navController = navController,
                        args.entryId
                    )
                }
                composable<Screen.BookmarksScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    BookmarksScreen(navController = navController)
                }
                composable<Screen.BookmarkDetailScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.BookmarkDetailScreen>()
                    BookmarkDetailsScreen(
                        navController = navController,
                        args.bookmarkId
                    )
                }
                composable<Screen.BookmarkSearchScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    BookmarkSearchScreen(navController = navController)
                }
                composable<Screen.ProjectsScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    ProjectsScreen(navController = navController)
                }
                composable<Screen.ProjectDetailScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.ProjectDetailScreen>()
                    ProjectDetailScreen(
                        projectId = args.projectId,
                        navController = navController
                    )
                }
                composable<Screen.CalendarScreen>(
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern = Constants.CALENDAR_SCREEN_URI
                        }
                    ),
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    CalendarScreen(navController = navController)
                }
                composable<Screen.CalendarEventDetailsScreen>(
                    deepLinks = listOf(
                        navDeepLink {
                            uriPattern =
                                "${Constants.CALENDAR_DETAILS_SCREEN_URI}?${Constants.CALENDAR_EVENT_ID_ARG}={${Constants.CALENDAR_EVENT_ID_ARG}}"
                        }
                    ),
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.CalendarEventDetailsScreen>()
                    CalendarEventDetailsScreen(
                        navController = navController,
                        eventId = args.eventId,
                        initialStartMillis = args.initialStartMillis
                    )
                }
                composable<Screen.NoteFolderDetailsScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    val args = it.toRoute<Screen.NoteFolderDetailsScreen>()
                    NoteFolderDetailsScreen(
                        navController = navController,
                        args.folderId
                    )
                }
                composable<Screen.ImportExportScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    ImportExportScreen()
                }
                composable<Screen.IntegrationsScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    IntegrationsScreen(
                        onBackToPortal = {
                            navController.navigate(Screen.PortalScreen) {
                                popUpTo(Screen.LobbyScreen)
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable<Screen.PortalScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    PortalScreen(navController = navController)
                }
                composable<Screen.LobbyScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    LobbyScreen(navController = navController)
                }
                composable<Screen.ConversationHistoryScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    ConversationHistoryScreen(navController = navController)
                }
                composable<Screen.MemoryScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    MemoryScreen(navController = navController)
                }
                composable<Screen.OtioComingSoon>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    ComingSoonScreen()
                }
                composable<Screen.SettingsScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    SettingsScreen(navController, appLockManager)
                }
                composable<Screen.SkillsScreen>(
                    enterTransition = { slideInTransition() },
                    exitTransition = { slideOutTransition() },
                ) {
                    SkillsScreen(navController = navController)
                }
            }
            if (!appUnlocked) {
                AuthScreen {
                    appLockManager.showAuthPrompt()
                }
            }
        }

            if (!permissionState.dismissed) {
                PermissionGateScreen(
                    state = permissionState,
                    onGrantAll = { viewModel.requestAllStandardPermissions(context) },
                    onTogglePermission = { permission -> viewModel.togglePermission(context, permission) },
                    onDismiss = { viewModel.dismissPermissionGate() },
                    onRefresh = { viewModel.refreshGatePermissions(context) }
                )
            }
        }
    }
}

fun slideInTransition() = slideInHorizontally(
    initialOffsetX = { it },
    animationSpec = tween(300)
)

fun slideOutTransition() = slideOutHorizontally(
    targetOffsetX = { it },
    animationSpec = tween(300)
)

fun slideUpTransition() = slideInVertically(
    initialOffsetY = { it },
    animationSpec = tween(300)
)

fun slideDownTransition() = slideOutVertically(
    targetOffsetY = { it },
    animationSpec = tween(300)
)
