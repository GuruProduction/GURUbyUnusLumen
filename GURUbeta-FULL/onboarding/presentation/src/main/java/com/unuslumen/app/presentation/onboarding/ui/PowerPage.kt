// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import com.unuslumen.app.onboarding.domain.PowerItem
import com.unuslumen.app.onboarding.domain.PowerKind
import com.unuslumen.app.onboarding.domain.QuestionChoice
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.toAiProviderOrNone

/**
 * PowerPage — plug the engine in.
 *
 * The setup stage: model key, local brain, skills, vision, language. Every row
 * performs a real write on finish; none is decorative. A row only shows as
 * connected when there is something real behind it, so the screen can never claim
 * a connection that does not exist.
 *
 * Each row is a filled panel in its own colour from the palette. Unconnected it
 * sits at 12% of that colour with a coloured edge; connected it fills solid and
 * the label flips to white or ink by measured contrast. That is the same language
 * the question tiles use, so the setup stage reads as the same product rather
 * than as a settings screen bolted onto the end.
 */
@Composable
fun PowerPage(
    chapter: OnboardingChapter,
    chapters: List<OnboardingChapter>,
    currentChapter: Int,
    items: List<PowerItem>,
    powerOn: Set<String>,
    draft: Map<String, String>,
    nodeColours: List<Color>,
    colouredChapters: Set<Int>,
    saving: Boolean,
    error: String?,
    canConnect: (String) -> Boolean,
    onField: (String, String) -> Unit,
    onToggle: (String) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    val seed = remember { OnboardingPalette.seedFor("power") }

    OnboardingFrame(
        chapter = chapter,
        onAdvance = { if (!saving) onFinish() },
        onBack = onBack,
        hint = if (saving) "Waking GURU up…" else "Tap right to wake GURU up",
        header = {
            OnboardingTopBar(chapter = chapter, nodeCount = nodeColours.size)
            Spacer(Modifier.height(10.dp))
            OnboardingRail(
                chapters = chapters,
                currentChapter = currentChapter,
                colouredChapters = colouredChapters
            )
            Spacer(Modifier.height(10.dp))
            OnboardingChapterLabel(
                chapter = chapter,
                chapterIndex = currentChapter,
                chapterCount = chapters.size.coerceAtLeast(1),
                tag = "Last step"
            )
        },
        map = {
            OnboardingConstellation(
                chapter = chapter,
                nodeColours = nodeColours
            )
        }
    ) {
        OnboardingEyebrow(
            chapter = chapter,
            index = 2,
            count = 3
        )
        Spacer(Modifier.height(12.dp))
        OnboardingHeadline(text = "Plug the engine in.", chapter = chapter)
        Spacer(Modifier.height(10.dp))
        OnboardingSub(
            text = "One key and GURU runs on your own model. Nothing is metered through us, and no account exists to be banned.",
            chapter = chapter
        )
        Spacer(Modifier.height(20.dp))

        items.forEachIndexed { index, item ->
            // A switch row is On when its toggle is on. A DATA PANEL is filled
            // when there is something typed in it, because that is the truth a
            // human can see. The old panel judged both by the toggle, so a panel
            // with a real key in it still read "Off" and looked like it had not
            // saved, which is half of why this screen felt broken.
            val isSwitch = item.kind == PowerKind.SKILLS || item.kind == PowerKind.VISION
            val filled = if (isSwitch) {
                item.key in powerOn
            } else {
                panelHasContent(item.kind, draft)
            }

            PowerPanel(
                chapter = chapter,
                item = item,
                colour = OnboardingPalette.colourFor(seed, index),
                connected = filled,
                value = draft[item.key].orEmpty(),
                modelName = draft["model_name"].orEmpty(),
                keyValue = draft["provider_key"].orEmpty(),
                urlValue = draft["provider_url"].orEmpty(),
                canConnect = canConnect(item.key),
                onField = onField,
                onToggle = onToggle
            )
            if (index != items.lastIndex) Spacer(Modifier.height(10.dp))
        }

        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = OnboardingSkin.Error,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    lineHeight = 1.45.em
                )
            )
        }

        Spacer(Modifier.height(16.dp))
        OnboardingFootnote(
            text = "Keys stay on this device. They are never sent to us.",
            chapter = chapter
        )
    }
}

/**
 * One setup row, as a filled panel.
 *
 * The dot fills with the row's colour when connected. The whole panel fills when
 * connected, which is the only thing on the page that changes size of colour, so
 * a human can see at a glance how much of the engine is already attached.
 */
