// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CredentialVault — Per-service secrets sealed by Android Keystore hardware keys.
 *
 * Architecture, full strength no corners:
 *  1. One AES-256 key per service alias lives inside the Android Keystore. The key
 *     material is generated and used entirely within the device's secure hardware
 *     (TEE/StrongBox class). It cannot be read by the app, by root, or by copying
 *     any file — nothing that holds the key exists outside the silicon.
 *  2. Every write seals fresh: a new IV per call (randomised encryption required by
 *     the Keystore spec) plus AES-GCM ciphertext. A stolen envelope file is worthless
 *     without the hardware key bound to this device's Keystore instance.
 *  3. Envelopes live in the app's private filesDir (per-UID sandbox, no other app,
 *     no shared storage ever).
 *  4. Reads unseal transiently in-memory for the single API call that needs them and
 *     are never persisted anywhere else, logged, or echoed.
 *
 * Slots cover the services GURU connects with human-approved credentials, replacing
 * the dead environment-variable auth Android never had: GitHub, Trello, Notion.
 */
object CredentialVault {
    private const val TAG = "guru"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val VAULT_DIR = "credential_vault"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    /** Registered service slots, one Keystore alias + one envelope file each. */
    const val SERVICE_GITHUB = "github"
    const val SERVICE_GITHUB_REFRESH = "github_refresh"
    const val SERVICE_TRELLO = "trello"
    const val SERVICE_NOTION = "notion"

    private fun vaultDir(context: Context): File {
        val dir = File(context.filesDir, VAULT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(context: Context, service: String): File =
        File(vaultDir(context), "$service.bin")

    /** Prefixed alias namespace so this app's Keystore entries can never collide. */
    private fun aliasFor(service: String): String = "guru_cred_$service"

    /**
     * The Keystore-backed key for a service, generated on first use inside the
     * hardware boundary. AES-256/GCM matching the envelope cipher.
     */
    private fun getOrCreateKey(service: String): SecretKey {
        val alias = aliasFor(service)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val existing = runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Seal and store a service secret. Called only by login flows holding a real
     * credential from an authentic handshake. Overwrites any prior slot content.
     */
    fun store(context: Context, service: String, secret: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(service))
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "Unexpected GCM IV length: ${iv.size}" }
            val sealed = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))

            val envelope = ByteArray(iv.size + sealed.size)
            iv.copyInto(envelope, 0)
            sealed.copyInto(envelope, iv.size)

            fileFor(context, service).writeBytes(envelope)
            Log.d(TAG, "CredentialVault: $service secret sealed (${envelope.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "CredentialVault: failed to seal $service secret: ${e.message}", e)
            throw e
        }
    }

    /**
     * Unseal and return the service secret, or null when none is stored. Keystore
     * authentication errors and tamper errors (AEADBadTag) both read to the caller
     * as "secret unavailable / not logged in" — state is never faked.
     */
    fun retrieve(context: Context, service: String): String? {
        val envelopeFile = fileFor(context, service)
        if (!envelopeFile.exists()) return null
        return try {
            val envelope = envelopeFile.readBytes()
            if (envelope.size <= IV_LENGTH) return null
            ivSlice(envelope)?.let { iv ->
                val ciphertext = envelope.copyOfRange(IV_LENGTH, envelope.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(service), GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }
        } catch (e: android.security.keystore.UserNotAuthenticatedException) {
            Log.w(TAG, "CredentialVault: $service unlock requires user authentication: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "CredentialVault: $service unseal failed (tampered or Keystore reset): ${e.message}")
            null
        }
    }

    private fun ivSlice(envelope: ByteArray): ByteArray? =
        if (envelope.size > IV_LENGTH) envelope.copyOfRange(0, IV_LENGTH) else null

    /** Does this service hold any sealed secret? File-existence check, no unsealing. */
    fun has(context: Context, service: String): Boolean = fileFor(context, service).exists()

    /** Wipe a service slot completely: the envelope file and its Keystore key. */
    fun delete(context: Context, service: String) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val alias = aliasFor(service)
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (e: Exception) {
            Log.w(TAG, "CredentialVault: $service Keystore entry could not be dropped: ${e.message}")
        }
        val envelopeFile = fileFor(context, service)
        if (envelopeFile.exists()) envelopeFile.delete()
        Log.d(TAG, "CredentialVault: $service slot fully wiped")
    }
}