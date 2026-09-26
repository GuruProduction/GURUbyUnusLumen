package com.unuslumen.app.guru

import android.app.Activity
import android.app.Application
import com.unuslumen.app.guru.R as GuruR
import android.app.NotificationChannel
import java.io.File
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.unuslumen.app.alarm.di.AlarmModule
import com.unuslumen.app.data.bookmarksDataModule
import com.unuslumen.app.data.calendarDataModule
import com.unuslumen.app.data.di.aiDataModule
import com.unuslumen.app.data.di.settingsDataModule
import com.unuslumen.app.data.journalDataModule
import com.unuslumen.app.data.noteDataModule
import com.unuslumen.app.data.noteMarkdownModule
import com.unuslumen.app.data.noteRoomModule
import com.unuslumen.app.data.tasksDataModule
import com.unuslumen.app.database.di.databaseModule
import com.unuslumen.app.di.coroutinesModule
import com.unuslumen.app.guru.di.MainPresentationModule
import com.unuslumen.app.guru.di.platformModule
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.di.PreferencesModule
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.presentation.di.AiPresentationModule
import com.unuslumen.app.presentation.di.BookmarksPresentationModule
import com.unuslumen.app.presentation.di.CalendarPresentationModule
import com.unuslumen.app.presentation.di.JournalPresentationModule
import com.unuslumen.app.presentation.di.NotePresentationModule
import com.unuslumen.app.presentation.di.SettingsPresentationModule
import com.unuslumen.app.presentation.di.TasksPresentationModule
import com.unuslumen.app.ui.R
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.data.memory.HiveMindWorker
import com.unuslumen.app.data.heartbeat.HeartbeatScheduler
import com.unuslumen.app.data.brain.BrainScheduler
import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.data.jobs.JobScheduler
import com.unuslumen.app.data.jobs.JobCompressionWorker
import com.unuslumen.app.domain.repository.JobRepository
import com.unuslumen.app.database.dao.ToolResultDao
import com.unuslumen.app.util.Constants
import com.unuslumen.app.util.orb.GuruOrbService
import com.unuslumen.app.util.orb.OrbChatMessage
import com.unuslumen.app.util.shell.AdbClient
import com.unuslumen.app.util.shell.BusyboxProvider
import com.unuslumen.app.util.shell.PythonProvider
import com.unuslumen.app.util.shell.ToyboxProvider
import com.unuslumen.app.widget.di.WidgetModule
import com.unuslumen.app.adspace.AdSpaceManager
import com.unuslumen.app.adspace.adSpaceModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.ksp.generated.module
import kotlin.system.exitProcess

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = PrefsConstants.SETTINGS_PREFERENCES)

class GuruApplication : Application() {

    private val getPreference: GetPreferenceUseCase by inject()
    private val brainService: BrainService by inject()
    private val applicationScope: CoroutineScope by inject(named("applicationScope"))
    private val adSpaceManager: AdSpaceManager by inject()
    private val toolResultDao: ToolResultDao by inject()
    private var torStarted = false

