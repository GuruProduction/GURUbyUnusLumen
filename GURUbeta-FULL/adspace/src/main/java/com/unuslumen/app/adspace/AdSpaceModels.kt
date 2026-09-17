package com.unuslumen.app.adspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimationConfig(
    val type: String = "custom",
    @SerialName("stickmanColour") val stickmanColour: String = "#DAA520",
    @SerialName("backgroundColour") val backgroundColour: String? = null,
    @SerialName("durationMs") val durationMs: Long = 3000,
    @SerialName("stickmanScale") val stickmanScale: Float = 1.0f,
    val keyframes: List<Keyframe> = emptyList()
)

@Serializable
data class Keyframe(
    val time: Long = 0,
    val pose: Pose = Pose()
)

@Serializable
data class Pose(
    val head: Float = 0f,
    val spine: Float = 0f,
    val leftArm: Float = -15f,
    val rightArm: Float = 15f,
    val leftForearm: Float = 0f,
    val rightForearm: Float = 0f,
    val leftLeg: Float = 8f,
    val rightLeg: Float = -8f,
    val leftShin: Float = 0f,
    val rightShin: Float = 0f
)

@Serializable
data class AdPack(
    val id: String,
    val version: Int,
    @SerialName("box_height_dp") val boxHeightDp: Int = 30,
    @SerialName("background_colour") val backgroundColour: String? = null,
    @SerialName("entry_duration_ms") val entryDurationMs: Long = 5000,
    @SerialName("rotation_mode") val rotationMode: RotationMode = RotationMode.SEQUENTIAL,
    @SerialName("transition_type") val transitionType: TransitionType = TransitionType.CROSSFADE,
    @SerialName("crossfade_duration_ms") val crossfadeDurationMs: Long = 300,
    @SerialName("slide_duration_ms") val slideDurationMs: Long = 400,
    @SerialName("slide_direction") val slideDirection: String = "left",
    val entries: List<AdEntry> = emptyList()
)

@Serializable
data class AdEntry(
    val id: String,
    @SerialName("lottie_data") val lottieData: String,
    @SerialName("lottie_file_name") val lottieFileName: String = "",
    val slogan: String = "",
    @SerialName("text_animation") val textAnimation: TextAnimationType = TextAnimationType.TYPING,
    @SerialName("text_colour_mode") val textColourMode: TextColourMode = TextColourMode.SINGLE,
    @SerialName("text_colours") val textColours: List<String> = listOf("#DAA520"),
    @SerialName("text_colour_cycle_ms") val textColourCycleMs: Long = 2000,
    @SerialName("font_family") val fontFamily: String? = null,
    @SerialName("font_size_sp") val fontSizeSp: Float = 12f,
    @SerialName("font_weight") val fontWeight: Int = 400,
    @SerialName("letter_spacing") val letterSpacing: Float = 0f,
    @SerialName("text_position") val textPosition: TextPosition = TextPosition.BELOW_ANIMATION,
    @SerialName("order_index") val orderIndex: Int = 0,
    @SerialName("animation_config") val animationConfig: AnimationConfig? = null,
    @SerialName("stickman_colour") val stickmanColour: String? = null,
    @SerialName("stickman_scale") val stickmanScale: Float = 1.0f,
    @SerialName("gradient_direction") val gradientDirection: Float = 0f,
    @SerialName("lottie_loop_mode") val lottieLoopMode: String = "infinite",
    @SerialName("lottie_loop_count") val lottieLoopCount: Int = 1,
    @SerialName("typing_speed_ms") val typingSpeedMs: Long = 80,
    @SerialName("typing_delete_speed_ms") val typingDeleteSpeedMs: Long = 40,
    @SerialName("fade_duration_ms") val fadeDurationMs: Long = 500,
    @SerialName("expand_duration_ms") val expandDurationMs: Long = 800,
    @SerialName("text_slide_duration_ms") val textSlideDurationMs: Long = 400,
    @SerialName("text_x_offset_dp") val textXOffsetDp: Float = 0f,
    @SerialName("text_y_offset_dp") val textYOffsetDp: Float = 0f,
    @SerialName("elements") val elements: List<AdElement> = emptyList(),
)

