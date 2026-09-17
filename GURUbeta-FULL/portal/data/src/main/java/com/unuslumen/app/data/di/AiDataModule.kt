package com.unuslumen.app.data.di

import android.content.Context
import com.unuslumen.app.data.tools.CommunicationPlusToolExecutor
import com.unuslumen.app.data.tools.DeviceControlToolExecutor
import com.unuslumen.app.data.tools.MediaToolExecutor
import com.unuslumen.app.data.tools.NoteToolExecutor
import com.unuslumen.app.data.tools.ProductivityToolExecutor
import com.unuslumen.app.data.tools.ShellToolExecutor
import com.unuslumen.app.data.luxify.LuxifyScanner
import com.unuslumen.app.data.luxify.LuxifyRegistry
import com.unuslumen.app.data.luxify.LuxifyRepositoryImpl
import com.unuslumen.app.domain.repository.LuxifyRepository
import com.unuslumen.app.data.tools.SmartHomeToolExecutor
import com.unuslumen.app.data.tools.ThemeToolExecutor
import com.unuslumen.app.data.tools.LocationToolExecutor
import com.unuslumen.app.data.tools.PlanningToolExecutor
import com.unuslumen.app.data.tools.ApprovalToolExecutor
import com.unuslumen.app.data.tools.KeyboardToolExecutor
import com.unuslumen.app.data.tools.CameraToolExecutor
import com.unuslumen.app.data.tools.SystemToolExecutor
import com.unuslumen.app.data.tools.NotificationToolExecutor
import com.unuslumen.app.data.tools.JournalToolExecutor
import com.unuslumen.app.data.tools.BookmarkToolExecutor
import com.unuslumen.app.data.tools.JobToolExecutor
import com.unuslumen.app.data.tools.TaskToolExecutor
import com.unuslumen.app.data.tools.MemoryToolExecutor
import com.unuslumen.app.data.tools.MetaToolExecutor
import com.unuslumen.app.data.tools.EmailToolExecutor
import com.unuslumen.app.data.tools.FileSystemToolExecutor
import com.unuslumen.app.data.tools.EncryptionToolExecutor
import com.unuslumen.app.data.tools.HttpToolExecutor
import com.unuslumen.app.data.tools.DatabaseToolExecutor
import com.unuslumen.app.data.tools.HookToolExecutor
import com.unuslumen.app.data.tools.AutomationToolExecutor
import com.unuslumen.app.data.tools.WebViewBrowserToolExecutor
import com.unuslumen.app.data.tools.VoiceToolExecutor
import com.unuslumen.app.data.tools.WebServicesToolExecutor
import com.unuslumen.app.data.tools.ProjectToolExecutor
import com.unuslumen.app.data.tools.ReverseEngineeringToolExecutor
import com.unuslumen.app.data.tools.ThoughtToolExecutor
import com.unuslumen.app.data.tools.EnvironmentToolExecutor
import com.unuslumen.app.data.tools.CalendarToolExecutor
import com.unuslumen.app.data.tools.TermuxToolExecutor
import com.unuslumen.app.data.tools.AppManagementToolExecutor
import com.unuslumen.app.data.tools.SoundToolExecutor
import com.unuslumen.app.data.discovery.MdnsDiscoveryManager
import com.unuslumen.app.data.discovery.SsdpDiscoveryManager
import com.unuslumen.app.data.discovery.ArpDiscoveryManager
import com.unuslumen.app.data.discovery.WifiScanManager
import com.unuslumen.app.data.discovery.BluetoothScanManager
import com.unuslumen.app.data.tools.FileAccessGuard
import com.unuslumen.app.data.tools.registry.ToolRegistry
import com.unuslumen.app.data.tools.registry.ToolNameResolver
import com.unuslumen.app.data.tools.registry.ToolSecurityPolicy
import com.unuslumen.app.data.tools.registry.ToolArgumentRepair
import com.unuslumen.app.data.tools.registry.ToolSchemaSerializer
import com.unuslumen.app.data.tools.registry.ToolDispatcher
import com.unuslumen.app.data.tools.UtilToolExecutor
import com.unuslumen.app.data.tools.PromptToolExecutor
import com.unuslumen.app.data.tools.FileProcessingToolExecutor
import com.unuslumen.app.data.tools.LuxifyToolExecutor
import com.unuslumen.app.data.tools.LuxifyToolResultExtractor
import com.unuslumen.app.data.tools.ToolResultToolExecutor
import com.unuslumen.app.data.tools.ToolResultToolResultExtractor
import com.unuslumen.app.data.tools.ContactsToolExecutor
import com.unuslumen.app.data.tools.ScreenToolExecutor
import com.unuslumen.app.data.tools.ProcessToolExecutor
import com.unuslumen.app.data.tools.ClipboardToolExecutor
import com.unuslumen.app.data.tools.FileToolExecutor
import com.unuslumen.app.data.tools.WebToolExecutor
import com.unuslumen.app.data.tools.WebToolResultExtractor
import com.unuslumen.app.data.tools.CommunicationToolExecutor
import com.unuslumen.app.data.tools.WidgetToolExecutor
import com.unuslumen.app.data.tools.AlarmToolExecutor
import com.unuslumen.app.data.tools.SettingsToolExecutor
import com.unuslumen.app.data.tools.IntentToolExecutor
import com.unuslumen.app.data.tools.SshToolExecutor
import com.unuslumen.app.data.ProjectAgentRepositoryImpl
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.data.gurutools.DynamicToolExecutor
import com.unuslumen.app.data.gurutools.GuruToolRegistryManager
import com.unuslumen.app.data.gurutools.GuruToolRepositoryImpl
import com.unuslumen.app.data.automation.AutomationRepositoryImpl
import com.unuslumen.app.domain.repository.GuruToolRepository
import com.unuslumen.app.domain.repository.AutomationRepository
import com.unuslumen.app.data.brain.BrainService
import com.unuslumen.app.data.memory.ContextBuilder
import com.unuslumen.app.data.memory.LocalEmbeddingService
import com.unuslumen.app.data.memory.MemoryRepositoryImpl
import com.unuslumen.app.data.memory.VectorSearchEngine
import com.unuslumen.app.data.prompt.PromptRepositoryImpl
import com.unuslumen.app.data.thoughts.ThoughtCycleRepositoryImpl
import com.unuslumen.app.data.repository.UnusLumenStreamingClient
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.repository.PromptRepository
import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import com.unuslumen.app.domain.di.AiDomainModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import org.koin.ksp.generated.module