    override fun onCreate() {
        super.onCreate()
        val app = this
        startKoin {
            allowOverride(true)
            androidContext(app)
            androidLogger()
            modules(
                platformModule,
                MainPresentationModule().module,
                AlarmModule().module,
                databaseModule,
                coroutinesModule,
                PreferencesModule().module,
                NotePresentationModule().module,
                noteDataModule,
                JournalPresentationModule().module,
                journalDataModule,
                TasksPresentationModule().module,
                tasksDataModule,
                SettingsPresentationModule().module,
                settingsDataModule,
                CalendarPresentationModule().module,
                calendarDataModule,
                BookmarksPresentationModule().module,
                bookmarksDataModule,
                WidgetModule().module,
                aiDataModule,
                AiPresentationModule().module,
                adSpaceModule
            )
            workManagerFactory()
        }
        applicationScope.launch {
            loadNotesModule()
            // Register all 388 tools into the ToolRegistry after notes module is loaded,
            // because some tool executors (e.g. PlanningToolExecutor) depend on
            // UpsertNoteUseCase which needs NoteRepository from the note module.
            val toolRegistry: com.unuslumen.app.data.tools.registry.ToolRegistry by inject()
            com.unuslumen.app.data.tools.registry.ToolRegistration.registerAll(toolRegistry, org.koin.core.context.GlobalContext.get())
            android.util.Log.d("guru", "Tool registry initialized with ${toolRegistry.getAllToolNames().size} tools")
            // HookEventBus: automatic hook dispatch. Real app events (messages,
            // conversations, tasks) now fire enabled hooks through the same
            // engine triggerHook uses, no manual firing required.
            val hookRepository: com.unuslumen.app.domain.repository.HookRepository by inject()
            com.unuslumen.app.data.hooks.HookEventBus.init(hookRepository, applicationScope)
            android.util.Log.d("guru", "HookEventBus initialised for automatic hook dispatch")
        }

        createRemindersNotificationChannel()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            e.printStackTrace()
            "```\n${e.stackTraceToString().take(500_000)}\n```".copyToClipboard()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, getString(R.string.exception_stack_trace_copied), Toast.LENGTH_LONG).show()
            }
            defaultHandler?.uncaughtException(thread, e)
            exitProcess(1)
        }

        HiveMindWorker.schedulePeriodic(this)
        BrainScheduler.schedule(this)
        HeartbeatScheduler.schedule(this, getPreference)
        brainService.initialise()

        // Reschedule all enabled jobs on app startup (belt and braces for reboots)
        applicationScope.launch {
            val jobRepository: JobRepository by inject()
            val enabledJobs = jobRepository.getEnabledJobs()
            for (job in enabledJobs) {
                try {
                    JobScheduler.scheduleJob(this@GuruApplication, job)
                } catch (e: Exception) {
                    android.util.Log.e("guru_jobs", "Failed to reschedule job '${job.name}' on startup: ${e.message}")
                }
            }
            // Register compression worker with dynamic cycle based on shortest compressionCycleMs
            val compressionJobs = enabledJobs.filter { it.compressionEnabled }
            val shortestCycle = compressionJobs.mapNotNull { it.compressionCycleMs }.minOrNull()
            if (shortestCycle != null) {
                try {
                    JobCompressionWorker.scheduleDynamic(this@GuruApplication, shortestCycle)
                } catch (e: Exception) {
                    android.util.Log.e("guru_jobs", "Failed to schedule compression worker: ${e.message}")
                }
            }
        }

        // Prune tool results on startup and every 24 hours
        applicationScope.launch {
            runCatching { toolResultDao.pruneToolResults(System.currentTimeMillis()) }
            while (true) {
                delay(24 * 60 * 60 * 1000L) // 24 hours
                runCatching { toolResultDao.pruneToolResults(System.currentTimeMillis()) }
            }
        }

        // Initialize ADB key store for persistent RSA key pair
        AdbClient.init(this)

        // Start Tor daemon for private web access.
        // Deferred until the first activity is in the foreground: Android 12+
        // forbids startForegroundService() from Application.onCreate because the
        // app is not in the foreground yet, which crashed the app on cold start
        // with ForegroundServiceStartNotAllowedException. The lifecycle callback
        // fires on the first activity's onResume, which is a legal moment to
        // start a foreground service. Covers every entry point (launcher, share
        // intents, deep links), not just MainActivity.
        val torManager: TorManager by inject()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (!torStarted) {
                    torStarted = true
                    torManager.start(this@GuruApplication)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Wire floating orb chat to AI repository
        initGuruOrbAiHandler()

        // Initialize Busybox
        initializeBusybox()

        // Initialize Python
        initializePython()

        // Initialize Toybox (custom-built toybox + standalone Unix tools)
        initializeToybox()

        // Initialize AdSpace manager — fetches canvas pack from the Unus Lumen API
        adSpaceManager.initialize()

        // Copy bundled handwriting fonts to guru_fonts/ so GURU can use them via set_theme_font
        applicationScope.launch { copyBundledFontsToGuruFonts() }

        // Metadata background worker: feeds the place/weather/motion chain behind
        // superadmin-configured cadences. Starts on the same first-activity
        // lifecycle moment as Tor, so process foreground rules are respected.
        val metadataWorker: com.unuslumen.app.data.metadata.MetadataRefreshWorker by inject()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            var metadataStarted = false
            override fun onActivityResumed(activity: Activity) {
                if (!metadataStarted) {
                    metadataStarted = true
                    metadataWorker.start()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun copyBundledFontsToGuruFonts() {
        val fontDir = File(filesDir, "guru_fonts")
        fontDir.mkdirs()
        val bundledFonts = mapOf(
            GuruR.font.original_salmon to "original_salmon",
            GuruR.font.miracle_days to "miracle_days",
            GuruR.font.hug_me_tight to "hug_me_tight",
            GuruR.font.caviar_dreams to "caviar_dreams",
            GuruR.font.made_tommy_soft to "made_tommy_soft",
        )
        bundledFonts.forEach { (resId, name) ->
            val ttfFile = File(fontDir, "$name.ttf")
            val otfFile = File(fontDir, "$name.otf")
            if (!ttfFile.exists() && !otfFile.exists()) {
                try {
                    val ext = if (name == "miracle_days" || name == "made_tommy_soft" || name == "original_salmon") "otf" else "ttf"
                    val outFile = File(fontDir, "$name.$ext")
                    resources.openRawResource(resId).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {
                    // Font copy failed, not critical — GURU can download fonts manually too
                }
            }
        }
    }

    private fun initGuruOrbAiHandler() {
        val aiRepository by inject<com.unuslumen.app.domain.repository.AiRepository>()
        val memoryRepository by inject<com.unuslumen.app.domain.memory.MemoryRepository>()
        val orbConversationId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        
        GuruOrbService.setAiHandler(object : GuruOrbService.Companion.OrbAiHandler {
            override suspend fun onUserMessage(text: String) {
                val convId = orbConversationId.value
                val userMsg = com.unuslumen.app.domain.model.AiMessage.UserMessage(
                    uuid = java.util.UUID.randomUUID().toString(),
                    content = text,
                    time = System.currentTimeMillis()
                )
                // Build message list: load existing conversation history + new user message
                val messages = if (convId != null) {
                    try {
                        val saved = memoryRepository.getMessagesByConversation(convId)
                        saved.map { msg ->
                            when (msg.role) {
                                "user" -> com.unuslumen.app.domain.model.AiMessage.UserMessage(
                                    uuid = msg.id, content = msg.content, time = msg.timestamp
                                )
                                else -> com.unuslumen.app.domain.model.AiMessage.AssistantMessage(
                                    content = msg.content, time = msg.timestamp, uuid = msg.id
                                )
                            }
                        } + userMsg
                    } catch (_: Exception) { listOf(userMsg) }
                } else { listOf(userMsg) }
                
                try {
                    aiRepository.sendMessage(messages, convId).collect { aiMsg ->
                        when (aiMsg) {
                            is com.unuslumen.app.domain.model.AiMessage.AssistantMessage -> {
                                GuruOrbService.addMessage(
                                    OrbChatMessage(content = aiMsg.content, isFromUser = false)
                                )
                            }
                            is com.unuslumen.app.domain.model.AiMessage.ToolCall -> {
                                if (aiMsg.isFailed) {
                                    GuruOrbService.addMessage(
                                        OrbChatMessage(content = "⚠️ Tool ${aiMsg.name} failed", isFromUser = false)
                                    )
                                }
                            }
                            else -> { }
                        }
                    }
                    // Save conversation ID for continuity
                    if (convId == null) {
                        try {
                            val convos = memoryRepository.getAllConversations()
                            val newest = convos.maxByOrNull { it.updatedDate }
                            if (newest != null) orbConversationId.value = newest.id
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    GuruOrbService.addMessage(
                        OrbChatMessage(content = "❌ ${e.message ?: "AI error occurred"}", isFromUser = false)
                    )
                }
            }
        })
    }

    private suspend fun loadNotesModule() {
        val isExternalNotesEnabled = getPreference(
            booleanPreferencesKey(PrefsConstants.EXTERNAL_NOTES_ENABLED),
            false
        ).first()
        val rootUri = getPreference(
            stringPreferencesKey(PrefsConstants.EXTERNAL_NOTES_FOLDER_URI),
            ""
        ).first()

        if (isExternalNotesEnabled && rootUri.isNotBlank()) {
            loadKoinModules(noteMarkdownModule(rootUri))
        } else {
            loadKoinModules(noteRoomModule)
        }
    }

    private fun createRemindersNotificationChannel() {
        val channel = NotificationChannel(
            Constants.REMINDERS_CHANNEL_ID,
            getString(R.string.reminders_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.description = getString(R.string.reminders_channel_description)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

    }

    private fun initializeBusybox() {
        applicationScope.launch {
            val busyboxProvider = BusyboxProvider(this@GuruApplication)
            busyboxProvider.initialize()
        }
    }

    private fun initializePython() {
        applicationScope.launch {
            val pythonProvider = PythonProvider(this@GuruApplication)
            pythonProvider.initialize()
        }
    }

    private fun initializeToybox() {
        applicationScope.launch {
            val toyboxProvider = ToyboxProvider(this@GuruApplication)
            toyboxProvider.initialize()
        }
    }

    private fun String.copyToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("label", this)
        clipboard.setPrimaryClip(clip)
    }
}
