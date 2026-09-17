package com.unuslumen.app.data.tools.registry

import com.unuslumen.app.data.tools.UtilToolDefinitions
import com.unuslumen.app.data.tools.PromptToolDefinitions
import com.unuslumen.app.data.tools.FileProcessingToolDefinitions
import com.unuslumen.app.data.tools.LuxifyToolDefinitions
import com.unuslumen.app.data.tools.ToolResultToolDefinitions
import com.unuslumen.app.data.tools.ContactsToolDefinitions
import com.unuslumen.app.data.tools.ScreenToolDefinitions
import com.unuslumen.app.data.tools.ProcessToolDefinitions
import com.unuslumen.app.data.tools.ClipboardToolDefinitions
import com.unuslumen.app.data.tools.FileToolDefinitions
import com.unuslumen.app.data.tools.WebToolDefinitions
import com.unuslumen.app.data.tools.CommunicationToolDefinitions
import com.unuslumen.app.data.tools.WidgetToolDefinitions
import com.unuslumen.app.data.tools.AlarmToolDefinitions
import com.unuslumen.app.data.tools.SettingsToolDefinitions
import com.unuslumen.app.data.tools.IntentToolDefinitions
import com.unuslumen.app.data.tools.SshToolDefinitions
import com.unuslumen.app.data.tools.PlanningToolDefinitions
import com.unuslumen.app.data.tools.ApprovalToolDefinitions
import com.unuslumen.app.data.tools.KeyboardToolDefinitions
import com.unuslumen.app.data.tools.CameraToolDefinitions
import com.unuslumen.app.data.tools.SystemToolDefinitions
import com.unuslumen.app.data.tools.NotificationToolDefinitions
import com.unuslumen.app.data.tools.JournalToolDefinitions
import com.unuslumen.app.data.tools.BookmarkToolDefinitions
import com.unuslumen.app.data.tools.JobToolDefinitions
import com.unuslumen.app.data.tools.NoteToSelfToolDefinitions
import com.unuslumen.app.data.tools.TaskToolDefinitions
import com.unuslumen.app.data.tools.MemoryToolDefinitions
import com.unuslumen.app.data.tools.MetaToolDefinitions
import com.unuslumen.app.data.tools.EmailToolDefinitions
import com.unuslumen.app.data.tools.FileSystemToolDefinitions
import com.unuslumen.app.data.tools.EncryptionToolDefinitions
import com.unuslumen.app.data.tools.HttpToolDefinitions
import com.unuslumen.app.data.tools.DatabaseToolDefinitions
import com.unuslumen.app.data.tools.HookToolDefinitions
import com.unuslumen.app.data.tools.AutomationToolDefinitions
import com.unuslumen.app.data.tools.WebViewBrowserToolDefinitions
import com.unuslumen.app.data.tools.ThoughtToolDefinitions
import com.unuslumen.app.data.tools.EnvironmentToolDefinitions
import com.unuslumen.app.data.tools.CalendarToolDefinitions
import com.unuslumen.app.data.tools.VoiceToolDefinitions
import com.unuslumen.app.data.tools.TermuxToolDefinitions
import com.unuslumen.app.data.tools.AppManagementToolDefinitions
import com.unuslumen.app.data.tools.SoundToolDefinitions
import com.unuslumen.app.data.tools.ShellToolDefinitions
import com.unuslumen.app.data.tools.ReverseEngineeringToolDefinitions
import com.unuslumen.app.data.tools.LocationToolDefinitions
import com.unuslumen.app.data.tools.CommunicationPlusToolDefinitions
import com.unuslumen.app.data.tools.WebServicesToolDefinitions
import com.unuslumen.app.data.tools.DeviceControlToolDefinitions
import com.unuslumen.app.data.tools.NoteToolDefinitions
import com.unuslumen.app.data.tools.MediaToolDefinitions
import com.unuslumen.app.data.tools.SmartHomeToolDefinitions
import com.unuslumen.app.data.tools.ProductivityToolDefinitions
import com.unuslumen.app.data.tools.ThemeToolDefinitions
import com.unuslumen.app.data.tools.ProjectToolDefinitions
import org.koin.core.Koin
import kotlin.reflect.KClass