@Serializable
data class AdElement(
    val id: String = "",
    val type: String = "text",
    @SerialName("x_dp") val xDp: Float = 0f,
    @SerialName("y_dp") val yDp: Float = 0f,
    @SerialName("width_dp") val widthDp: Float = 100f,
    @SerialName("height_dp") val heightDp: Float = 100f,
    val rotation: Float = 0f,
    @SerialName("z_index") val zIndex: Int = 0,
    val opacity: Float = 1f,
    val text: String? = null,
    @SerialName("text_animation") val textAnimation: String? = null,
    @SerialName("font_family") val fontFamily: String? = null,
    @SerialName("font_size_sp") val fontSizeSp: Float? = null,
    @SerialName("font_weight") val fontWeight: Int? = null,
    @SerialName("letter_spacing") val letterSpacing: Float? = null,
    @SerialName("text_colours") val textColours: List<String>? = null,
    @SerialName("text_colour_mode") val textColourMode: String? = null,
    @SerialName("text_colour_cycle_ms") val textColourCycleMs: Long? = null,
    @SerialName("gradient_direction") val gradientDirection: Float? = null,
    @SerialName("typing_speed_ms") val typingSpeedMs: Long? = null,
    @SerialName("typing_delete_speed_ms") val typingDeleteSpeedMs: Long? = null,
    @SerialName("fade_duration_ms") val fadeDurationMs: Long? = null,
    @SerialName("expand_duration_ms") val expandDurationMs: Long? = null,
    @SerialName("text_slide_duration_ms") val textSlideDurationMs: Long? = null,
    @SerialName("lottie_file_name") val lottieFileName: String? = null,
    @SerialName("lottie_data") val lottieData: String? = null,
    @SerialName("lottie_loop_mode") val lottieLoopMode: String? = null,
    @SerialName("lottie_loop_count") val lottieLoopCount: Int? = null,
    @SerialName("stickman_colour") val stickmanColour: String? = null,
    @SerialName("stickman_scale") val stickmanScale: Float? = null,
    @SerialName("image_file_name") val imageFileName: String? = null,
    @SerialName("image_data") val imageData: String? = null,
    @SerialName("video_file_name") val videoFileName: String? = null,
    @SerialName("video_data") val videoData: String? = null,
    @SerialName("script_content") val scriptContent: String? = null,
)

@Serializable
enum class RotationMode { SEQUENTIAL, SHUFFLE }

@Serializable
enum class TransitionType { HARD_CUT, CROSSFADE, SLIDE, NONE }

@Serializable
enum class TextAnimationType {
    STATIC, TYPING, EXPANDING, FADING, SLIDING,
    WORD_BY_WORD, CHARACTER_WAVE, BOUNCE_IN, RAINBOW_SHIMMER,
    TYPEWRITER_CURSOR, GLITCH, ZOOM_BLUR, SPRING, FLIP_3D,
    SLIDE_LEFT, SLIDE_RIGHT, SLIDE_UP, SLIDE_DOWN,
}

@Serializable
enum class TextColourMode { SINGLE, CYCLING, GRADIENT }

@Serializable
enum class TextPosition { ABOVE_ANIMATION, BELOW_ANIMATION, BESIDE_ANIMATION }

@Serializable
data class AdPackResponse(
    val id: String,
    val version: Int,
    @SerialName("box_height_dp") val boxHeightDp: Int = 30,
    @SerialName("background_colour") val backgroundColour: String? = null,
    @SerialName("entry_duration_ms") val entryDurationMs: Long = 5000,
    @SerialName("rotation_mode") val rotationMode: String = "sequential",
    @SerialName("transition_type") val transitionType: String = "crossfade",
    @SerialName("crossfade_duration_ms") val crossfadeDurationMs: Long = 300,
    @SerialName("slide_duration_ms") val slideDurationMs: Long = 400,
    @SerialName("slide_direction") val slideDirection: String = "left",
    val entries: List<AdEntryResponse> = emptyList()
) {
    fun toDomain(): AdPack = AdPack(
        id = id,
        version = version,
        boxHeightDp = boxHeightDp,
        backgroundColour = backgroundColour,
        entryDurationMs = entryDurationMs,
        rotationMode = runCatching { RotationMode.valueOf(rotationMode.uppercase()) }.getOrDefault(RotationMode.SEQUENTIAL),
        transitionType = runCatching { TransitionType.valueOf(transitionType.uppercase()) }.getOrDefault(TransitionType.CROSSFADE),
        crossfadeDurationMs = crossfadeDurationMs,
        slideDurationMs = slideDurationMs,
        slideDirection = slideDirection,
        entries = entries.sortedBy { it.orderIndex }.map { it.toDomain() }
    )
}

