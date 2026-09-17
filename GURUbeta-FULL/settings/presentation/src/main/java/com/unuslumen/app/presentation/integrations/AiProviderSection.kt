package com.unuslumen.app.presentation.integrations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.fixedBaseUrl
import com.unuslumen.app.presentation.components.ExperimentalBadge
import com.unuslumen.app.ui.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun AiProviderSection(
    getAiProvider: () -> Flow<AiProvider>,
    getStringSetting: (PrefsKey<String>, String) -> Flow<String>,
    getBooleanSetting: (PrefsKey<Boolean>, Boolean) -> Flow<Boolean>,
    adbPairingState: AdbPairingState = AdbPairingState.Idle,
    adbPaired: Boolean = false,
    onEvent: (IntegrationsEvent) -> Unit,
    onPairAdb: (String, Int?) -> Unit = { _, _ -> },
    onAutoPairAdb: () -> Unit = {},
    onCheckAdbStatus: () -> Unit = {},
    modifier: Modifier = Modifier
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(25.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.ai),
                    style = MaterialTheme.typography.titleLarge
                )
                Switch(
                    checked = aiEnabled,
                    onCheckedChange = {
                        onEvent(IntegrationsEvent.ToggleAiProvider(it))
                    }
                )
            }
            AnimatedVisibility(aiEnabled) {
                Column {
                    Spacer(Modifier.height(12.dp))

                    // Model connect: pick where GURU's model lives. App-style card,
                    // dropdown in the Settings idiom, one Save commits everything.
                    ModelConnectSection(
                        provider = provider,
                        byoBaseUrl = byoBaseUrl,
                        byoApiKey = byoApiKey,
                        byoModelName = byoModelName,
                        onEvent = onEvent
                    )

                    Spacer(Modifier.height(12.dp))
                    AiToolsSwitch(
                        checked = aiToolsEnabled,
                        onCheck = { onEvent(IntegrationsEvent.ToggleAiTools(it)) }
                    )
                    Text(
                        text = stringResource(R.string.enable_ai_tools_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    if (aiToolsEnabled) {
                        Spacer(Modifier.height(8.dp))
                        AdbPairingSection(
                            pairingState = adbPairingState,
                            adbPaired = adbPaired,
                            onPair = { code, port -> onPairAdb(code, port) },
                            onAutoPair = onAutoPairAdb,
                            onCheckStatus = { onCheckAdbStatus() }
                        )
                        Spacer(Modifier.height(8.dp))
                        FloatingOrbSection(
                            onEvent = onEvent
                        )
                    }
                }
            }
        }
    }
}