/**
 * ToolSetRegistration — Interface for tool set self-registration.
 *
 * Each tool set's definitions object implements this. KSP scans for all
 * implementations at compile time and generates GeneratedToolRegistrations
 * which lists them all. The registration system iterates that generated list
 * at startup, gets the executor and extractor from Koin, and registers
 * each tool in the registry.
 *
 * No tool set is manually listed anywhere by a human. A new tool set added
 * to the codebase is discovered automatically by KSP.
 */
interface ToolSetRegistration {
    val definitions: List<ToolDefinition>
    fun executorClass(): KClass<out ToolExecutor>
    fun extractorClass(): KClass<out ToolResultExtractor>?
}

/**
 * ToolRegistration — Registers all discovered tool sets into the registry.
 *
 * Called once at app startup. Iterates the KSP-generated list of
 * ToolSetRegistration implementations, gets the executor and extractor
 * from Koin, and registers each tool definition.
 */
object ToolRegistration {

    fun registerAll(registry: ToolRegistry, koin: Koin) {
        for (reg in GeneratedToolRegistrations.allRegistrations) {
            val executor: ToolExecutor = koin.get(reg.executorClass())
            val extractor: ToolResultExtractor? = reg.extractorClass()?.let { koin.get(it) }
            for (def in reg.definitions) {
                registry.register(def, executor, extractor)
            }
        }
    }
}

/**
 * GeneratedToolRegistrations — Auto-generated by KSP.
 *
 * Lists every object that implements ToolSetRegistration. This file is
 * generated at compile time so it is immune to R8/ProGuard stripping.
 *
 * Until KSP is set up, this is a manually maintained placeholder that
 * gets populated as each tool set is migrated in Phase 2 batches.
 * Each batch adds its tool set definitions object to this list.
 * When KSP is configured, this manual file is replaced by the generated one.
 */
object GeneratedToolRegistrations {
    val allRegistrations: List<ToolSetRegistration> = listOf(
        UtilToolDefinitions,
        PromptToolDefinitions,
        FileProcessingToolDefinitions,
        LuxifyToolDefinitions,
        ToolResultToolDefinitions,
        ContactsToolDefinitions,
        ScreenToolDefinitions,
        ProcessToolDefinitions,
        ClipboardToolDefinitions,
        FileToolDefinitions,
        WebToolDefinitions,
        CommunicationToolDefinitions,
        WidgetToolDefinitions,
        AlarmToolDefinitions,
        SettingsToolDefinitions,
        IntentToolDefinitions,
        SshToolDefinitions,
        PlanningToolDefinitions,
        ApprovalToolDefinitions,
        KeyboardToolDefinitions,
        CameraToolDefinitions,
        SystemToolDefinitions,
        NotificationToolDefinitions,
        JournalToolDefinitions,
        BookmarkToolDefinitions,
        JobToolDefinitions,
        TaskToolDefinitions,
        MemoryToolDefinitions,
        MetaToolDefinitions,
        EmailToolDefinitions,
        FileSystemToolDefinitions,
        EncryptionToolDefinitions,
        HttpToolDefinitions,
        DatabaseToolDefinitions,
        HookToolDefinitions,
        AutomationToolDefinitions,
        WebViewBrowserToolDefinitions,
        ThoughtToolDefinitions,
        EnvironmentToolDefinitions,
        CalendarToolDefinitions,
        VoiceToolDefinitions,
        TermuxToolDefinitions,
        AppManagementToolDefinitions,
        SoundToolDefinitions,
        ShellToolDefinitions,
        ReverseEngineeringToolDefinitions,
        LocationToolDefinitions,
        CommunicationPlusToolDefinitions,
        WebServicesToolDefinitions,
        DeviceControlToolDefinitions,
        NoteToolDefinitions,
        MediaToolDefinitions,
        SmartHomeToolDefinitions,
        ProductivityToolDefinitions,
        ThemeToolDefinitions,
        ProjectToolDefinitions,
        NoteToSelfToolDefinitions
    )
}