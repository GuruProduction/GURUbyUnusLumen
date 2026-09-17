package com.unuslumen.app.presentation.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import coil.compose.AsyncImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.SubTask
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.LeftToRight
import com.unuslumen.app.ui.components.common.clearGlass
import com.unuslumen.app.ui.components.common.drawGradientRadial
import com.unuslumen.app.ui.theme.guruTheme
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.launch

private val GoldAccent = Color(0xFFDAA520)
private val WarmInk = Color(0xFF2B241C)

@Composable
fun PortalChatBar(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean,
    loading: Boolean,
    liquidState: LiquidState,
    attachments: List<AiMessageAttachment>,
    onTextChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    toolCallCount: Int = 0,
    isToolCallActive: Boolean = false,
    onToolCallClick: () -> Unit = {},
    onFilePreview: (String) -> Unit = {},
    onOtioClick: () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val isExpanded = isFocused || text.isNotBlank()
    val cornerRadius by animateDpAsState(
        if (isExpanded) 22.dp else 28.dp,
        animationSpec = chatBarAnimationSpec(),
    )
    val shape = RoundedCornerShape(cornerRadius)

    val rotatingTips = remember {
        listOf(
            "Ask Guru anything...",
            "Tip: Tap attach to add a note or task",
        )
    }
    var currentTipIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(isFocused) {
        if (!isFocused) {
            while (true) {
                kotlinx.coroutines.delay(3000L)
                currentTipIndex = (currentTipIndex + 1) % rotatingTips.size
            }
        }
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .fillMaxWidth()
    ) {
        AnimatedVisibility(attachments.isNotEmpty()) {
            AiAttachmentsSection(
                attachments = attachments,
                editable = true,
                onRemove = onRemoveAttachment,
                onFilePreview = onFilePreview,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Card(
            modifier = modifier
                .padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
            elevation = CardDefaults.cardElevation(0.dp),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (isExpanded) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
            ),
        ) {
            Box(
                modifier = Modifier
                    .clearGlass(
                        liquidState = liquidState,
                        shape = { shape },
                        edge = { if (isExpanded) 0.012f else 0.02f },
                    )
                    .fillMaxWidth(),
            ) {
                // Warm paper tint on the chat bar — subtle aged-cream wash
                // matching the warm palette instead of the old lavender
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFF0E8DA).copy(alpha = 0.12f),
                                    Color(0xFFE4D9C6).copy(alpha = 0.04f),
                                )
                            )
                        )
                )
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(chatBarAnimationSpec()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = text,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            onValueChange = onTextChange,
                            shape = shape,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(0.dp, if (isFocused) 250.dp else 150.dp)
                                .onFocusChanged { isFocused = it.isFocused },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                cursorColor = GoldAccent
                            ),
                            placeholder = {
                                Crossfade(
                                    targetState = currentTipIndex,
                                    animationSpec = tween(500, easing = FastOutSlowInEasing),
                                    label = "tipRotation"
                                ) { tipIndex ->
                                    Text(
                                        text = rotatingTips[tipIndex],
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = WarmInk.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        )
                        if (loading) {
                            // While Guru works: stop is always available, and send is
                            // available the moment you've typed — sending interrupts
                            // the in-flight run (Lux Code style) instead of being blocked.
                            Box(
                                modifier = Modifier
                                    .padding(end = 4.dp, top = 4.dp, bottom = 4.dp)
                                    .size(33.dp)
                                    .clickable { onCancel() },
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = "file:///android_asset/stop.png",
                                    contentDescription = null,
                                    modifier = Modifier.size(33.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp, top = 4.dp, bottom = 4.dp)
                                    .size(33.dp)
                                    .clickable(enabled = enabled) { onSend() },
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = "file:///android_asset/send.png",
                                    contentDescription = stringResource(id = R.string.portal_chat_bar_send_content_description),
                                    modifier = Modifier.size(33.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp, top = 4.dp, bottom = 4.dp)
                                    .size(33.dp)
                                    .clickable(enabled = enabled) { onSend() },
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = "file:///android_asset/send.png",
                                    contentDescription = stringResource(id = R.string.portal_chat_bar_send_content_description),
                                    modifier = Modifier.size(33.dp)
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = isFocused,
                        enter = fadeIn(chatBarAnimationSpec()) + expandVertically(chatBarAnimationSpec()),
                        exit = fadeOut(chatBarAnimationSpec()) + shrinkVertically(chatBarAnimationSpec())
                    ) {
                        LeftToRight {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 1. Otio — the way out: tap to leave the portal
                                // and land back in the lobby. First in the row.
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clickable(onClick = onOtioClick),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        model = "file:///android_asset/GURUicon.png",
                                        contentDescription = stringResource(R.string.portal_header_lobby_content_description),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                // 2. Attach (existing onAttachClick onClick kept intact)
                                IconButton(onClick = { onAttachClick() }) {
                                    AsyncImage(
                                        model = "file:///android_asset/AttachmentIcon.png",
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                // 3. Wallet (visual only, no logic yet)
                                AsyncImage(
                                    model = "file:///android_asset/WalletIcon.png",
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                // 4. Chat History (visual only, no logic yet)
                                AsyncImage(
                                    model = "file:///android_asset/ChatHistoryIcon.png",
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                // 5. Masks (visual only, no logic yet)
                                AsyncImage(
                                    model = "file:///android_asset/MasksIcon.png",
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                // 6. Bell (visual only, no logic yet)
                                AsyncImage(
                                    model = "file:///android_asset/NotificationIcon.png",
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                // 7. Tool Calls node with counter badge
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clickable(onClick = { onToolCallClick() }),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        model = "file:///android_asset/ToolsCalls.png",
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    if (toolCallCount > 0) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(14.dp)
                                                .background(
                                                    if (isToolCallActive) Color(0xFFFFA726) else GoldAccent,
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = if (toolCallCount > 9) "9+" else "$toolCallCount",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                ),
                                                color = Color.Black,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun <T> chatBarAnimationSpec() = tween<T>(durationMillis = 300, easing = FastOutSlowInEasing)

fun Modifier.drawAnimatedGradient(
    loading: Boolean
) = composed {
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    val animX = remember(loading) { Animatable(lastX) }
    val animY = remember(loading) { Animatable(lastY) }

    LaunchedEffect(loading) {
        if (loading) {
            launch {
                animX.animateTo(
                    1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse
                    )
                ) { lastX = value }
            }
            launch {
                animY.animateTo(
                    1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2800, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse
                    )
                ) { lastY = value }
            }
        }
    }
    drawBehind {
        val xMul = animX.value
        val yMul = animY.value
        drawGradientRadial(
            Color(0xFFDAA520).copy(0.10f),
            Offset(size.width * xMul, size.height - size.height * yMul),
            radius = size.maxDimension * 0.45f
        )
        drawGradientRadial(
            Color(0xFFB8956A).copy(0.10f),
            Offset(size.width - size.width * xMul, size.height - size.height * yMul),
            radius = size.maxDimension * 0.45f
        )
        drawGradientRadial(
            Color(0xFF8A6D3A).copy(0.08f),
            Offset(size.width - size.width * xMul * 0.7f, size.height * yMul),
            radius = size.maxDimension * 0.45f
        )
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun PortalChatBarPreview() {
    guruTheme {
        val liquidState = rememberLiquidState()
        Box(
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(Modifier.liquefiable(liquidState)) {
                LazyColumn {
                    item {
                        MessageCard(
                            message = AiMessage.UserMessage(
                                uuid = "uuid",
                                content = "This is a test example message",
                                time = 1
                            ),
                            onCopy = {}
                        )
                    }
                    item {
                        Spacer(Modifier.height(26.dp))
                    }
                }
            }
            PortalChatBar(
                text = "",
                enabled = true,
                attachments = listOf(
                    AiMessageAttachment.Note(
                        Note(
                            id = "1",
                            title = "This is a Note Title",
                            content = "Note Content",
                        )
                    ),
                    AiMessageAttachment.Task(
                        Task(
                            id = "1",
                            title = "This is a Task Title",
                            description = "Task Description",
                            isCompleted = false,
                            dueDate = 12345,
                            subTasks = listOf(
                                SubTask()
                            )
                        )
                    )
                ),
                loading = true,
                onTextChange = {},
                onAttachClick = {},
                onRemoveAttachment = {},
                onSend = {},
                onCancel = {},
                liquidState = liquidState
            )
        }
    }
}