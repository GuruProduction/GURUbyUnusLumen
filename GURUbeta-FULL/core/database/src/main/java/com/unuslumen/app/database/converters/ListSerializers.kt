package com.unuslumen.app.database.converters

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Serializer for List<Float> stored as JSON arrays in the database.
 */
object FloatListSerializer : KSerializer<List<Float>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FloatList", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<Float>) {
        val jsonArray = buildJsonArray {
            value.forEach { add(JsonPrimitive(it)) }
        }
        (encoder as JsonEncoder).encodeJsonElement(jsonArray)
    }

    override fun deserialize(decoder: Decoder): List<Float> {
        val jsonInput = decoder as JsonDecoder
        val element = jsonInput.decodeJsonElement()
        return element.jsonArray.map { it.jsonPrimitive.content.toFloat() }
    }
}

/**
 * Serializer for List<String> stored as JSON arrays in the database.
 */
object StringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringList", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<String>) {
        val jsonArray = buildJsonArray {
            value.forEach { add(JsonPrimitive(it)) }
        }
        (encoder as JsonEncoder).encodeJsonElement(jsonArray)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val jsonInput = decoder as JsonDecoder
        val element = jsonInput.decodeJsonElement()
        return element.jsonArray.map { it.jsonPrimitive.content }
    }
}