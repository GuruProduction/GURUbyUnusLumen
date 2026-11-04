// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyPair

/**
 * Persists the RSA key pair used for ADB authentication and pairing.
 *
 * Android's ADB daemon remembers authorized keys, so the key has to survive app
 * restarts. If it is regenerated, the phone does not recognise us and the user is
 * asked to authorise the same app again on every connection.
 *
 * WHY THE FORMAT CHANGED. It used to write three hex lines (modulus, public
 * exponent, private exponent) and rebuild the private half with
 * `RSAPrivateKeySpec(modulus, privateExponent)`. That spec carries no public
 * exponent, so the key reaching TLS had n and d but no e. BoringSSL's
 * certificate-versus-key check compares exactly n and e, so such a key can never
 * match its own certificate and the pairing socket dies with
 * `KEY_VALUES_MISMATCH` before the pairing protocol starts. Issue 306.
 *
 * It now writes base64 PKCS#8 DER, which is a complete key parsed by BoringSSL's
 * own decoder, and the public half is derived from it rather than stored beside
 * it so the two cannot drift apart.
 *
 * The old file is migrated in place rather than discarded. It carries n, e and d,
 * which is enough to recover the prime factors, so the key IDENTITY survives and
 * any authorisation the phone already granted stays valid.
 */
object AdbKeyStore {
    private const val TAG = "guru"

    /** The PKCS#8 file written from now on. */
    private const val KEY_FILE = "adb_key.pk8"

    /** The old three-hex-line file, read once and then retired. */
    private const val LEGACY_KEY_FILE = "adb_key"

    private var cachedKeyPair: KeyPair? = null

    /**
     * The key pair for ADB authentication, loaded, migrated or created.
     *
     * Every branch ends in [AdbKeyMaterial.selfCheck], so a key that cannot sign
     * and verify is replaced rather than handed on. A key that exists but does not
     * work re-prompts forever with no explanation, and replacing it costs one
     * pairing, whereas keeping it costs every connection.
     */
    @Synchronized
    fun getOrCreateKeyPair(context: Context): KeyPair {
        cachedKeyPair?.let { return it }

        val pk8 = File(context.filesDir, KEY_FILE)
        val legacy = File(context.filesDir, LEGACY_KEY_FILE)

        loadFromPkcs8(pk8)?.let { pair ->
            cachedKeyPair = pair
            Log.d(TAG, "AdbKeyStore: loaded existing PKCS#8 key pair")
            if (legacy.exists()) {
                Log.d(TAG, "AdbKeyStore: retiring the superseded legacy key file")
                legacy.delete()
            }
            return pair
        }

        migrateLegacy(legacy)?.let { pair ->
            cachedKeyPair = pair
            return pair
        }

        Log.d(TAG, "AdbKeyStore: generating a new RSA key pair")
        val generated = AdbKeyMaterial.generate()
        AdbKeyMaterial.selfCheck(generated)
        saveKeyPair(pk8, generated)
        cachedKeyPair = generated
        Log.d(TAG, "AdbKeyStore: saved new RSA key pair as PKCS#8")
        return generated
    }

    private fun loadFromPkcs8(file: File): KeyPair? {
        if (!file.exists()) {
            Log.d(TAG, "AdbKeyStore: no PKCS#8 key file found")
            return null
        }
        return try {
            val pair = AdbKeyMaterial.load(file.readText())
            AdbKeyMaterial.selfCheck(pair)
            pair
        } catch (e: Exception) {
            // A file that cannot produce a working pair is worse than no file,
            // because it makes every launch fail identically. Renamed rather than
            // deleted, so a real fault stays diagnosable instead of vanishing.
            Log.e(TAG, "AdbKeyStore: stored PKCS#8 key is unusable, setting it aside", e)
            file.renameTo(File(file.parentFile, "${file.name}.unusable"))
            null
        }
    }

    /**
     * Move the old three-line file onto the new format, keeping the same identity.
     *
     * Returns null when there is nothing to migrate, or when the old file cannot be
     * read, in which case a fresh key is generated. A migration failure is logged
     * with its reason, because a silent fallback to a new key is what makes a
     * device re-prompt on every connect and look like a pairing bug forever.
     */
    private fun migrateLegacy(file: File): KeyPair? {
        if (!file.exists()) return null
        return try {
            val pair = AdbKeyMaterial.fromLegacy(file.readText())
            AdbKeyMaterial.selfCheck(pair)
            saveKeyPair(File(file.parentFile, KEY_FILE), pair)
            file.delete()
            Log.d(TAG, "AdbKeyStore: migrated the legacy key file to PKCS#8, identity preserved")
            pair
        } catch (e: Exception) {
            Log.e(
                TAG,
                "AdbKeyStore: could not migrate the legacy ADB key, generating a new one. " +
                    "The phone will need to authorise this key once.",
                e,
            )
            file.renameTo(File(file.parentFile, "${file.name}.unmigratable"))
            null
        }
    }

    /**
     * Write the private half as base64 PKCS#8 through a temporary file, so a kill
     * mid-write cannot leave a half-written key that fails to parse next launch.
     */
    private fun saveKeyPair(file: File, keyPair: KeyPair) {
        val encoded = AdbKeyMaterial.encode(keyPair.private)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encoded)
        check(temporary.renameTo(file)) { "could not move ${temporary.name} into place" }
        Log.d(TAG, "AdbKeyStore: key saved to ${file.absolutePath}")
    }

    /** Whether any saved key exists, in either the current or the legacy format. */
    fun hasSavedKey(context: Context): Boolean =
        File(context.filesDir, KEY_FILE).exists() || File(context.filesDir, LEGACY_KEY_FILE).exists()

    /**
     * Forget the saved key.
     *
     * The in-memory copy is dropped as well, and the ADB client's own cache with
     * it, because a reset that left a stale pair in memory would keep signing with
     * the key the caller just asked to forget.
     */
    fun deleteSavedKey(context: Context) {
        File(context.filesDir, KEY_FILE).delete()
        File(context.filesDir, LEGACY_KEY_FILE).delete()
        cachedKeyPair = null
        AdbClient.forgetCachedKeyPair()
    }
}