@Serializable
data class AdEntryResponse(
    val id: String,
    @SerialName("lottie_data") val lottieData: String? = null,
    @SerialName("lottie_file_name") val lottieFileName: String = "",
    val slogan: String = "",
    @SerialName("text_animation") val textAnimation: String = "typing",
    @SerialName("text_colour_mode") val textColourMode: String = "single",
    @SerialName("text_colours") val textColours: List<String> = listOf("#DAA520"),
    @SerialName("text_colour_cycle_ms") val textColourCycleMs: Long = 2000,
    @SerialName("font_family") val fontFamily: String? = null,
    @SerialName("font_size_sp") val fontSizeSp: Float = 12f,
    @SerialName("font_weight") val fontWeight: Int = 400,
    @SerialName("letter_spacing") val letterSpacing: Float = 0f,
    @SerialName("text_position") val textPosition: String = "below_animation",
    @SerialName("order_index") val orderIndex: Int = 0,
    @SerialName("animation_config") @Serializable(with = AnimationConfigSerializer::class) val animationConfig: AnimationConfig? = null,
    @SerialName("stickman_colour") val stickmanColour: String? = null,
    @SerialName("stickman_scale") val stickmanScale: Float = 1.0f,
    @SerialName("gradient_direction") val gradientDirection: Float = 0f,
    @SerialName("lottie_loop_mode") val lottieLoopMode: String = "infinite",
    @SerialName("lottie_loop_count") val lottieLoopCount: Int = 1,
    @SerialName("typing_speed_ms") val typingSpeedMs: Long = 80,
    @SerialName("typing_delete_speed_ms") val typingDeleteSpeedMs: Long = 40,
    @SerialName("fade_duration_ms") val fadeDurationMs: Long = 500,
    @SerialName("expand_duration_ms") val expandDurationMs: Long = 800,
    @SerialName("text_slide_duration_ms") val textSlideDurationMs: Long = 400,
    @SerialName("text_x_offset_dp") val textXOffsetDp: Float = 0f,
    @SerialName("text_y_offset_dp") val textYOffsetDp: Float = 0f,
    @SerialName("elements") val elements: List<AdElement> = emptyList(),
) {
    fun toDomain(): AdEntry = AdEntry(
        id = id,
        lottieData = lottieData ?: "",
        lottieFileName = lottieFileName,
        slogan = slogan,
        textAnimation = runCatching { TextAnimationType.valueOf(textAnimation.uppercase()) }.getOrDefault(TextAnimationType.TYPING),
        textColourMode = runCatching { TextColourMode.valueOf(textColourMode.uppercase()) }.getOrDefault(TextColourMode.SINGLE),
        textColours = textColours,
        textColourCycleMs = textColourCycleMs,
        fontFamily = fontFamily,
        fontSizeSp = fontSizeSp,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
        textPosition = runCatching { TextPosition.valueOf(textPosition.uppercase()) }.getOrDefault(TextPosition.BELOW_ANIMATION),
        orderIndex = orderIndex,
        animationConfig = animationConfig,
        stickmanColour = stickmanColour,
        stickmanScale = stickmanScale,
        gradientDirection = gradientDirection,
        lottieLoopMode = lottieLoopMode,
        lottieLoopCount = lottieLoopCount,
        typingSpeedMs = typingSpeedMs,
        typingDeleteSpeedMs = typingDeleteSpeedMs,
        fadeDurationMs = fadeDurationMs,
        expandDurationMs = expandDurationMs,
        textSlideDurationMs = textSlideDurationMs,
        textXOffsetDp = textXOffsetDp,
        textYOffsetDp = textYOffsetDp,
        elements = elements,
    )
}