@Composable
private fun PowerPanel(
    chapter: OnboardingChapter,
    item: PowerItem,
    colour: Color,
    connected: Boolean,
    value: String,
    modelName: String,
    keyValue: String,
    urlValue: String,
    canConnect: Boolean,
    onField: (String, String) -> Unit,
    onToggle: (String) -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val fill by animateColorAsState(
        targetValue = if (connected) colour else OnboardingPalette.washOf(colour),
        animationSpec = tween(220),
        label = "power-fill"
    )
    val edge by animateColorAsState(
        targetValue = if (connected) colour else OnboardingPalette.edgeOf(colour),
        animationSpec = tween(220),
        label = "power-edge"
    )
    val titleColour = if (connected) OnboardingPalette.onTile(colour) else ink
    val captionColour = if (connected) {
        OnboardingPalette.onTile(colour).copy(alpha = 0.82f)
    } else {
        OnboardingSkin.inkSoftOf(chapter)
    }
    val dotScale by animateColorAsState(
        targetValue = if (connected) colour else Color.Transparent,
        animationSpec = tween(200),
        label = "power-dot"
    )

    // Only Skills and Vision are real switches. The provider and language panels
    // are text fields whose state IS the value, so they carry no On/Off control
    // and no toggle gesture: a switch next to a text field says "this is off and
    // none of what you typed counts", which was false and was the reason the
    // screen read as unsaved.
    val isSwitch = item.kind == PowerKind.SKILLS || item.kind == PowerKind.VISION

    Column(
        Modifier
            .fillMaxWidth()
            .clip(OnboardingTileShape)
            .background(fill)
            .border(
                width = if (connected) 2.dp else 1.5.dp,
                color = edge,
                shape = OnboardingTileShape
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (isSwitch) {
                        Modifier.toggleable(
                            value = connected,
                            role = Role.Switch,
                            enabled = canConnect,
                            onValueChange = { if (canConnect) onToggle(item.key) }
                        )
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(
                        if (connected) {
                            OnboardingPalette.onTile(colour)
                        } else {
                            dotScale.takeIf { it.alpha > 0f } ?: ink.copy(alpha = 0.16f)
                        }
                    )
                    .then(
                        if (connected) {
                            Modifier.border(
                                width = 1.dp,
                                color = colour.copy(alpha = 0.40f),
                                shape = CircleShape
                            )
                        } else Modifier
                    )
            )
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = titleColour,
                        fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Medium,
                        fontFamily = OnboardingSkin.Body
                    )
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.caption,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = captionColour,
                        fontFamily = OnboardingSkin.Body,
                        lineHeight = 1.4.em
                    )
                )
            }
            if (isSwitch) {
                Text(
                    text = if (connected) "On" else "Off",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (connected) {
                            OnboardingPalette.onTile(colour)
                        } else {
                            OnboardingSkin.inkFaintOf(chapter)
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = OnboardingSkin.Body,
                        letterSpacing = 0.08.em
                    )
                )
            }
        }

        when (item.kind) {
            PowerKind.PROVIDER -> {
                Spacer(Modifier.height(12.dp))
                ProviderPicks(
                    chapter = chapter,
                    colour = colour,
                    providers = item.providers,
                    chosen = value,
                    onPick = { onField(item.key, it) }
                )
                // The fields below change to match the provider, so the panel asks
                // for exactly what that endpoint needs and never for what it does
                // not. Picking Google should not be asked for a base URL.
                when (value.toAiProviderOrNone()) {
                    AiProvider.OpenAI,
                    AiProvider.Anthropic,
                    AiProvider.Google,
                    AiProvider.XAI -> {
                        InlineField(
                            chapter = chapter,
                            colour = colour,
                            connected = connected,
                            label = "API key",
                            value = keyValue,
                            placeholder = keyPlaceholder(value),
                            isSecret = true,
                            onChange = { onField("provider_key", it) }
                        )
                        InlineField(
                            chapter = chapter,
                            colour = colour,
                            connected = connected,
                            label = "Model name",
                            value = modelName,
                            placeholder = modelPlaceholderFor(value),
                            isSecret = false,
                            onChange = { onField("model_name", it) }
                        )
                    }

                    AiProvider.Ollama,
                    AiProvider.OpenAICompat -> {
                        InlineField(
                            chapter = chapter,
                            colour = colour,
                            connected = connected,
                            label = "Endpoint URL",
                            value = urlValue,
                            placeholder = if (value.toAiProviderOrNone() == AiProvider.Ollama) {
                                "http://<pc-ip>:11434"
                            } else {
                                "http://<pc-ip>:1234"
                            },
                            isSecret = false,
                            onChange = { onField("provider_url", it) }
                        )
                        InlineField(
                            chapter = chapter,
                            colour = colour,
                            connected = connected,
                            label = "API key (optional)",
                            value = keyValue,
                            placeholder = "Leave blank if it has none",
                            isSecret = true,
                            onChange = { onField("provider_key", it) }
                        )
                        InlineField(
                            chapter = chapter,
                            colour = colour,
                            connected = connected,
                            label = "Model name",
                            value = modelName,
                            placeholder = modelPlaceholderFor(value),
                            isSecret = false,
                            onChange = { onField("model_name", it) }
                        )
                    }

                    else -> Unit
                }
            }

            PowerKind.LANGUAGE -> {
                Spacer(Modifier.height(12.dp))
                InlineField(
                    chapter = chapter,
                    colour = colour,
                    connected = connected,
                    label = "Language GURU answers in",
                    value = value,
                    placeholder = "English",
                    isSecret = false,
                    onChange = { onField(item.key, it) }
                )
            }

            else -> Unit
        }
    }
}

