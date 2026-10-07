// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain.cerebrum

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CerebrumHost — the app's single handle onto the real Cerebrum brain.
 *
 * The brain is the cdylib `libcerebrum_android.so` built from the cerebrum
 * rust workspace and packs one `CerebrumState`: signature engine, DWM, tiered
 * retrieval, dream engine, event store, graph + entity reconciliation, decay,
 * policy, embeddings, all sitting on the argon2id + XChaCha20-Poly1305 vault.
 * Everything crosses JNI as hex-encoded frame payloads, the same bytes the
 * unix/TCP front doors exchange, so the transport contract has exactly one
 * shape everywhere.
 *
 * Private by design end of: the vault passphrase lives nowhere except
 *  a) sealed in the Android Keystore wrapper envelope on first boot, and
 *  b) inside the brain's RAM holder, dropped with zeroize at brain stop.
 * It is never written in plain to disk, never logged, never exported.
 */
object CerebrumHost {
    private const val TAG = "guru_cerebrum"

    // ---- JNI declarations; symbol surface lives in libcerebrum_android.so ---
    init {
        // cdylib load fires exactly once per app process; the host object is
        // the only entry point into libcerebrum_android.so by convention.
        runCatching {
            System.loadLibrary("cerebrum_android")
            Log.i(TAG, "cerebrum_android.so loaded")
        }.onFailure { throw IllegalStateException("libcerebrum_android.so missing; Cerebrum cannot start", it) }
    }

    private external fun cerebrumStartJNI(dataDir: String, socketPath: String, passphrase: String): Long
    private external fun cerebrumStopJNI()
    private external fun cerebrumSaveNowJNI(): Int
    private external fun cerebrumExecuteJNI(frameType: Int, payloadHex: String): String
    private external fun cerebrumStatusJNI(): String
    private external fun cerebrumLastErrorJNI(): String

    // ---- frame type constants, wire-byte true -------------------------------
    const val FRAME_QUERY = 1             // 0x01
    const val FRAME_CURATE = 2            // 0x02
    const val FRAME_RETRIEVE = 3          // 0x03
    const val FRAME_SEARCH = 4            // 0x04
    const val FRAME_GRAPH_TRAVERSE = 5    // 0x05
    const val FRAME_CONSOLIDATE = 6       // 0x06

    // ---- boot state ---------------------------------------------------------
    private val isBooted = AtomicBoolean(false)

    fun isRunning(): Boolean = isBooted.get()

    /** Boot stamp (unix ms) returned by the brain start or last start attempt; 0 when not running. */
    @Volatile
    var bootStamp: Long = 0
        private set

    // ---- vault passphrase, Keystore-sealed, one wrapper in ------------------

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val CEREBRUM_KS_ALIAS = "guru_cerebrum_vault_pass"
    private const val CEREBRUM_DATA_ROOT = "cerebrum"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    private fun keyStoreWrapperFile(context: Context): File = File(context.filesDir, "cerebrum_key.bin")
    private fun cerebrumDir(context: Context): File = File(context.filesDir, CEREBRUM_DATA_ROOT)
    private fun vaultDataDir(context: Context): File = File(cerebrumDir(context), "state")
    private fun socketPath(context: Context): File = File(cerebrumDir(context), "cerebrum.sock")

