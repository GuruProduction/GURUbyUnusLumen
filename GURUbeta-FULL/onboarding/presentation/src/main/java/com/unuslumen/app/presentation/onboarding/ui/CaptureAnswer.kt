// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import com.unuslumen.app.util.permissions.Permission
import com.unuslumen.app.util.permissions.rememberPermissionState
import java.io.File

/**
 * CaptureAnswer — one photo, taken with the phone's own camera.
 *
 * WHAT THE ANSWER IS. The stored value is the absolute path of the JPEG, not a
 * sentence. That is deliberate: the reference photo exists so that something can
 * look at it later, so the answer has to be a file the model can point at rather
 * than a description of one.
 *
 * WHERE IT LIVES. The photo is written into the app's own internal storage under
 * `files/onboarding/`, handed to the camera through a FileProvider URI with a
 * one-shot grant, and never copied into the shared gallery. It therefore never
 * appears in anyone else's photo app and leaves with the app when it is
 * uninstalled.
 *
 * NO BUTTONS, AS EVERYWHERE ELSE IN THIS FLOW. The tile is the control. Empty, it
 * invites a photo and opens the camera. Filled, it shows the photo and a retake
 * line, and tapping it shoots again. It swallows its own taps, so pressing it
 * never advances the page.
 *
 * THE PERMISSION IS REQUESTED BEFORE THE CAMERA OPENS. Android throws if a
 * capture intent is launched with CAMERA declared in the manifest but not
 * granted, and a thrown intent looks to the human like nothing happened at all.
 * So a tap without the grant asks for it, and the shot fires by itself the moment
 * the grant lands.
 */
@Composable
fun CaptureAnswer(
    chapter: OnboardingChapter,
    answer: String,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val context = LocalContext.current
    val accent = OnboardingSkin.colourOf(chapter)
    val permission = rememberPermissionState(Permission.CAMERA)

    var box by remember { mutableStateOf(Rect.Zero) }
    var pendingShot by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // Held in a State rather than a plain local because the camera result arrives
    // long after shoot() returns, and the callback has to read the path that was
    // chosen for THIS shot, not a value captured when the screen composed.
    val targetPath = remember { mutableStateOf("") }

    val liveAnswer by rememberUpdatedState(onAnswer)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved: Boolean ->
        val path = targetPath.value
        if (saved && path.isNotBlank()) {
            liveAnswer(path)
            failure = null
        } else if (path.isNotBlank()) {
            // A cancelled shot can leave a zero-byte file behind. Clear it so the
            // folder only ever holds photos that were actually taken.
            runCatching {
                File(path).takeIf { it.exists() && it.length() == 0L }?.delete()
            }
        }
    }

    /** A fresh file for one shot, so a retake never overwrites the previous one. */
    val shoot: () -> Unit = {
        failure = null
        val dir = File(context.filesDir, "onboarding").apply { mkdirs() }
        val file = File(dir, "selfie_${System.currentTimeMillis()}.jpg")
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            targetPath.value = file.absolutePath
            launcher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            failure = "There is no camera app on this device I can open. You can skip this one."
        } catch (e: IllegalArgumentException) {
            failure = "I could not open the camera. You can skip this one."
        }
    }

    // The grant can land after the tap that asked for it. When it does, take the
    // shot rather than making the human tap a second time.
    LaunchedEffect(permission.isGranted, pendingShot) {
        if (permission.isGranted && pendingShot) {
            pendingShot = false
            shoot()
        }
    }

    val onTileTap: (Offset) -> Unit = { origin ->
        onLaunch(origin)
        if (permission.isGranted) {
            shoot()
        } else {
            pendingShot = true
            permission.launchRequest()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { box = it.boundsInWindow() }
                .aspectRatio(1f)
                .clip(OnboardingTileShape)
                .background(accent)
                .clickable { onTileTap(Offset(box.center.x, box.center.y)) }
        ) {
            if (answer.isNotBlank()) {
                AsyncImage(
                    model = File(answer),
                    contentDescription = "Your reference photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(OnboardingTileShape)
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(14.dp)
                        .clip(CircleShape)
                        .background(OnboardingSkin.onColour(accent))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Retake",
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = accent,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = OnboardingSkin.Body
                        )
                    )
                }
            } else {
                // A solid field with one line of words on it. No disc, no plus, no
                // icon: the previous version stacked a 64dp circle above a two-line
                // caption and read as a placeholder graphic rather than a door.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(26.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = if (permission.isGranted) {
                            "Take your photo"
                        } else {
                            "Allow the camera"
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = OnboardingSkin.onColour(accent),
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = OnboardingSkin.Display,
                            lineHeight = 1.08.em,
                            textAlign = TextAlign.Start
                        )
                    )
                }
            }
        }

        if (!failure.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = failure.orEmpty(),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = OnboardingSkin.Error,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Body,
                    lineHeight = 1.45.em
                )
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "You can skip this. GURU will still be yours, it just will not be able to check it is you.",
            style = MaterialTheme.typography.labelMedium.copy(
                color = OnboardingSkin.inkFaintOf(chapter),
                fontWeight = FontWeight.Normal,
                fontFamily = OnboardingSkin.Body,
                lineHeight = 1.5.em
            )
        )
    }
}