/**
 * Whether a data panel has anything real typed into it.
 *
 * This is what makes the panel show as filled, and it is display only: the save
 * writes whatever is non-blank regardless, so a half-filled panel still keeps
 * every value it holds.
 */
private fun panelHasContent(kind: PowerKind, draft: Map<String, String>): Boolean = when (kind) {
    PowerKind.PROVIDER -> draft["provider"].orEmpty().isNotBlank() ||
        draft["model_name"].orEmpty().isNotBlank()
    PowerKind.LANGUAGE -> draft["language"].orEmpty().isNotBlank()
    PowerKind.SKILLS, PowerKind.VISION -> false
}

/**
 * The provider picker, inside the setup panel.
 *
 * One line per provider, all of them visible at once. That is deliberate: the
 * whole complaint about the old panel was that it silently offered fewer
 * endpoints than the Settings screen, so hiding any behind a dropdown would
 * repeat the fault in a different shape. Six rows is short enough to show.
 *
 * The chosen row is marked by a filled disc and a heavier label rather than a
 * tick or a radio, matching the tiles elsewhere in the flow.
 */
@Composable
private fun ProviderPicks(
    chapter: OnboardingChapter,
    colour: Color,
    providers: List<QuestionChoice>,
    chosen: String,
    onPick: (String) -> Unit,
) {
    val ink = OnboardingSkin.inkOf(chapter)
    val muted = if (chosen.isNotBlank()) OnboardingSkin.onColour(colour) else ink
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "PROVIDER",
            style = MaterialTheme.typography.labelSmall.copy(
                color = muted.copy(alpha = if (chosen.isNotBlank()) 0.70f else 0.55f),
                fontWeight = FontWeight.SemiBold,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.10.em
            )
        )
        Spacer(Modifier.height(6.dp))
        providers.forEach { provider ->
            val isOn = provider.value.equals(chosen.trim(), ignoreCase = true)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPick(provider.value) }
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (isOn) muted else Color.Transparent
                        )
                        .border(
                            width = 1.5.dp,
                            color = muted.copy(alpha = if (isOn) 0f else 0.45f),
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = provider.label,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = muted,
                        fontWeight = if (isOn) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = OnboardingSkin.Body
                    )
                )
            }
        }
    }
}

/** The key placeholder a provider expects, so the field hints at the shape. */
private fun keyPlaceholder(providerName: String): String = when (
    providerName.trim().toAiProviderOrNone()
) {
    AiProvider.Anthropic -> "sk-ant-…"
    AiProvider.Google -> "AIza…"
    AiProvider.XAI -> "xai-…"
    else -> "sk-…"
}

/** A realistic model name for a provider, so nobody has to guess the syntax. */
private fun modelPlaceholderFor(providerName: String): String = when (
    providerName.trim().toAiProviderOrNone()
) {
    AiProvider.OpenAI -> "gpt-4o-mini"
    AiProvider.Anthropic -> "claude-sonnet-4-5"
    AiProvider.Google -> "gemini-2.5-flash"
    AiProvider.XAI -> "grok-4"
    AiProvider.Ollama -> "llama3.1:8b"
    else -> "model-id"
}

/**
 * A line to type on, inside a panel.
 *
 * When the panel is filled the field flips to a translucent white well so it stays
 * legible on the solid colour; when it is resting it stays dark on the wash.
 */
@Composable
private fun InlineField(
    chapter: OnboardingChapter,
    colour: Color,
    connected: Boolean,
    label: String,
    value: String,
    placeholder: String,
    isSecret: Boolean,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = OnboardingSkin.inkOf(chapter)
    val textColour = if (connected) OnboardingPalette.onTile(colour) else ink
    val faint = textColour.copy(alpha = if (connected) 0.62f else 0.38f)

    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = textColour.copy(alpha = 0.70f),
                fontWeight = FontWeight.SemiBold,
                fontFamily = OnboardingSkin.Body,
                letterSpacing = 0.10.em
            )
        )
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = textColour,
                fontWeight = FontWeight.Normal,
                fontFamily = OnboardingSkin.Body
            ),
            cursorBrush = SolidColor(textColour),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isSecret) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    color = faint,
                                    fontFamily = OnboardingSkin.Body
                                )
                            )
                        }
                        inner()
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (focused) 2.dp else 1.dp)
                            .background(
                                if (focused) {
                                    textColour
                                } else {
                                    textColour.copy(alpha = 0.30f)
                                }
                            )
                    )
                }
            }
        )
    }
}