/**
 * Model connect block: provider dropdown, conditional connection fields, one
 * Save for the whole group. Corner rhythm and colours match the ADB card so
 * the section reads as one family.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelConnectSection(
    provider: AiProvider,
    byoBaseUrl: String,
    byoApiKey: String,
    byoModelName: String,
    onEvent: (IntegrationsEvent) -> Unit
) {
    var localProvider by remember(provider) { mutableStateOf(provider) }
    var localUrl by remember(byoBaseUrl) { mutableStateOf(byoBaseUrl) }
    var localKey by remember(byoApiKey) { mutableStateOf(byoApiKey) }
    var localModel by remember(byoModelName) { mutableStateOf(byoModelName) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = stringResource(R.string.model_connect),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.model_connect_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = providerDisplayName(localProvider),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.model_provider)) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.provider_unuslumen),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.provider_unuslumen_coming_soon),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        enabled = false,
                        onClick = { }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_openai)) },
                        onClick = {
                            localProvider = AiProvider.OpenAI
                            dropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_anthropic)) },
                        onClick = {
                            localProvider = AiProvider.Anthropic
                            dropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_google)) },
                        onClick = {
                            localProvider = AiProvider.Google
                            dropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_xai)) },
                        onClick = {
                            localProvider = AiProvider.XAI
                            dropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_ollama)) },
                        onClick = {
                            localProvider = AiProvider.Ollama
                            dropdownExpanded = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.provider_openai_compat)) },
                        onClick = {
                            localProvider = AiProvider.OpenAICompat
                            dropdownExpanded = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when (localProvider) {
                AiProvider.UnusLumen -> {
                    Text(
                        text = stringResource(R.string.provider_unuslumen_blurb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AiProvider.OpenAI, AiProvider.Anthropic, AiProvider.Google, AiProvider.XAI -> {
                    OutlinedTextField(
                        value = localKey,
                        onValueChange = { localKey = it },
                        label = { Text(stringResource(R.string.byo_api_key_label)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = localModel,
                        onValueChange = { localModel = it },
                        label = { Text(stringResource(R.string.byo_model_name_label)) },
                        placeholder = { Text(modelPlaceholder(localProvider)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val fixedUrl = localProvider.fixedBaseUrl()
                    if (fixedUrl != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = fixedUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AiProvider.Ollama, AiProvider.OpenAICompat -> {
                    OutlinedTextField(
                        value = localUrl,
                        onValueChange = { localUrl = it },
                        label = { Text(stringResource(R.string.byo_base_url_label)) },
                        placeholder = {
                            Text(
                                if (localProvider == AiProvider.Ollama) "http://<pc-ip>:11434"
                                else "http://<pc-ip>:1234"
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = localKey,
                        onValueChange = { localKey = it },
                        label = { Text(stringResource(R.string.byo_api_key_label_optional)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = localModel,
                        onValueChange = { localModel = it },
                        label = { Text(stringResource(R.string.byo_model_name_label)) },
                        placeholder = { Text(modelPlaceholder(localProvider)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                else -> {}
            }

            if (localProvider != AiProvider.UnusLumen && localProvider != AiProvider.None) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.api_key_stays_on_device),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        onEvent(IntegrationsEvent.SelectProvider(localProvider))
                        onEvent(IntegrationsEvent.UpdateCustomURL(localProvider, localUrl))
                        onEvent(IntegrationsEvent.UpdateApiKey(localProvider, localKey))
                        onEvent(IntegrationsEvent.UpdateModel(localProvider, localModel))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.byo_save))
                }
            }
        }
    }
}

@Composable
private fun providerDisplayName(provider: AiProvider): String = when (provider) {
    AiProvider.UnusLumen -> stringResource(R.string.provider_unuslumen)
    AiProvider.OpenAI -> stringResource(R.string.provider_openai)
    AiProvider.Anthropic -> stringResource(R.string.provider_anthropic)
    AiProvider.Google -> stringResource(R.string.provider_google)
    AiProvider.XAI -> stringResource(R.string.provider_xai)
    AiProvider.Ollama -> stringResource(R.string.provider_ollama)
    AiProvider.OpenAICompat -> stringResource(R.string.provider_openai_compat)
    AiProvider.None -> stringResource(R.string.provider_none_selected)
}

private fun modelPlaceholder(provider: AiProvider): String = when (provider) {
    AiProvider.OpenAI -> "gpt-4o-mini"
    AiProvider.Anthropic -> "claude-sonnet-4-5"
    AiProvider.Google -> "gemini-2.5-flash"
    AiProvider.XAI -> "grok-4"
    AiProvider.Ollama -> "llama3.1:8b"
    else -> "model-id"
}

@Composable
private fun AiToolsSwitch(
    modifier: Modifier = Modifier,
    checked: Boolean,
    onCheck: (Boolean) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable { onCheck(!checked) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_tools),
                contentDescription = "",
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.enable_ai_tools),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            ExperimentalBadge()
        }
        Switch(checked = checked, onCheckedChange = { onCheck(it) })
    }
}

@Composable
fun AdbPairingSection(
    pairingState: AdbPairingState,
    adbPaired: Boolean,
    onPair: (String, Int?) -> Unit,
    onAutoPair: () -> Unit = {},
    onCheckStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pairingCode by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var showPortField by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                adbPaired -> MaterialTheme.colorScheme.primaryContainer
                pairingState is AdbPairingState.Error -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when {
                        adbPaired -> "✅ ADB Connected"
                        pairingState is AdbPairingState.Pairing -> "🔄 Pairing..."
                        pairingState is AdbPairingState.Error -> "❌ Pairing Failed"
                        else -> "📱 ADB Device Control"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
                if (!adbPaired) {
                    TextButton(onClick = { showPortField = !showPortField }) {
                        Text(
                            text = if (showPortField) "Auto-discover" else "Manual port",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (!adbPaired) {
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { onAutoPair() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    Text("⚡ Auto-Pair (One-Tap Setup)")
                }

                Spacer(Modifier.height(4.dp))

                TextButton(onClick = { showManualEntry = !showManualEntry }) {
                    Text(
                        text = if (showManualEntry) "Hide manual entry" else "Enter pairing code manually",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (showManualEntry) {
                    Text(
                        text = "Go to Settings → Developer Options → Wireless Debugging → Pair device with pairing code.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it },
                        label = { Text("Pairing Code") },
                        placeholder = { Text("e.g. 123456") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    if (showPortField) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it },
                            label = { Text("Pairing Port") },
                            placeholder = { Text("e.g. 37421") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                    }

                    TextButton(onClick = { showPortField = !showPortField }) {
                        Text(
                            text = if (showPortField) "Auto-discover port" else "Manual port",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val port = portText.toIntOrNull()
                            onPair(pairingCode, port)
                        },
                        enabled = pairingCode.isNotBlank() && pairingState !is AdbPairingState.Pairing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (pairingState is AdbPairingState.Pairing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Pair Device")
                    }
                }

                if (pairingState is AdbPairingState.Error) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pairingState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Guru has full device control via ADB. Shell commands run with proper permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun FloatingOrbSection(
    onEvent: (IntegrationsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(
            context.getSharedPreferences("settings_preferences", 0)
                .getBoolean("guru_floating_orb_enabled", false)
        )
    }

    val canDrawOverApps = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        Settings.canDrawOverlays(context)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🧠",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Floating Guru Orb",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { isChecked ->
                        if (isChecked && !canDrawOverApps) {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            enabled = isChecked
                            context.getSharedPreferences("settings_preferences", 0)
                                .edit().putBoolean("guru_floating_orb_enabled", isChecked).apply()
                            onEvent(IntegrationsEvent.ToggleFloatingOrb(isChecked))
                            if (isChecked) {
                                com.unuslumen.app.util.orb.GuruOrbService.startOrb(context)
                            } else {
                                com.unuslumen.app.util.orb.GuruOrbService.stopOrb(context)
                            }
                        }
                    }
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = if (enabled) "Guru is always available — tap the floating orb on any screen to chat." else "Enable to show a floating orb on top of all apps. Tap it anytime to chat with Guru.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!canDrawOverApps) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ Requires 'Display over other apps' permission",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
fun AiProviderSectionPreview() {
    com.unuslumen.app.ui.theme.guruTheme {
        val providerFlow = flowOf(AiProvider.UnusLumen)
        val stringSetting: (PrefsKey<String>, String) -> Flow<String> = { _, default ->
            flowOf(default)
        }
        val booleanSetting: (PrefsKey<Boolean>, Boolean) -> Flow<Boolean> = { _, default ->
            flowOf(true)
        }

        AiProviderSection(
            getAiProvider = { providerFlow },
            getStringSetting = stringSetting,
            getBooleanSetting = booleanSetting,
            onEvent = {}
        )
    }
}