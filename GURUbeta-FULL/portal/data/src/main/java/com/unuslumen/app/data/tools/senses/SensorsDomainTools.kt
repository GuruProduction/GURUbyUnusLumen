// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.reflect.KClass

/**
 * The sensors domain: raw motion sampling, body readings, activity context, UWB
 * ranging, and background health sampling.
 *
 * THE RULE THAT SHAPES EVERY TOOL HERE. Absent hardware is reported as the device in
 * one sentence and never as a zero reading. A phone with no heart-rate sensor that
 * answers `{ bpm: 0 }` teaches the model that its person has a pulse of zero, which
 * is a fabricated clinical fact. So every tool asks the device first and refuses with
 * the device's own reason when the sensor is not there.
 *
 * ACTIVITY RECOGNITION IS AN HONEST INFERENCE HERE. The real Activity Recognition API
 * lives in Google Play Services, which this project does not depend on. Rather than
 * add that dependency, activityRecognition classifies acceleration magnitude and its
 * variance from the accelerometer, and both its description and its result say so in
 * one sentence. It can separate still from walking from a vehicle; it never reports a
 * Play Services confidence it does not have.
 */

@Serializable
data class SensorStreamResult(
    val success: Boolean,
    val sensor: String = "",
    /** The sensor's own reported maximum rate in Hz, or null when the platform omits it. */
    val maxRateHz: Float? = null,
    /** Rate the stream really achieved, measured from the sensor's own timestamps. */
    val effectiveRateHz: Float? = null,
    val sampleCount: Int = 0,
    val windowSeconds: Int = 0,
    /** Mean magnitude over the window, or null when no sample arrived. */
    val meanMagnitude: Double? = null,
    val minMagnitude: Double? = null,
    val maxMagnitude: Double? = null,
    /** Standard deviation of magnitude: the cheapest honest measure of how much moved. */
    val magnitudeStdDev: Double? = null,
    /** Pitch and roll from gravity, only for the accelerometer. */
    val pitchDegrees: Double? = null,
    val rollDegrees: Double? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BodySensorReading(
    val sensor: String,
    val value: Double? = null,
    val unit: String = "",
    val accuracy: String = "unknown",
) : ToolResultData

@Serializable
data class BodySensorsResult(
    val success: Boolean,
    val readings: List<BodySensorReading> = emptyList(),
    /** Which body sensors this device actually reports, so absence is visible. */
    val availableSensors: List<String> = emptyList(),
    val windowSeconds: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class ActivityRecognitionResult(
    val success: Boolean,
    /** STILL, WALKING, RUNNING, IN_VEHICLE or UNKNOWN. Never a Play Services constant. */
    val activity: String = "UNKNOWN",
    val confidence: Double = 0.0,
    val windowSeconds: Int = 0,
    val sampleCount: Int = 0,
    val meanMagnitude: Double? = null,
    val magnitudeStdDev: Double? = null,
    /** States plainly that this is an inference, so the model cannot over-claim. */
    val method: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class UwbRangeResult(
    val success: Boolean,
    /** Whether the platform reports UWB hardware at all. */
    val uwbPresent: Boolean = false,
    val technologyAvailability: String = "UNKNOWN",
    val supportsDistanceMeasurement: Boolean = false,
    val minRangingIntervalMillis: Long? = null,
    /** A measured distance in metres, or null when no peer answered. */
    val distanceMetres: Double? = null,
    val distanceStdDevMetres: Double? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BackgroundHealthResult(
    val success: Boolean,
    val granted: Boolean,
    val samplesTaken: Int = 0,
    val windowSeconds: Int = 0,
    val readings: List<BodySensorReading> = emptyList(),
    val note: String = "",
    val error: String? = null,
) : ToolResultData

object SensorsDomainDefinitions : ToolSetRegistration {
    const val SENSOR_STREAM = "sensorStream"
    const val BODY_SENSORS = "bodySensors"
    const val BACKGROUND_HEALTH = "backgroundHealth"
    const val ACTIVITY_RECOGNITION = "activityRecognition"
    const val UWB_RANGE = "uwbRange"

    override val definitions = listOf(
        ToolDefinition(
            name = SENSOR_STREAM,
            description = "Sample the accelerometer, gyroscope or magnetometer for a short window and " +
                "return the real achieved sample rate measured from the sensor's own timestamps, plus " +
                "an aggregate: mean, min, max and standard deviation of magnitude, and pitch and roll " +
                "from gravity for the accelerometer. High-rate sampling is a normal permission held " +
                "from install, so no dialog appears. Use it to measure motion rather than guess at it.",
            category = "sensors",
            parameters = listOf(
                ToolParameter(
                    "sensor", ToolParameterType.String, false,
                    "accelerometer, gyroscope or magnetometer. Default accelerometer.",
                    enumValues = listOf("accelerometer", "gyroscope", "magnetometer"),
                ),
                ToolParameter(
                    "windowSeconds", ToolParameterType.Integer, false,
                    "How long to sample, 1 to 30 seconds. Default 3."
                ),
                ToolParameter(
                    "rateHz", ToolParameterType.Integer, false,
                    "Requested sample rate in Hz, 1 to 400. Default 100. The achieved rate is reported " +
                        "separately and is usually lower."
                ),
            ),
            permissions = listOf(TeamThreePermissions.HIGH_SAMPLING_RATE_SENSORS.permission),
        ),
        ToolDefinition(
            name = BODY_SENSORS,
            description = "Read the body sensors this device exposes — heart rate and step count — " +
                "sampled for a short window, and report which of them exist before reporting any " +
                "reading. A phone without a heart-rate sensor gets a sentence about the device rather " +
                "than a zero pulse. Phone body sensors are far coarser than a wearable.",
            category = "sensors",
            parameters = listOf(
                ToolParameter(
                    "windowSeconds", ToolParameterType.Integer, false,
                    "How long to sample, 1 to 30 seconds. Default 5."
                ),
            ),
            permissions = listOf(TeamThreePermissions.BODY_SENSORS.permission),
        ),
        ToolDefinition(
            name = BACKGROUND_HEALTH,
            description = "Read body sensors on the background grant, so a sample is permitted while " +
                "GURU is not on screen. This is the mode Android gates behind \"Allow all the time\", " +
                "granted only as a second step after the foreground body-sensors permission, so it " +
                "refuses until that step is done. Reports which sensors exist first, because no grant " +
                "creates one.",
            category = "sensors",
            parameters = listOf(
                ToolParameter(
                    "windowSeconds", ToolParameterType.Integer, false,
                    "How long to sample, 1 to 60 seconds. Default 10."
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.BODY_SENSORS.permission,
                TeamThreePermissions.BODY_SENSORS_BACKGROUND.permission,
            ),
        ),
        ToolDefinition(
            name = ACTIVITY_RECOGNITION,
            description = "Say whether the person is still, walking, running or in a vehicle, with a " +
                "confidence. THIS IS AN INFERENCE FROM THE ACCELEROMETER, NOT THE ACTIVITY RECOGNITION " +
                "API: this build does not depend on Google Play Services, which is where the real " +
                "recogniser lives. It reads acceleration magnitude and its variance over a window, so " +
                "it separates still from walking from a vehicle, and it says so in its result rather " +
                "than claiming a recognised activity it has not got.",
            category = "sensors",
            parameters = listOf(
                ToolParameter(
                    "windowSeconds", ToolParameterType.Integer, false,
                    "How long to sample before deciding, 2 to 30 seconds. Default 5."
                ),
            ),
            permissions = listOf(TeamThreePermissions.ACTIVITY_RECOGNITION.permission),
        ),
        ToolDefinition(
            name = UWB_RANGE,
            description = "Report whether this device has ultra-wideband hardware, what the platform's " +
                "ranging service says the radio can do, and any distance a live session yielded. UWB " +
                "is absent on most phones, and an absent radio is reported as the device in one " +
                "sentence rather than as a failed measurement. A distance needs a second UWB device " +
                "whose address was exchanged out of band, so without one the answer is the capability.",
            category = "sensors",
            parameters = listOf(
                ToolParameter(
                    "windowSeconds", ToolParameterType.Integer, false,
                    "How long to wait for the capabilities report, 1 to 15 seconds. Default 3."
                ),
            ),
            permissions = listOf(TeamThreePermissions.UWB_RANGING.permission),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = SensorsDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

/** One sensor event: the three axes and the sensor's own timestamp, kept as a Long. */
private class Sample(val x: Float, val y: Float, val z: Float, val timestampNanos: Long) {
    val magnitude: Double get() = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
}

/**
 * The executor behind every sensor tool.
 *
 * One sampling helper serves the motion and activity tools, so the timestamps and the
 * aggregate maths cannot disagree between them. Every tool asks the device before the
 * grant where the hardware is what can be missing, and the device sentence comes from
 * PermissionGrants.guardHardware so it reads the same as every other hardware refusal
 * in the app.
 */
class SensorsDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val sensorManager: SensorManager?
        get() = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SensorsDomainDefinitions.SENSOR_STREAM -> sensorStream(args)
        SensorsDomainDefinitions.BODY_SENSORS -> bodySensors(args)
        SensorsDomainDefinitions.BACKGROUND_HEALTH -> backgroundHealth(args)
        SensorsDomainDefinitions.ACTIVITY_RECOGNITION -> activityRecognition(args)
        SensorsDomainDefinitions.UWB_RANGE -> uwbRange(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    // ------------------------------------------------------------------
    // Raw motion
    // ------------------------------------------------------------------

    private suspend fun sensorStream(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.HIGH_SAMPLING_RATE_SENSORS)?.let { return it }

        val manager = sensorManager
            ?: return sensorStreamFailure("This device has no SensorManager service, so it has no motion sensors.")

        val requested = (args["sensor"] as? String)?.lowercase() ?: "accelerometer"
        val (label, type) = when (requested) {
            "accelerometer" -> "accelerometer" to Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> "gyroscope" to Sensor.TYPE_GYROSCOPE
            "magnetometer" -> "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD
            else -> return sensorStreamFailure(
                "Unknown sensor '$requested'. Use accelerometer, gyroscope or magnetometer."
            )
        }

        val sensor = manager.getDefaultSensor(type)
            ?: return PermissionGrants.guardHardware(
                present = false,
                label = label,
                why = "GURU can measure $label readings on this phone",
            ) ?: return sensorStreamFailure("No $label on this device.")

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 3).coerceIn(1, 30)
        val rateHz = ((args["rateHz"] as? Number)?.toInt() ?: 100).coerceIn(1, 400)

        val samples = sample(manager, sensor, windowSeconds, rateHz)
        val magnitudes = samples.map { it.magnitude }
        val effectiveRate = effectiveRateHz(samples)
        val (pitch, roll) = if (type == Sensor.TYPE_ACCELEROMETER && samples.isNotEmpty()) {
            pitchRoll(samples)
        } else {
            null to null
        }

        val r = SensorStreamResult(
            success = samples.isNotEmpty(),
            sensor = label,
            // maxDelay is the longest gap between events in microseconds, so its
            // reciprocal is the sensor's own slowest guaranteed rate ceiling.
            maxRateHz = runCatching { sensor.maxDelay }.getOrNull()?.takeIf { it > 0 }?.let { 1_000_000f / it },
            effectiveRateHz = effectiveRate,
            sampleCount = samples.size,
            windowSeconds = windowSeconds,
            meanMagnitude = magnitudes.averageOrNull(),
            minMagnitude = magnitudes.minOrNull(),
            maxMagnitude = magnitudes.maxOrNull(),
            magnitudeStdDev = magnitudes.stdDevOrNull(),
            pitchDegrees = pitch,
            rollDegrees = roll,
            note = if (samples.isEmpty()) {
                "The sensor is listed but delivered no samples in $windowSeconds seconds, which is a " +
                    "device or sensor-service state rather than a permission."
            } else {
                "Sampled at an achieved ${effectiveRate ?: 0f} Hz over $windowSeconds seconds. The " +
                    "requested $rateHz Hz is a ceiling the platform may cap, and the achieved rate is " +
                    "measured from the sensor's own event timestamps rather than assumed."
            },
            error = null,
        )
        return emitPayload(json, json.encodeToString(SensorStreamResult.serializer(), r), r, r.success, r.error)
    }

    private fun sensorStreamFailure(message: String): ToolExecutionResult {
        val r = SensorStreamResult(success = false, error = message)
        return emitPayload(json, json.encodeToString(SensorStreamResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Collect raw samples for a window, keeping the sensor's own event timestamp.
     *
     * The sensor clock, not the callback's wall clock, is what the achieved rate is
     * computed from. Using wall clock would measure the dispatcher rather than the
     * sensor and would flatter a slow stream.
     */
    private suspend fun sample(
        manager: SensorManager,
        sensor: Sensor,
        windowSeconds: Int,
        rateHz: Int,
    ): List<Sample> {
        val collected = mutableListOf<Sample>()
        val microsPerEvent = (1_000_000L / rateHz.coerceAtLeast(1)).toInt()

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                collected.add(
                    Sample(
                        x = event.values.getOrElse(0) { 0f },
                        y = event.values.getOrElse(1) { 0f },
                        z = event.values.getOrElse(2) { 0f },
                        timestampNanos = event.timestamp,
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        val registered = manager.registerListener(
            listener, sensor, microsPerEvent, Handler(Looper.getMainLooper())
        )
        if (!registered) return emptyList()

        try {
            // There is no completion signal for a continuous sensor, so the window
            // itself is the wait.
            suspendCancellableCoroutine<Unit> { continuation ->
                val handler = Handler(Looper.getMainLooper())
                val finish = Runnable { if (continuation.isActive) continuation.resume(Unit) }
                handler.postDelayed(finish, windowSeconds * 1000L)
                continuation.invokeOnCancellation { handler.removeCallbacks(finish) }
            }
        } finally {
            runCatching { manager.unregisterListener(listener) }
        }
        return collected
    }

    /** Achieved rate from the first and last sensor timestamps, not from wall clock. */
    private fun effectiveRateHz(samples: List<Sample>): Float? {
        if (samples.size < 2) return null
        val spanNanos = samples.last().timestampNanos - samples.first().timestampNanos
        if (spanNanos <= 0L) return null
        return ((samples.size - 1).toDouble() / (spanNanos / 1_000_000_000.0)).toFloat()
    }

    /** Pitch and roll from gravity, in degrees. */
    private fun pitchRoll(samples: List<Sample>): Pair<Double?, Double?> {
        val n = samples.size.toDouble()
        val meanX = samples.sumOf { it.x.toDouble() } / n
        val meanY = samples.sumOf { it.y.toDouble() } / n
        val meanZ = samples.sumOf { it.z.toDouble() } / n
        val pitch = Math.toDegrees(atan2(-meanX, sqrt(meanY * meanY + meanZ * meanZ)))
        val roll = Math.toDegrees(atan2(meanY, meanZ))
        return pitch to roll
    }

    // ------------------------------------------------------------------
    // Body sensors
    // ------------------------------------------------------------------

    private suspend fun bodySensors(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.BODY_SENSORS)?.let { return it }

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 5).coerceIn(1, 30)

        val available = availableBodySensorNames()
        if (available.isEmpty()) {
            return PermissionGrants.guardHardware(
                present = false,
                label = "body sensor",
                why = "GURU can read your heart rate and step count",
            ) ?: run {
                val r = BodySensorsResult(
                    success = false,
                    windowSeconds = windowSeconds,
                    error = "This device has no body sensors.",
                )
                // The success flag follows the payload, never the fact that
                // serialising worked: a refusal is a refusal whatever its JSON says elsewhere.
                emitPayload(json, json.encodeToString(BodySensorsResult.serializer(), r), r, r.success, r.error)
            }
        }

        val read = readBodySensorsAsync(windowSeconds)
        val r = BodySensorsResult(
            success = read.readings.any { it.value != null },
            readings = read.readings,
            availableSensors = available,
            windowSeconds = windowSeconds,
            note = "Phone body sensors are far coarser than a wearable; a heart rate here is a spot " +
                "reading taken while a finger or wrist covers the sensor, not a continuous trace.",
            error = read.error,
        )
        return emitPayload(json, json.encodeToString(BodySensorsResult.serializer(), r), r, r.success, r.error)
    }

    private suspend fun backgroundHealth(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.BODY_SENSORS)?.let { return it }
        PermissionGrants.guard(context, TeamThreePermissions.BODY_SENSORS_BACKGROUND)?.let { return it }

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 10).coerceIn(1, 60)
        val available = availableBodySensorNames()

        if (available.isEmpty()) {
            val r = BackgroundHealthResult(
                success = false,
                granted = true,
                windowSeconds = windowSeconds,
                note = "Background body sampling is granted, but this device exposes neither a " +
                    "heart-rate sensor nor a step counter, so there is nothing to sample in the " +
                    "background or in the foreground.",
                error = "This device has no body sensors.",
            )
            return emitPayload(json, json.encodeToString(BackgroundHealthResult.serializer(), r), r, r.success, r.error)
        }

        val read = readBodySensorsAsync(windowSeconds)
        val r = BackgroundHealthResult(
            success = read.readings.any { it.value != null },
            granted = true,
            samplesTaken = read.readings.count { it.value != null },
            windowSeconds = windowSeconds,
            readings = read.readings,
            note = "Sampled over $windowSeconds seconds while the background body-sensors grant is " +
                "held. The read runs inside the GURU process; what the background grant buys is " +
                "Android permitting it while the app is not on screen, not a process of its own.",
            error = read.error,
        )
        return emitPayload(json, json.encodeToString(BackgroundHealthResult.serializer(), r), r, r.success, r.error)
    }

    private data class BodyRead(val readings: List<BodySensorReading>, val error: String?)

    private fun availableBodySensorNames(): List<String> {
        val manager = sensorManager ?: return emptyList()
        val out = mutableListOf<String>()
        if (manager.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null) out.add("heartRate")
        if (manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null) out.add("stepCounter")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            manager.getDefaultSensor(Sensor.TYPE_HEART_BEAT) != null
        ) {
            out.add("heartBeat")
        }
        return out
    }

    private suspend fun readBodySensorsAsync(windowSeconds: Int): BodyRead =
        withContext(Dispatchers.IO) { readBodySensors(windowSeconds) }

    /**
     * One reading per available body sensor, waiting up to the window for each.
     *
     * Heart rate delivers a value only while something is held against the sensor, so
     * a window with no event is reported as no reading rather than as zero. Steps are
     * cumulative since boot and are reported exactly as the sensor states them.
     *
     * This blocks the calling thread deliberately: the whole read is a short bounded
     * wait on a hardware callback, and suspending across the latch buys nothing but a
     * second thread. Callers on a coroutine dispatcher go through the async wrapper.
     */
    private fun readBodySensors(windowSeconds: Int): BodyRead {
        val manager = sensorManager ?: return BodyRead(emptyList(), "This device has no SensorManager service.")

        val heart = manager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val steps = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val readings = mutableListOf<BodySensorReading>()
        var error: String? = null

        if (heart != null) {
            val latch = CountDownLatch(1)
            var value: Double? = null
            var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (value == null) {
                        value = event.values.getOrNull(0)?.toDouble()
                        accuracy = event.accuracy
                        latch.countDown()
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }

            if (manager.registerListener(listener, heart, SensorManager.SENSOR_DELAY_NORMAL)) {
                try {
                    latch.await(windowSeconds.toLong(), TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Falling through with no value is the honest outcome.
                } finally {
                    runCatching { manager.unregisterListener(listener) }
                }
            }

            readings.add(
                BodySensorReading(
                    sensor = "heartRate",
                    value = value,
                    unit = "bpm",
                    accuracy = accuracyName(accuracy),
                )
            )
            if (value == null) {
                error = "The heart-rate sensor delivered no value in $windowSeconds seconds. It reports " +
                    "only while a finger or wrist covers it, so this is no reading rather than a rate " +
                    "of zero."
            }
        }

        if (steps != null) {
            val latch = CountDownLatch(1)
            var value: Double? = null

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (value == null) {
                        value = event.values.getOrNull(0)?.toDouble()
                        latch.countDown()
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }

            if (manager.registerListener(listener, steps, SensorManager.SENSOR_DELAY_NORMAL)) {
                try {
                    latch.await(windowSeconds.toLong(), TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // As above: no value is reported rather than a made-up count.
                } finally {
                    runCatching { manager.unregisterListener(listener) }
                }
            }

            readings.add(
                BodySensorReading(
                    sensor = "stepCounter",
                    value = value,
                    unit = "steps since boot",
                    accuracy = "cumulative",
                )
            )
        }

        return BodyRead(readings, error)
    }

    private fun accuracyName(accuracy: Int): String = when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "high"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "medium"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "low"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "unreliable"
        else -> "unknown"
    }

    // ------------------------------------------------------------------
    // Activity, inferred from the accelerometer
    // ------------------------------------------------------------------

    private suspend fun activityRecognition(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.ACTIVITY_RECOGNITION)?.let { return it }

        val manager = sensorManager
            ?: return activityFailure("This device has no SensorManager service, so motion cannot be sampled.")

        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return PermissionGrants.guardHardware(
                present = false,
                label = "accelerometer",
                why = "GURU can tell whether you are still or moving",
            ) ?: return activityFailure("No accelerometer on this device.")

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 5).coerceIn(2, 30)
        val samples = sample(manager, accelerometer, windowSeconds, 50)

        if (samples.isEmpty()) {
            return activityFailure(
                "The accelerometer delivered no samples in $windowSeconds seconds, so no activity can " +
                    "be inferred; that is a device state rather than a permission."
            )
        }

        val magnitudes = samples.map { it.magnitude }
        val mean = magnitudes.average()
        val stdDev = magnitudes.stdDev()
        val (activity, confidence) = classify(mean, stdDev)

        val r = ActivityRecognitionResult(
            success = true,
            activity = activity,
            confidence = confidence,
            windowSeconds = windowSeconds,
            sampleCount = samples.size,
            meanMagnitude = mean,
            magnitudeStdDev = stdDev,
            method = "Inferred from the accelerometer (${samples.size} samples, standard deviation of " +
                "acceleration magnitude). This is NOT the Activity Recognition API: that lives in " +
                "Google Play Services, which this build does not depend on, so no recognised-activity " +
                "confidence from Google's classifier exists here.",
        )
        return emitPayload(json, json.encodeToString(ActivityRecognitionResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Magnitude and variance into a coarse activity guess.
     *
     * The thresholds are empirical and deliberately coarse: gravity is 9.81, a phone
     * lying still varies by a few hundredths, a person walking swings the magnitude by
     * roughly 0.5 to 2, and running swings it further. Confidence is derived from how
     * far the variance sits past its threshold rather than being a fixed number, so a
     * marginal reading reports itself as marginal.
     */
    private fun classify(meanMagnitude: Double, stdDev: Double): Pair<String, Double> {
        val gravity = SensorManager.GRAVITY_EARTH.toDouble()
        // A phone in free fall or held at an unusual angle reads far from 1g, so the
        // magnitude is compared against gravity before anything else.
        val deviation = abs(meanMagnitude - gravity)

        return when {
            stdDev < 0.15 && deviation < 0.6 -> "STILL" to (1.0 - stdDev / 0.15).coerceIn(0.3, 0.95)
            stdDev < 0.6 -> "WALKING" to (0.4 + stdDev / 0.6 * 0.4).coerceIn(0.3, 0.85)
            stdDev < 2.5 -> "RUNNING" to (0.3 + (stdDev - 0.6) / 1.9 * 0.4).coerceIn(0.25, 0.7)
            else -> "IN_VEHICLE" to 0.25
        }
    }

    private fun activityFailure(message: String): ToolExecutionResult {
        val r = ActivityRecognitionResult(
            success = false,
            method = "Inferred from the accelerometer; no reading was taken, so no activity is claimed.",
            error = message,
        )
        return emitPayload(json, json.encodeToString(ActivityRecognitionResult.serializer(), r), r, r.success, r.error)
    }

    // ------------------------------------------------------------------
    // UWB
    // ------------------------------------------------------------------

    /**
     * UWB capability, and the honest statement of why no distance exists.
     *
     * THE API CHANGED. Android 16 introduced `android.ranging.RangingManager` as the
     * single entry point for UWB, BLE-CS, Wi-Fi RTT and Wi-Fi PD, and it is the only
     * one present in the SDK this project compiles against. The older
     * `android.uwb.UwbManager` is not in this public SDK, so the ranging service is what
     * this tool talks to.
     *
     * WHAT THIS TOOL DOES NOT DO. It does not invent a peer. A ranging session needs a
     * second device with the right role and address, exchanged out of band, so with no
     * peer configured it reports the capability, the technology availability and the
     * absence of a measurement, which are the three facts it can honestly know.
     */
    private suspend fun uwbRange(args: Map<String, Any?>): ToolExecutionResult {
        if (!PermissionGrants.hasUwb(context)) {
            // guardHardware returns null when the capability IS present, so the null
            // branch has to exist for the type to be a result. The check above
            // guarantees the refusal, so the elvis is unreachable and states that in
            // the only way the compiler accepts.
            return PermissionGrants.guardHardware(
                present = false,
                label = "UWB radio",
                why = "GURU can measure distance to a UWB device to within centimetres",
            ) ?: PermissionGrants.refusal(
                PermissionGrants.absentHardware(
                    label = "UWB radio",
                    why = "GURU can measure distance to a UWB device to within centimetres",
                )
            )
        }

        PermissionGrants.guard(context, TeamThreePermissions.UWB_RANGING)?.let { return it }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            val r = UwbRangeResult(
                success = false,
                uwbPresent = true,
                technologyAvailability = "RANGING_SERVICE_UNAVAILABLE",
                note = "This phone reports UWB hardware, but the unified ranging service arrived in " +
                    "Android 16 (API 36) and this device's platform is older, so this build has no " +
                    "public API to range with.",
                error = "UWB ranging needs Android 16 or newer on this build.",
            )
            return emitPayload(json, json.encodeToString(UwbRangeResult.serializer(), r), r, r.success, r.error)
        }

        val windowSeconds = ((args["windowSeconds"] as? Number)?.toInt() ?: 3).coerceIn(1, 15)
        return UwbRangingProbe(context, windowSeconds).read()
    }
}

/**
 * The ranging-service probe, isolated in its own class.
 *
 * WHY A SEPARATE CLASS AND NOT A PRIVATE FUNCTION. `android.ranging` does not exist
 * below API 36, and a verifier that resolves this class's references on an older
 * device would fail before any runtime guard ran. Keeping every API-36 symbol inside
 * one class that is only loaded behind an SDK check means an older phone never loads
 * it at all, which is the only shape that is safe without a build-time check.
 *
 * The annotation is written as a comment rather than `@RequiresApi` because the data
 * module does not declare androidx.annotation directly, and adding a dependency to
 * satisfy a documentation annotation would be the tail wagging the dog.
 */
private class UwbRangingProbe(
    private val context: Context,
    private val windowSeconds: Int,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Run on a direct executor: the callback only completes a deferred. */
    private val directExecutor = Executor { command -> command.run() }

    suspend fun read(): ToolExecutionResult {
        val manager = runCatching {
            context.getSystemService(android.ranging.RangingManager::class.java)
        }.getOrNull()

        if (manager == null) {
            return result(
                UwbRangeResult(
                    success = false,
                    uwbPresent = true,
                    technologyAvailability = "NO_RANGING_SERVICE",
                    note = "This phone reports UWB hardware but exposes no ranging service, so nothing " +
                        "can range with it on this platform build.",
                    error = "The ranging service is not present on this device.",
                )
            )
        }

        val capabilities = CompletableDeferred<android.ranging.RangingCapabilities?>()
        val callback = object : android.ranging.RangingManager.RangingCapabilitiesCallback {
            override fun onRangingCapabilities(capabilitiesReceived: android.ranging.RangingCapabilities) {
                if (!capabilities.isCompleted) capabilities.complete(capabilitiesReceived)
            }
        }

        val registered = runCatching {
            manager.registerCapabilitiesCallback(directExecutor, callback)
            true
        }.getOrDefault(false)

        if (!registered) {
            return result(
                UwbRangeResult(
                    success = false,
                    uwbPresent = true,
                    technologyAvailability = "REGISTRATION_REFUSED",
                    note = "The ranging service refused a capabilities callback, which means the grant " +
                        "is held but the platform will not range for this app.",
                    error = "The ranging service refused the capability query.",
                )
            )
        }

        val received = try {
            withTimeoutOrNull(windowSeconds * 1000L) { capabilities.await() }
        } finally {
            runCatching { manager.unregisterCapabilitiesCallback(callback) }
        }

        if (received == null) {
            return result(
                UwbRangeResult(
                    success = false,
                    uwbPresent = true,
                    technologyAvailability = "NO_CAPABILITIES_REPORTED",
                    note = "The ranging service accepted the callback but reported no capabilities " +
                        "within $windowSeconds seconds, so the radio's state cannot be stated.",
                    error = "No ranging capabilities were reported.",
                )
            )
        }

        val availability = received.technologyAvailability[android.ranging.RangingManager.UWB]
        val uwbCapabilities = runCatching { received.uwbCapabilities }.getOrNull()
        val availabilityName = when (availability) {
            android.ranging.RangingCapabilities.ENABLED -> "ENABLED"
            android.ranging.RangingCapabilities.DISABLED_REGULATORY -> "DISABLED_REGULATORY"
            android.ranging.RangingCapabilities.DISABLED_USER -> "DISABLED_USER"
            android.ranging.RangingCapabilities.DISABLED_USER_RESTRICTIONS -> "DISABLED_USER_RESTRICTIONS"
            android.ranging.RangingCapabilities.NOT_SUPPORTED -> "NOT_SUPPORTED"
            null -> "NOT_REPORTED"
            else -> "UNKNOWN($availability)"
        }

        val distanceSupported = uwbCapabilities?.isDistanceMeasurementSupported == true
        val interval = runCatching { uwbCapabilities?.minimumRangingInterval?.toMillis() }.getOrNull()

        return result(
            UwbRangeResult(
                success = availabilityName == "ENABLED",
                uwbPresent = true,
                technologyAvailability = availabilityName,
                supportsDistanceMeasurement = distanceSupported,
                minRangingIntervalMillis = interval,
                note = if (availabilityName == "ENABLED") {
                    "The UWB radio is enabled and this device can range. No peer is configured, and a " +
                        "ranging session needs a second device's address exchanged out of band, so no " +
                        "distance is reported rather than a fabricated one."
                } else {
                    "The UWB radio is present but not ranging: the platform reports $availabilityName. " +
                        "A regulatory or user setting is the cause, not a permission and not this app."
                },
                error = if (availabilityName == "ENABLED") null else "UWB ranging is not currently available.",
            )
        )
    }

    private fun result(value: UwbRangeResult): ToolExecutionResult =
        // The success flag follows the payload, never the fact that
        // serialising worked: a refusal is a refusal whatever its JSON says elsewhere.
        emitPayload(json, json.encodeToString(UwbRangeResult.serializer(), value), value, value.success, value.error)
}

/** Mean of a list, or null when it is empty rather than NaN or zero. */
private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

/** Sample standard deviation, or null when there are fewer than two values. */
private fun List<Double>.stdDevOrNull(): Double? = if (size < 2) null else stdDev()

private fun List<Double>.stdDev(): Double {
    if (size < 2) return 0.0
    val mean = average()
    val variance = sumOf { (it - mean) * (it - mean) } / (size - 1)
    return sqrt(variance)
}

/**
 * The sensors domain's wiring.
 */
object SensorsDomainModule : DomainToolModule {
    override val id: String = "sensors"

    override val registrations: List<ToolSetRegistration> = listOf(SensorsDomainDefinitions)

    override fun bindings(): Module = module {
        factory { SensorsDomainExecutor(get()) }
    }
}
