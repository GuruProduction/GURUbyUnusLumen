package com.unuslumen.app.adspace

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer for AnimationConfig that handles both JSON objects and JSON strings.
 *
 * The server stores animation_config as a jsonb column in PostgreSQL. When serializing
 * the response, it may output the value as either:
 * 1. A nested JSON object: {"type":"custom","stickmanColour":"#DAA520",...}
 * 2. A JSON string containing escaped JSON: "{\"type\":\"custom\",\"stickmanColour\":\"#DAA520\",...}"
 *
 * This serializer accepts both forms and deserializes them into AnimationConfig.
 */
object AnimationConfigSerializer : KSerializer<AnimationConfig?> {
    private val json = Json { ignoreUnknownKeys = true }
    private val delegate = AnimationConfig.serializer()

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("AnimationConfig")

    override fun serialize(encoder: Encoder, value: AnimationConfig?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }
        delegate.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): AnimationConfig? {
        val jsonDecoder = decoder as? JsonDecoder ?: return null
        val element = jsonDecoder.decodeJsonElement()

        if (element is JsonPrimitive && element.isString) {
            val rawJson = element.contentOrNull ?: return null
            return runCatching {
                json.decodeFromString(delegate, rawJson)
            }.getOrNull()
        }

        if (element is JsonObject) {
            return runCatching {
                json.decodeFromJsonElement(delegate, element)
            }.getOrNull()
        }

        return null
    }
}