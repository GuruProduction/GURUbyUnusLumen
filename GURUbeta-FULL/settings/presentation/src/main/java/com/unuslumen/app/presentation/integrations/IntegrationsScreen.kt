package com.unuslumen.app.presentation.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unuslumen.app.presentation.components.SettingsPageBrush
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import org.koin.androidx.compose.koinViewModel

/**
 * Model Connect screen host — the old IntegrationsScreen, renamed and
 * rebuilt in the Portal design language. Owns the ViewModel wiring and the
 * back-to-Portal hop after a successful connect save.
 */
@Composable
fun IntegrationsScreen(
    onBackToPortal: () -> Unit = {},
    viewModel: IntegrationsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val adbPairingState by viewModel.adbPairingState.collectAsStateWithLifecycle()
    val adbPaired by viewModel.adbPaired.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            guruAppBar(title = stringResource(R.string.integrations))
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SettingsPageBrush)
                .padding(paddingValues)
        ) {
            ModelConnectScreen(
                getAiProvider = viewModel::getAiProvider,
                getStringSetting = viewModel::getSettings,
                getBooleanSetting = viewModel::getSettings,
                adbPairingState = adbPairingState,
                adbPaired = adbPaired,
                onEvent = viewModel::onEvent,
                onPairAdb = { code, port -> viewModel.pairWithAdb(context, code, port) },
                onAutoPairAdb = { viewModel.autoPairAdb(context) },
                onCheckAdbStatus = { viewModel.checkAdbStatus(context) },
                onOpenPortal = onBackToPortal,
            )
        }
    }
}