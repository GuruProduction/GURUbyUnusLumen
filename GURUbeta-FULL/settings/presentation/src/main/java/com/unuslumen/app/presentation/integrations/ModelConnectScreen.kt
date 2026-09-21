package com.unuslumen.app.presentation.integrations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.model.fixedBaseUrl
import com.unuslumen.app.presentation.components.SettingsGold
import com.unuslumen.app.presentation.components.SettingsInk
import com.unuslumen.app.presentation.components.SettingsPageBrush
import com.unuslumen.app.presentation.components.SettingsPrimaryButton
import com.unuslumen.app.presentation.components.SettingsRowDivider
import com.unuslumen.app.presentation.components.SettingsSectionCard
import com.unuslumen.app.presentation.components.SettingsSwitchRow
import com.unuslumen.app.presentation.components.SettingsTextField
import com.unuslumen.app.ui.R

/**
 * Model Connect — where Guru gets wired to an AI model. Portal design
 * language: warm ink on aged cream paper, gold monospace labels, hairline
 * gold card borders. Replaces the old Material-default "Integrations" card.
 *
 * Sections: provider connect (with Save confirmation + auto-return),
 * engine settings (device defaults for BYO endpoints), tools + ADB, orb.
 */
@Composable
fun ModelConnectScreen(
    getAiProvider: () -> kotlinx.coroutines.flow.Flow<AiProvider>,
    getStringSetting: (PrefsKey<String>, String) -> kotlinx.coroutines.flow.Flow<String>,
    getBooleanSetting: (PrefsKey<Boolean>, Boolean) -> kotlinx.coroutines.flow.Flow<Boolean>,
    adbPairingState: AdbPairingState = AdbPairingState.Idle,
    adbPaired: Boolean = false,
    onEvent: (IntegrationsEvent) -> Unit,
    onPairAdb: (String, Int?) -> Unit = { _, _ -> },
    onAutoPairAdb: () -> Unit = {},
    onCheckAdbStatus: () -> Unit = {},
    onOpenPortal: () -> Unit = {},
) {
    val provider by getAiProvider().collectAsStateWithLifecycle(AiProvider.None)
    val aiEnabled = provider != AiProvider.None
    val aiToolsEnabled by getBooleanSetting(
        PrefsKey.BooleanKey(PrefsConstants.AI_TOOLS_ENABLED_KEY),
        false
    ).collectAsStateWithLifecycle(false)
    val byoBaseUrl by getStringSetting(
        PrefsKey.StringKey(PrefsConstants.BYO_BASE_URL_KEY),
        ""
    ).collectAsStateWithLifecycle("")
    val byoApiKey by getStringSetting(
        PrefsKey.StringKey(PrefsConstants.BYO_API_KEY_KEY),
        ""
    ).collectAsStateWithLifecycle("")
    val byoModelName by getStringSetting(
        PrefsKey.StringKey(PrefsConstants.BYO_MODEL_NAME_KEY),
        ""
    ).collectAsStateWithLifecycle("")

    Column(
        Modifier
            .fillMaxWidth()
            .background(SettingsPageBrush)
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // ─── Model connect ───
        SettingsSectionCard {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "model",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                        ),
                        color = SettingsGold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.model_connect),
                        style = MaterialTheme.typography.titleMedium,
                        color = SettingsInk,
                    )
                }
                Text(
                    text = stringResource(R.string.model_connect_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsInk.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(12.dp))

                ModelConnectBody(
                    provider = provider,
                    byoBaseUrl = byoBaseUrl,
                    byoApiKey = byoApiKey,
                    byoModelName = byoModelName,
                    onEvent = onEvent,
                    onSaved = onOpenPortal,
                )
            }
        }

        if (aiEnabled) {
            Spacer(Modifier.height(12.dp))

            // ─── Engine settings (BYO wire options) ───
            EngineSettingsSection(
                byoProvider = provider == AiProvider.Ollama || provider == AiProvider.OpenAICompat,
                getStringSetting = getStringSetting,
                onEvent = onEvent,
            )
            Spacer(Modifier.height(12.dp))

            // ─── Tools ───
            SettingsSectionCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.enable_ai_tools),
                    caption = stringResource(R.string.enable_ai_tools_description),
                    checked = aiToolsEnabled,
                    onCheck = { onEvent(IntegrationsEvent.ToggleAiTools(it)) },
                )
                AnimatedVisibility(aiToolsEnabled) {
                    Column {
                        SettingsRowDivider()
                        AdbPairingSection(
                            pairingState = adbPairingState,
                            adbPaired = adbPaired,
                            onPair = { code, port -> onPairAdb(code, port) },
                            onAutoPair = onAutoPairAdb,
                            onCheckStatus = { onCheckAdbStatus() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // ─── Floating orb ───
            if (aiToolsEnabled) {
                SettingsSectionCard {
                    FloatingOrbSection(onEvent = onEvent)
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

/** The provider dropdown + credential fields + save, on warm paper. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelConnectBody(
    provider: AiProvider,
    byoBaseUrl: String,
    byoApiKey: String,
    byoModelName: String,
    onEvent: (IntegrationsEvent) -> Unit,
    onSaved: () -> Unit,
) {
    var localProvider by remember(provider) { mutableStateOf(provider) }
    var localUrl by remember(byoBaseUrl) { mutableStateOf(byoBaseUrl) }
    var localKey by remember(byoApiKey) { mutableStateOf(byoApiKey) }
    var localModel by remember(byoModelName) { mutableStateOf(byoModelName) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Column {
        // Provider picker: exposed dropdown, NOT a text field with a tap hack.
        // The old code wrapped an editable OutlinedTextField in clickable{} — the
        // field stole focus, raised the IME and the menu never opened (the bug
        // where the keyboard appeared and the dropdown didn't). readOnly + menu
        // anchor means the whole field is a menu trigger: tap opens the list,
        // no keyboard, matching AiProviderSection's working pattern.
        ExposedDropdownMenuBox(
            expanded = dropdownExpanded,
            onExpandedChange = { dropdownExpanded = it },
        ) {
            OutlinedTextField(
                value = providerLabel(localProvider),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.model_provider)) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = SettingsGold,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SettingsGold.copy(alpha = 0.8f),
                    unfocusedBorderColor = SettingsGold.copy(alpha = 0.3f),
                    focusedTextColor = SettingsInk,
                    unfocusedTextColor = SettingsInk,
                    cursorColor = SettingsGold,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_unuslumen) + " - " + stringResource(R.string.provider_unuslumen_coming_soon)) },
                    enabled = false,
                    onClick = { },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_openai)) },
                    onClick = { localProvider = AiProvider.OpenAI; dropdownExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_anthropic)) },
                    onClick = { localProvider = AiProvider.Anthropic; dropdownExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_google)) },
                    onClick = { localProvider = AiProvider.Google; dropdownExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_xai)) },
                    onClick = { localProvider = AiProvider.XAI; dropdownExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_ollama)) },
                    onClick = { localProvider = AiProvider.Ollama; dropdownExpanded = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.provider_openai_compat)) },
                    onClick = { localProvider = AiProvider.OpenAICompat; dropdownExpanded = false },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        when (localProvider) {
            AiProvider.UnusLumen -> {
                Text(
                    text = stringResource(R.string.provider_unuslumen_blurb),
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsInk.copy(alpha = 0.6f),
                )
            }

            AiProvider.OpenAI, AiProvider.Anthropic, AiProvider.Google, AiProvider.XAI -> {
                SettingsTextField(
                    value = localKey,
                    onValueChange = { localKey = it },
                    label = stringResource(R.string.byo_api_key_label),
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = localModel,
                    onValueChange = { localModel = it },
                    label = stringResource(R.string.byo_model_name_label),
                    placeholder = modelPlaceholder(localProvider),
                )
                val fixedUrl = localProvider.fixedBaseUrl()
                if (fixedUrl != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = fixedUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = SettingsInk.copy(alpha = 0.5f),
                    )
                }
            }

            AiProvider.Ollama, AiProvider.OpenAICompat -> {
                SettingsTextField(
                    value = localUrl,
                    onValueChange = { localUrl = it },
                    label = stringResource(R.string.byo_base_url_label),
                    placeholder = if (localProvider == AiProvider.Ollama) "http://<pc-ip>:11434" else "http://<pc-ip>:1234",
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = localKey,
                    onValueChange = { localKey = it },
                    label = stringResource(R.string.byo_api_key_label_optional),
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = localModel,
                    onValueChange = { localModel = it },
                    label = stringResource(R.string.byo_model_name_label),
                    placeholder = modelPlaceholder(localProvider),
                )
            }

            else -> {}
        }

        if (localProvider != AiProvider.UnusLumen && localProvider != AiProvider.None) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.api_key_stays_on_device),
                style = MaterialTheme.typography.bodySmall,
                color = SettingsInk.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            SettingsPrimaryButton(
                text = stringResource(R.string.byo_save).uppercase(),
                onClick = {
                    onEvent(IntegrationsEvent.SelectProvider(localProvider))
                    onEvent(IntegrationsEvent.UpdateCustomURL(localProvider, localUrl))
                    onEvent(IntegrationsEvent.UpdateApiKey(localProvider, localKey))
                    onEvent(IntegrationsEvent.UpdateModel(localProvider, localModel))
                    onSaved()
                },
            )
        }
    }
}

/**
 * Engine settings: the eight device defaults BYO endpoints run on. One card,
 * eight fields, one Save. Saved to prefs as strings; the AI repository parses
 * them per send and falls back to the LlmConfig defaults on anything blank.
 */
@Composable
private fun EngineSettingsSection(
    byoProvider: Boolean,
    getStringSetting: (PrefsKey<String>, String) -> kotlinx.coroutines.flow.Flow<String>,
    onEvent: (IntegrationsEvent) -> Unit,
) {
    SettingsSectionCard {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                text = "engine",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                ),
                color = SettingsGold,
            )
            Text(
                text = stringResource(R.string.byo_wire_options),
                style = MaterialTheme.typography.titleMedium,
                color = SettingsInk,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(R.string.byo_wire_options_description),
                style = MaterialTheme.typography.bodySmall,
                color = SettingsInk.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (byoProvider) {
                EngineSettingsFields(getStringSetting = getStringSetting, onEvent = onEvent)
            } else {
                Text(
                    text = "Applies when you connect your own endpoint (Ollama or OpenAI-compatible). Cloud brands are tuned by their own services.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SettingsInk.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/** The eight wire-option fields with one Save that commits them all. */
@Composable
private fun EngineSettingsFields(
    getStringSetting: (PrefsKey<String>, String) -> kotlinx.coroutines.flow.Flow<String>,
    onEvent: (IntegrationsEvent) -> Unit,
) {
    // Straight-line state: compose contract kept honest, no remember-in-loop.
    val temperatureSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_TEMPERATURE_KEY), ""
    ).collectAsStateWithLifecycle("")
    val maxTokensSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_MAX_TOKENS_KEY), ""
    ).collectAsStateWithLifecycle("")
    val contextWindowSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_CONTEXT_WINDOW_KEY), ""
    ).collectAsStateWithLifecycle("")
    val thinkingLevelSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_THINKING_LEVEL_KEY), ""
    ).collectAsStateWithLifecycle("")
    val topPSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_TOP_P_KEY), ""
    ).collectAsStateWithLifecycle("")
    val topKSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_TOP_K_KEY), ""
    ).collectAsStateWithLifecycle("")
    val repeatPenaltySaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_REPEAT_PENALTY_KEY), ""
    ).collectAsStateWithLifecycle("")
    val seedSaved by getStringSetting(
        stringPreferencesKey(PrefsConstants.BYO_SEED_KEY), ""
    ).collectAsStateWithLifecycle("")

    var temperature by remember(temperatureSaved) { mutableStateOf(temperatureSaved) }
    var maxTokens by remember(maxTokensSaved) { mutableStateOf(maxTokensSaved) }
    var contextWindow by remember(contextWindowSaved) { mutableStateOf(contextWindowSaved) }
    var thinkingLevel by remember(thinkingLevelSaved) { mutableStateOf(thinkingLevelSaved) }
    var topP by remember(topPSaved) { mutableStateOf(topPSaved) }
    var topK by remember(topKSaved) { mutableStateOf(topKSaved) }
    var repeatPenalty by remember(repeatPenaltySaved) { mutableStateOf(repeatPenaltySaved) }
    var seed by remember(seedSaved) { mutableStateOf(seedSaved) }

    Column {
        SettingsTextField(
            value = temperature,
            onValueChange = { temperature = it },
            label = stringResource(R.string.byo_temperature_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it },
            label = stringResource(R.string.byo_max_tokens_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = contextWindow,
            onValueChange = { contextWindow = it },
            label = stringResource(R.string.byo_context_window_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = thinkingLevel,
            onValueChange = { thinkingLevel = it },
            label = stringResource(R.string.byo_thinking_level_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = topP,
            onValueChange = { topP = it },
            label = stringResource(R.string.byo_top_p_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = topK,
            onValueChange = { topK = it },
            label = stringResource(R.string.byo_top_k_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = repeatPenalty,
            onValueChange = { repeatPenalty = it },
            label = stringResource(R.string.byo_repeat_penalty_label),
        )
        Spacer(Modifier.height(8.dp))
        SettingsTextField(
            value = seed,
            onValueChange = { seed = it },
            label = stringResource(R.string.byo_seed_label),
        )
        Spacer(Modifier.height(12.dp))
        SettingsPrimaryButton(
            text = stringResource(R.string.byo_save).uppercase(),
            onClick = {
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_TEMPERATURE_KEY), temperature.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_MAX_TOKENS_KEY), maxTokens.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_CONTEXT_WINDOW_KEY), contextWindow.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_THINKING_LEVEL_KEY), thinkingLevel.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_TOP_P_KEY), topP.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_TOP_K_KEY), topK.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_REPEAT_PENALTY_KEY), repeatPenalty.trim()))
                onEvent(IntegrationsEvent.SavePreference(stringPreferencesKey(PrefsConstants.BYO_SEED_KEY), seed.trim()))
            },
        )
    }
}

private fun providerLabel(provider: AiProvider): String = when (provider) {
    AiProvider.UnusLumen -> "Unus Lumen"
    AiProvider.OpenAI -> "OpenAI"
    AiProvider.Anthropic -> "Anthropic"
    AiProvider.Google -> "Google Gemini"
    AiProvider.XAI -> "xAI Grok"
    AiProvider.Ollama -> "Ollama"
    AiProvider.OpenAICompat -> "OpenAI-compatible"
    AiProvider.None -> "No provider selected"
}

private fun modelPlaceholder(provider: AiProvider): String = when (provider) {
    AiProvider.OpenAI -> "gpt-4o-mini"
    AiProvider.Anthropic -> "claude-sonnet-4-5"
    AiProvider.Google -> "gemini-2.5-flash"
    AiProvider.XAI -> "grok-4"
    AiProvider.Ollama -> "llama3.1:8b"
    else -> "model-id"
}