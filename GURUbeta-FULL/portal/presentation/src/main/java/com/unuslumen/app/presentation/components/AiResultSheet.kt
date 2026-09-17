package com.unuslumen.app.presentation.components

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.defaultMarkdownTypography
import com.unuslumen.app.ui.components.common.drawGradientRadial
import com.unuslumen.app.ui.components.common.frostedGlass
import com.unuslumen.app.ui.theme.Blue
import com.unuslumen.app.ui.theme.DarkOrange
import com.unuslumen.app.ui.theme.LightPurple
import com.unuslumen.app.ui.theme.guruTheme
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import sv.lib.squircleshape.CornerSmoothing
import sv.lib.squircleshape.SquircleShape

@Composable
fun AiResultSheet(
    modifier: Modifier = Modifier,
    loading: Boolean,
    result: String?,
    error: String?,
    liquidState: LiquidState,
    onReplaceClick: () -> Unit,
    onAddToNoteClick: () -> Unit,
    onCopyClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "infiniteTransition")

    val offset by infiniteTransition.animateValue(
        initialValue = 0,
        targetValue = 20,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse,
        ),
        typeConverter = Int.VectorConverter,
        label = "Card y offset"
    )
    val xMul by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(3350, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
        label = "x Multiplier"
    )
    val yMul by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0.9f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(2200, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
        label = "y Multiplier"
    )

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier
            .wrapContentHeight()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .offset {
                if (loading) {
                    IntOffset(0, offset)
                } else IntOffset.Zero
            }
    ) {
        GlowingBorder(
            modifier = Modifier.matchParentSize(),
            innerPadding = PaddingValues(
                vertical = 28.dp,
                horizontal = 22.dp
            ),
            blur = 24.dp,
            animationDuration = 2000
        )
        val shape = SquircleShape(radius = 42.dp, smoothing = CornerSmoothing.Medium)
        Card(
            modifier = modifier
                .padding(vertical = 24.dp)
                .widthIn(max = 500.dp)
                .padding(horizontal = 12.dp)
                .clickable(enabled = false) {},
            shape = shape,
            elevation = CardDefaults.cardElevation(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .heightIn(min = 120.dp)
                    .frostedGlass(liquidState, shape)
                    .drawBehind {
                        drawGradientRadial(
                            surfaceVariant
                                .copy(alpha = 0.75f)
                                .compositeOver(Blue),
                            Offset(
                                size.width * xMul,
                                size.height - size.height * yMul
                            )
                        )
                        drawGradientRadial(
                            surfaceVariant
                                .copy(alpha = 0.75f)
                                .compositeOver(DarkOrange),
                            Offset(
                                size.width - size.width * xMul,
                                size.height - size.height * yMul
                            )
                        )
                        drawGradientRadial(
                            surfaceVariant
                                .copy(alpha = 0.75f)
                                .compositeOver(LightPurple),
                            Offset(
                                size.width - size.width * xMul,
                                size.height * yMul
                            )
                        )
                    }
                    .fillMaxWidth()
                    .animateContentSize(
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessVeryLow
                        )
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (result != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Markdown(
                            content = result,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, start = 24.dp, end = 24.dp),
                            imageTransformer = Coil2ImageTransformerImpl,
                            typography = defaultMarkdownTypography()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AiResultActions(
                        onCopyClick = onCopyClick,
                        onReplaceClick = onReplaceClick,
                        onAddToNoteClick = onAddToNoteClick
                    )
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AiResultActions(
    modifier: Modifier = Modifier,
    onCopyClick: () -> Unit,
    onReplaceClick: () -> Unit,
    onAddToNoteClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AiResultAction(
            textRes = R.string.copy,
            iconRes = R.drawable.ic_copy,
            onClick = onCopyClick
        )
        AiResultAction(
            textRes = R.string.replace,
            iconRes = R.drawable.ic_replace,
            onClick = onReplaceClick
        )
        AiResultAction(
            textRes = R.string.add_to_note,
            iconRes = R.drawable.ic_add_note,
            onClick = onAddToNoteClick
        )
    }
}

@Composable
private fun RowScope.AiResultAction(
    textRes: Int,
    iconRes: Int,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(bottom = 12.dp, top = 8.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = stringResource(id = textRes),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(id = textRes),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}



@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AiResultSheetPreview() {
    guruTheme {
        val liquidState = rememberLiquidState()
        Box(Modifier.height(650.dp)) {
            Surface(
                modifier = Modifier.fillMaxSize()
            ) {
                Markdown(
                    content = """
                        # Introduction to Computational Complexity

                        Computational complexity is a branch of the theory of computation that focuses on classifying computational problems according to their inherent difficulty and the resources required to solve them.

                        ## 1. Key Resources
                        The two most common resources measured are:
                        - **Time Complexity:** How many steps (or how much time) an algorithm takes to complete relative to the size of the input.
                        - **Space Complexity:** How much memory or storage an algorithm requires relative to the size of the input.

                        ## 2. Big O Notation
                        Big O notation is used to describe the upper bound of an algorithm's growth rate. Common complexities include:
                        - **O(1):** Constant time (e.g., accessing an array element).
                        - **O(log n):** Logarithmic time (e.g., binary search).
                        - **O(n):** Linear time (e.g., iterating through a list).
                        - **O(n log n):** Linearithmic time (e.g., Merge Sort, Quick Sort).
                        - **O(n²):** Quadratic time (e.g., Bubble Sort).
                        - **O(2ⁿ):** Exponential time (e.g., recursive calculation of Fibonacci numbers).

                        """.trimIndent(),
                    typography = defaultMarkdownTypography(),
                    modifier = Modifier.padding(horizontal = 24.dp).liquefiable(liquidState)
                )
            }
            AiResultSheet(
                modifier = Modifier.padding(18.dp),
                loading = false,
                result = """
                - Computational complexity classifies problems by difficulty and required resources.  
                - **Key resources:**  
                  - *Time complexity*: steps/time relative to input size.  
                  - *Space complexity*: memory/storage relative to input size.  
                - **Big O notation** (upper bound growth rates):  
                  - O(1): constant time.  
                  - O(log n): logarithmic time.  
                  - O(n): linear time.  
                  - O(n log n): linearithmic time.  
                  - O(n²): quadratic time.  
                  - O(2ⁿ): exponential time.  
                - **Major complexity classes:**  
                  - **P**: solvable in polynomial time.  
                  - **NP**: solutions verifiable in polynomial time.  
                  - **NP‑Complete**: hardest problems in NP; polynomial solution would solve all NP problems.  
                  - **NP‑Hard**: at least as hard as NP‑Complete, not necessarily in NP.  
                - **P vs NP problem:** Open question whether every quickly verifiable problem (NP) is also quickly solvable (P); most believe P ≠ NP.  
                - **Importance:** Guides developers to write efficient code, select appropriate data structures, and recognize intractable problems that may need approximations or heuristics.
            """.trimIndent(),
                error = null,
                liquidState = liquidState,
                {}, {}, {}
            )
        }
    }
}

