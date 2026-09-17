package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object EncryptionToolDefinitions : ToolSetRegistration {
    const val ENCRYPT_AES = "encryptAes"
    const val DECRYPT_AES = "decryptAes"
    const val GENERATE_AES_KEY = "generateAesKey"
    const val HASH_DATA = "hashData"
    const val BASE64_ENCODE = "base64Encode"
    const val BASE64_DECODE = "base64Decode"
    const val ENCRYPT_FILE = "encryptFile"
    const val DECRYPT_FILE = "decryptFile"

    override val definitions = listOf(
        ToolDefinition(name = ENCRYPT_AES, description = "Encrypt data using AES-256-GCM. Returns Base64-encoded ciphertext with IV prepended.", category = "encryption", parameters = listOf(ToolParameter("data", ToolParameterType.String, true, "Plaintext data"), ToolParameter("keyBase64", ToolParameterType.String, true, "Base64-encoded 256-bit key")), permissions = emptyList()),
        ToolDefinition(name = DECRYPT_AES, description = "Decrypt data encrypted with encryptAes.", category = "encryption", parameters = listOf(ToolParameter("encryptedBase64", ToolParameterType.String, true, "Base64-encoded ciphertext"), ToolParameter("keyBase64", ToolParameterType.String, true, "Base64-encoded key")), permissions = emptyList()),
        ToolDefinition(name = GENERATE_AES_KEY, description = "Generate a random AES-256 key. Returns Base64-encoded key.", category = "encryption", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HASH_DATA, description = "Hash data using SHA-256, SHA-512, or MD5. Returns hex-encoded hash.", category = "encryption", parameters = listOf(ToolParameter("data", ToolParameterType.String, true, "Data to hash"), ToolParameter("algorithm", ToolParameterType.String, false, "Algorithm: SHA-256, SHA-512, or MD5")), permissions = emptyList()),
        ToolDefinition(name = BASE64_ENCODE, description = "Base64 encode data.", category = "encryption", parameters = listOf(ToolParameter("data", ToolParameterType.String, true, "Data to encode")), permissions = emptyList()),
        ToolDefinition(name = BASE64_DECODE, description = "Base64 decode data.", category = "encryption", parameters = listOf(ToolParameter("data", ToolParameterType.String, true, "Base64 data")), permissions = emptyList()),
        ToolDefinition(name = ENCRYPT_FILE, description = "Encrypt a file using AES-256-GCM.", category = "encryption", parameters = listOf(ToolParameter("inputPath", ToolParameterType.String, true, "Input file path"), ToolParameter("outputPath", ToolParameterType.String, true, "Output file path"), ToolParameter("keyBase64", ToolParameterType.String, true, "Base64 key")), permissions = emptyList()),
        ToolDefinition(name = DECRYPT_FILE, description = "Decrypt a file encrypted with encryptFile.", category = "encryption", parameters = listOf(ToolParameter("inputPath", ToolParameterType.String, true, "Encrypted file"), ToolParameter("outputPath", ToolParameterType.String, true, "Output path"), ToolParameter("keyBase64", ToolParameterType.String, true, "Base64 key")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = EncryptionToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}