    private fun getOrCreateCerebrumKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = runCatching { keyStore.getKey(CEREBRUM_KS_ALIAS, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(CEREBRUM_KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** Seal + drop the vault passphrase to a private-file envelope; Keystore key stays inside the silicon. */
    private fun sealCerebrumPass(context: Context, passphrase: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCerebrumKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected iv length" }
        val sealed = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
        val envelope = ByteArray(iv.size + sealed.size)
        iv.copyInto(envelope, 0)
        sealed.copyInto(envelope, iv.size)
        keyStoreWrapperFile(context).writeBytes(envelope)
        Log.d(TAG, "cerebrum pass envelope sealed")
    }

    /** Unseal; transient only, lives inside this one method's return chain. */
    private fun unsealCerebrumPass(context: Context): String? {
        val f = keyStoreWrapperFile(context)
        if (!f.exists()) return null
        return runCatching {
            val envelope = f.readBytes()
            if (!(envelope.size > IV_LENGTH)) return null
            val iv = envelope.copyOfRange(0, IV_LENGTH)
            val cipherBody = envelope.copyOfRange(IV_LENGTH, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateCerebrumKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBody), Charsets.UTF_8)
        }.getOrNull() ?: run {
            Log.w(TAG, "cerebrum pass envelope: tampered or unavailable")
            return null
        }
    }

    // ---- boot paths ----------------------------------------------------------

    enum class BootStep { RESOLVING_KEY, MIGRATING_ROOM, STARTING_ENGINE, OK }
    data class BootResult(val ok: Boolean, val stamp: Long, val error: String?)

    /** Seal the passphrase exactly once; if none exists yet, one arrives from the caller. */
    fun onFirstRun(context: Context, freshPassphrase: String) {
        cerebrumDir(context).mkdirs()
        if (!keyStoreWrapperFile(context).exists()) {
            require(freshPassphrase.isNotEmpty()) { "first-run cerebrum pass empty refuse" }
            sealCerebrumPass(context, freshPassphrase)
            Log.d(TAG, "cerebrum first-run seal done (pass stored in keystore wrapped only)")
        }
    }

    /** Rotate vault key (re-encrypt everything by save-after-new-seal chain). */
    fun rotateCerebrumKey(context: Context, freshPassphrase: String) {
        sealCerebrumPass(context, freshPassphrase)
        Log.d(TAG, "cerebrum pass rotation sealed")
    }

    /**
     * Boot the brain with a real key-vault self-heal.
     *
     * THE HIDDEN BUG (reported twice, fixed the wrong way once): on an app
     * reinstall the Keystore alias can survive while the sandbox keys file
     * rewrites, or the opposite, so a boot starts against a vault whose
     * encryption key belongs to an older key-era. The JNI never opens a
     * dead-pair vault (correctly), and previously we surfaced that as a
     * permanent boot failure that only adb file surgery could cure.
     *
     * The now-permanent heal (in app code, never shell): a boot whose JNI
     * reports Crypto/AeadOpenFailed treats the install's own brain directory
     * as key-orphaned: wipe exactly those dirs (the cerebrum brain data +
     * envelope; ALL OTHER app data including room, termux, cookies untouched),
     * delete THIS app's own keystore alias, seal a fresh pass, retry the boot
     * once. Room remains the truth source for re-migration (the app-layer
     * migration pass in CerebrumBoot re-runs afterwards on the SAME trigger
     * chain it already has: fresh vaults re-land Room memory tables' data;
     * fresh-install cases land with empty room tables as an empty brain, which
     * is correct because there IS nothing to restore, one store only).
     */
    @Synchronized
    fun boot(
        context: Context,
        freshPassphrase: String = "",
        onStep: ((BootResult) -> Unit)? = null,
    ): BootResult {
        val theFirstAttempt: BootResult = bootOnce(context, freshPassphrase, onStep)
        if (attemptWasKeyOrphanAEAD(theFirstAttempt.error)) {
            Log.w(TAG, "key-vault orphan detected: app-side self-heal (own cerebrum dir only) and re-seal")
            healOrphanedKeyPair(context)
            val healedAttempt: BootResult = bootOnce(context, freshPassphrase, onStep)
            if (healedAttempt.ok) {
                Log.i(TAG, "self-heal boot OK: fresh vault sealed, re-sealed pass, heal completed")
            }
            return healedAttempt
        }
        return theFirstAttempt
    }

    private fun attemptWasKeyOrphanAEAD(bootError: String?): Boolean =
        bootError?.contains("AEAD", ignoreCase = true) == true ||
            bootError?.contains("integrity", ignoreCase = true) == true

    private fun healOrphanedKeyPair(context: Context) {
        runCatching {
            keyStoreWrapperFile(context).delete()
            vaultDataDir(context).deleteRecursively()
            socketPath(context).delete()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val alias = CEREBRUM_KS_ALIAS
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
            Log.w(TAG, "orphaned brain dirs + keystore alias wiped (cerebrum-only paths); fresh pairs land at re-seal")
        }.onFailure { errorText ->
            Log.e(TAG, "self-heal encountered wipe error (continues; new seal will attempt to recover): ${errorText.message}")
        }
    }

    private fun bootOnce(
        context: Context,
        freshPassphrase: String,
        onStep: ((BootResult) -> Unit)?,
    ): BootResult {
        if (isBooted.get()) {
            return BootResult(ok = true, stamp = bootStamp, error = null)
        }
        cerebrumDir(context).mkdirs()
        vaultDataDir(context).mkdirs()

        val pass: String =
            unsealCerebrumPass(context)
                ?: run {
                    if (freshPassphrase.isEmpty()) {
                        Log.e(TAG, "no vault key: sealed pass gone and caller carried no fresh one")
                        val failRes = BootResult(false, 0L, "cerebrum boot failed: pass missing, fail closed")
                        onStep?.invoke(failRes)
                        return failRes
                    }
                    // no envelope: fresh brain. real wipe of any orphan vault
                    // already done by any prior heal path; now seal + go fresh.
                    sealCerebrumPass(context, freshPassphrase)
                    vaultDataDir(context).deleteRecursively()
                    cerebrumDir(context).mkdirs()
                    vaultDataDir(context).mkdirs()
                    freshPassphrase
                }
        cerebrumDir(context).mkdirs()
        vaultDataDir(context).mkdirs()

        bootStamp = cerebrumStartJNI(
            vaultDataDir(context).absolutePath,
            socketPath(context).absolutePath,
            pass
        )
        // The pass string has landed inside the Brain's zeroized RAM holder; nothing local holds.
        if (bootStamp == 0L) {
            val lastBootError = cerebrumLastErrorJNI()
            isBooted.set(false)
            val failRes = BootResult(false, 0L, lastBootError)
            onStep?.invoke(failRes)
            Log.e(TAG, "Cerebrum boot FAILURE: $lastBootError")
            return failRes
        }
        isBooted.set(true)
        val okRes = BootResult(true, bootStamp, null)
        Log.d(TAG, "Cerebrum brain booted (stamp=$bootStamp) hardening+vault loaded")
        onStep?.invoke(okRes)
        return okRes
    }

    fun stop() {
        if (!isBooted.get()) return
        cerebrumStopJNI()
        isBooted.set(false)
        bootStamp = 0
        Log.d(TAG, "Cerebrum brain stopped (SIGTERM path completed)")
    }

    fun saveNow(): Int = if (isBooted.get()) cerebrumSaveNowJNI() else 0

    fun status(): String = if (isBooted.get()) cerebrumStatusJNI() else """{"running":false}"""

    fun lastError(): String = cerebrumLastErrorJNI()

    // ---- frame-dispatch front ends ------------------------------------------

    /** Push one raw frame payload and return the wrapped JSON hex, for the raw-caller use case. */
    fun executeFrame(frameType: Int, payloadHex: String): String? =
        if (isBooted.get()) cerebrumExecuteJNI(frameType, payloadHex) else null

    fun statusJson(): String = status()
}