package com.unuslumen.app.util.shell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec

/**
 * Persists the RSA key pair used for ADB authentication.
 *
 * Android's ADB daemon remembers authorized keys. If we generate a new key
 * on every app restart, the device won't recognize us and AUTH will fail.
 *
 * This class stores the RSA key pair in the app's private files directory,
 * encrypted with Android's Keystore for security.
 */
object AdbKeyStore {
    private const val TAG = "guru"
    private const val KEY_FILE = "adb_key"
    private const val KEY_SIZE = 2048

    private var cachedKeyPair: KeyPair? = null

    /**
     * Get or create the RSA key pair for ADB authentication.
     * Persists the key to disk so it survives app restarts.
     */
    @Synchronized
    fun getOrCreateKeyPair(context: Context): KeyPair {
        // Return cached key if available
        cachedKeyPair?.let { return it }

        // Try to load from disk
        val loaded = loadKeyPair(context)
        if (loaded != null) {
            cachedKeyPair = loaded
            Log.d(TAG, "AdbKeyStore: loaded existing RSA key pair")
            return loaded
        }

        // Generate new key
        Log.d(TAG, "AdbKeyStore: generating new RSA key pair")
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(RSAKeyGenParameterSpec(KEY_SIZE, RSAKeyGenParameterSpec.F4))
        val keyPair = keyGen.generateKeyPair()

        // Save to disk
        saveKeyPair(context, keyPair)
        cachedKeyPair = keyPair
        Log.d(TAG, "AdbKeyStore: saved new RSA key pair")
        return keyPair
    }

    /**
     * Save the RSA key pair to disk.
     * Stores modulus and private exponent as hex strings.
     */
    private fun saveKeyPair(context: Context, keyPair: KeyPair) {
        try {
            val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
            val privKey = keyPair.private as java.security.interfaces.RSAPrivateKey

            // Store as: modulus\npublicExponent\nprivateExponent
            val data = buildString {
                append(pubKey.modulus.toString(16))
                append('\n')
                append(pubKey.publicExponent.toString(16))
                append('\n')
                append(privKey.privateExponent.toString(16))
            }

            val file = File(context.filesDir, KEY_FILE)
            file.writeText(data)
            Log.d(TAG, "AdbKeyStore: key saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "AdbKeyStore: failed to save key pair", e)
        }
    }

    /**
     * Load the RSA key pair from disk.
     */
    private fun loadKeyPair(context: Context): KeyPair? {
        return try {
            val file = File(context.filesDir, KEY_FILE)
            if (!file.exists()) {
                Log.d(TAG, "AdbKeyStore: no saved key file found")
                return null
            }

            val lines = file.readText().split('\n')
            if (lines.size < 3) {
                Log.w(TAG, "AdbKeyStore: key file corrupted")
                return null
            }

            val modulus = BigInteger(lines[0], 16)
            val publicExponent = BigInteger(lines[1], 16)
            val privateExponent = BigInteger(lines[2], 16)

            val pubKeySpec = RSAPublicKeySpec(modulus, publicExponent)
            val privKeySpec = RSAPrivateKeySpec(modulus, privateExponent)

            val factory = KeyFactory.getInstance("RSA")
            val publicKey = factory.generatePublic(pubKeySpec)
            val privateKey = factory.generatePrivate(privKeySpec)

            KeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            Log.e(TAG, "AdbKeyStore: failed to load key pair", e)
            null
        }
    }

    /**
     * Check if a saved key exists.
     */
    fun hasSavedKey(context: Context): Boolean {
        return File(context.filesDir, KEY_FILE).exists()
    }

    /**
     * Delete the saved key (for debugging/reset).
     */
    fun deleteSavedKey(context: Context) {
        val file = File(context.filesDir, KEY_FILE)
        if (file.exists()) {
            file.delete()
        }
        cachedKeyPair = null
    }
}