@Module
@ComponentScan("com.unuslumen.app.data")
internal class AiDataModule

// ToolRegistryHolder has been removed. All 56 tool sets are migrated to the new registry system.
// The old koog ToolRegistry with @Tool/@LLMDescription annotations has been fully replaced by the
// registry infrastructure at com.unuslumen.app.data.tools.registry.* and per-tool-set Definitions/Executor/Results files.

val aiDataModule = module {
    includes(AiDomainModule().module, AiDataModule().module)

    // NoteToolSet migrated to registry — see NoteToolExecutor
    // TaskToolSet migrated to registry — see TaskToolExecutor
    // CalendarToolSet migrated to registry — see CalendarToolExecutor
    // JournalToolSet migrated to registry — see JournalToolExecutor
    // BookmarkToolSet migrated to registry — see BookmarkToolExecutor
    // PlanningToolSet migrated to registry — see PlanningToolExecutor
    // AlarmToolSet migrated to registry — see AlarmToolExecutor
    // SettingsToolSet migrated to registry — see SettingsToolExecutor
    // MemoryToolSet migrated to registry — see MemoryToolExecutor
    // PromptToolSet migrated to registry — see PromptToolExecutor
    // MetaToolSet migrated to registry — see MetaToolExecutor
    // AutomationToolSet migrated to registry — see AutomationToolExecutor
    // HookToolSet migrated to registry — see HookToolExecutor
    // JobToolSet migrated to registry — see JobToolExecutor
    // ThoughtToolSet migrated to registry — see ThoughtToolExecutor
    // HttpToolSet migrated to registry — see HttpToolExecutor
    // SshToolSet migrated to registry — see SshToolExecutor
    // FileSystemToolSet migrated to registry — see FileSystemToolExecutor
    // FileProcessingToolSet migrated to registry — see FileProcessingToolExecutor
    // DatabaseToolSet migrated to registry — see DatabaseToolExecutor
    // ClipboardToolSet migrated to registry — see ClipboardToolExecutor
    // LocationToolSet migrated to registry — see LocationToolExecutor
    // AppManagementToolSet migrated to registry — see AppManagementToolExecutor
    // ContactsToolSet migrated to registry — see ContactsToolExecutor
    // ScreenToolSet migrated to registry — see ScreenToolExecutor
    // IntentToolSet migrated to registry — see IntentToolExecutor
    // ProcessToolSet migrated to registry — see ProcessToolExecutor
    // EncryptionToolSet migrated to registry — see EncryptionToolExecutor
    // KeyboardToolSet migrated to registry — see KeyboardToolExecutor
    // WidgetToolSet migrated to registry — see WidgetToolExecutor
    // NotificationToolSet migrated to registry — see NotificationToolExecutor
    // ThemeToolSet migrated to registry — see ThemeToolExecutor
    // ProjectToolSet migrated to registry — see ProjectToolExecutor
    // ApprovalToolSet migrated to registry — see ApprovalToolExecutor
    // ReverseEngineeringToolSet migrated to registry — see ReverseEngineeringToolExecutor
    // LuxifyToolSet migrated to registry — see LuxifyToolExecutor
    single { MdnsDiscoveryManager(get<Context>()) }
    single { SsdpDiscoveryManager(get<Context>()) }
    single { ArpDiscoveryManager(get<Context>()) }
    single { WifiScanManager(get<Context>()) }
    single { BluetoothScanManager(get<Context>()) }
    single { FileAccessGuard() }
    single { TorManager() }

    // Metadata background worker: owns the place/weather/motion refresh chain,
    // reads cadences and toggles from superadmin config at the top of every
    // loop iteration. Started by Application boot code (MetadataWorkerStarter).
    single { com.unuslumen.app.data.metadata.MetadataRefreshWorker(
        get<Context>(),
        { com.unuslumen.app.data.metadata.MetadataConfigFetcher.getCachedConfig(get<Context>()) },
        get<TorManager>()
    ) }
    factory { DynamicToolExecutor() }
    single<GuruToolRepository> { GuruToolRepositoryImpl(get(), get()) }
    single<AutomationRepository> { AutomationRepositoryImpl(get(), get()) }
    factory { GuruToolRegistryManager(get(), get()) }
    // EnvironmentToolSet migrated to registry — see EnvironmentToolExecutor
    // WebViewBrowserToolSet migrated to registry — see WebViewBrowserToolExecutor
    // ToolResultToolSet migrated to registry — see ToolResultToolExecutor
    // UtilToolSet migrated to registry — see UtilToolExecutor
    // ShellToolSet migrated to registry — see ShellToolExecutor
    // DeviceControlToolSet migrated to registry — see DeviceControlToolExecutor
    // CommunicationToolSet migrated to registry — see CommunicationToolExecutor
    // FileToolSet migrated to registry — see FileToolExecutor
    // SoundToolSet migrated to registry — see SoundToolExecutor
    // MediaToolSet migrated to registry — see MediaToolExecutor
    // SmartHomeToolSet migrated to registry — see SmartHomeToolExecutor
    // WebServicesToolSet migrated to registry — see WebServicesToolExecutor
    // ProductivityToolSet migrated to registry — see ProductivityToolExecutor
    // VoiceToolSet migrated to registry — see VoiceToolExecutor
    // CommunicationPlusToolSet migrated to registry — see CommunicationPlusToolExecutor
    // SystemToolSet migrated to registry — see SystemToolExecutor
    // EmailToolSet migrated to registry — see EmailToolExecutor
    // CameraToolSet migrated to registry — see CameraToolExecutor
    // WebToolSet migrated to registry — see WebToolExecutor
    factory { com.unuslumen.app.data.web.WebSearchService(get()) }
    factory { com.unuslumen.app.data.web.WebFetchService(get(), get<Context>()) }
    factory { LocalEmbeddingService(get<Context>()) }
    factory { VectorSearchEngine(get(), get()) }

    // BrainService must be registered before MemoryRepositoryImpl and ContextBuilder
    // because both depend on it. Koin resolves lazily but explicit ordering is clearer.
    single { BrainService(get(), get(), get(), get(), get(), get<Context>()) }

    single<MemoryRepository> { MemoryRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get()) }
    single<PromptRepository> { PromptRepositoryImpl(get(), get()) }
    single<ThoughtCycleRepository> { ThoughtCycleRepositoryImpl(get(), get(), get(), get()) }
    factory { ContextBuilder(get(), get(), get(), get()) }

    factory { com.unuslumen.app.domain.use_case.CreateProjectUseCase(get()) }
    factory { com.unuslumen.app.domain.use_case.UpdateProjectUseCase(get()) }
    factory { com.unuslumen.app.domain.use_case.DeleteProjectUseCase(get()) }
    factory { com.unuslumen.app.domain.use_case.GetAllProjectsUseCase(get()) }
    factory { com.unuslumen.app.domain.use_case.GetProjectUseCase(get()) }
    factory { com.unuslumen.app.domain.use_case.SearchProjectsUseCase(get()) }

    single { LuxifyScanner(get<Context>(), get()) }
    single { LuxifyRegistry(get()) }
    single<LuxifyRepository> { LuxifyRepositoryImpl(get(), get(), get()) }

    // Tool registry system
    single { ToolRegistry() }
    single { ToolRegistryHolder(get()) }
    single { ToolNameResolver() }
    single { ToolSecurityPolicy() }
    single { ToolArgumentRepair() }
    single { ToolSchemaSerializer() }
    single { ToolDispatcher(get(), get(), get(), get(), get<Context>()) }

    // Batch 1 executor and extractor bindings
    factory { UtilToolExecutor() }
    factory { PromptToolExecutor(get()) }
    factory { FileProcessingToolExecutor(get<Context>()) }
    factory { LuxifyToolExecutor(get()) }
    factory { ToolResultToolExecutor(get()) }
    factory { LuxifyToolResultExtractor() }
    factory { ToolResultToolResultExtractor() }
    // Batch 2 executor and extractor bindings
    factory { ContactsToolExecutor(get<Context>()) }
    factory { ScreenToolExecutor(get<Context>()) }
    factory { ProcessToolExecutor() }
    factory { ClipboardToolExecutor(get<Context>()) }
    factory { FileToolExecutor(get<Context>(), get<FileAccessGuard>()) }
    factory { WebToolExecutor(get(), get()) }
    factory { WebToolResultExtractor() }
    // Batch 3 executor bindings
    factory { CommunicationToolExecutor(get<Context>()) }
    factory { WidgetToolExecutor(get<Context>()) }
    factory { AlarmToolExecutor(get(), get(), get()) }
    factory { SettingsToolExecutor(get(), get()) }
    factory { IntentToolExecutor(get<Context>()) }
    factory { SshToolExecutor(get<Context>(), get<FileAccessGuard>()) }
    // Batch 4 executor bindings
    factory { PlanningToolExecutor(get(), get(), get(), get()) }
    factory { ApprovalToolExecutor(get()) }
    factory { KeyboardToolExecutor(get<Context>()) }
    factory { CameraToolExecutor(get<Context>()) }
    factory { SystemToolExecutor(get<Context>()) }
    factory { NotificationToolExecutor(get<Context>()) }
    // Batch 5 executor bindings
    factory { JournalToolExecutor(get(), get(), get(), get(), get(), get()) }
    factory { BookmarkToolExecutor(get(), get(), get(), get(), get(), get()) }
    factory { JobToolExecutor(get(), get<Context>()) }
    factory { TaskToolExecutor(get(), get(), get(), get(), get(), get(), get()) }
    factory { MemoryToolExecutor(get()) }
    factory { MetaToolExecutor(get()) }
    factory { EmailToolExecutor(get<Context>()) }
    // Batch 6 executor bindings
    factory { FileSystemToolExecutor(get<Context>()) }
    factory { EncryptionToolExecutor() }
    factory { HttpToolExecutor(get(), get<Context>()) }
    factory { DatabaseToolExecutor(get<Context>()) }
    factory { HookToolExecutor(get()) }
    factory { AutomationToolExecutor(get()) }
    factory { WebViewBrowserToolExecutor(get(), get<Context>()) }
    // Batch 7 executor bindings
    factory { ThoughtToolExecutor(get()) }
    factory { EnvironmentToolExecutor(get<Context>(), get(), get(), get(), get(), get()) }
    factory { CalendarToolExecutor(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { VoiceToolExecutor(get<Context>()) }
    factory { TermuxToolExecutor(get<Context>()) }
    factory { AppManagementToolExecutor(get<Context>()) }
    factory { SoundToolExecutor(get<Context>()) }
    // Batch 8 executor bindings
    factory { ShellToolExecutor(get<Context>(), get<FileAccessGuard>(), get()) }
    factory { ReverseEngineeringToolExecutor(get<Context>()) }
    // Batch 9 executor bindings
    factory { LocationToolExecutor(get<Context>()) }
    factory { CommunicationPlusToolExecutor(get<Context>()) }
    factory { WebServicesToolExecutor(get<Context>(), get()) }
    factory { DeviceControlToolExecutor(get<Context>()) }
    factory { NoteToolExecutor(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { MediaToolExecutor(get<Context>(), get()) }
    factory { SmartHomeToolExecutor(get<Context>()) }
    factory { ProductivityToolExecutor(get<Context>(), get()) }
    factory { ThemeToolExecutor(get(), get<Context>()) }
    factory { ProjectToolExecutor(get(), get(), get(), get(), get(), get(), get()) }
    factory { com.unuslumen.app.data.tools.NoteToSelfToolExecutor(get()) }
    single { com.unuslumen.app.data.notestoself.NotesToSelfEngine(get()) }
}
