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
     * Boot the brain, healing ONLY a genuinely orphaned key pair.
     *
     * The brain directory and the Keystore alias can fall out of step on an
     * app reinstall, so a boot can start against a vault whose key belongs to
     * an older key-era. That is a real condition worth healing automatically,
     * because no app-side path can recover from it.
     *
     * The heal deletes the brain, so the condition that triggers it must be
     * exact. It is: the JNI reports that the vault HEADER itself was rejected,
     * which means the passphrase genuinely does not open this file. A failure
     * to read a RECORD is a different fault entirely, and treating it as an
     * orphan destroyed a live brain. An earlier version matched the substring
     * "AEAD" or "integrity", which are the words used for a record that failed
     * to open, so every mid-vault read failure wiped the memory. That match is
     * gone: `PassphraseRejected` is the only orphan signal.
     *
     * Anything else surfaces as a normal boot failure so the underlying fault
     * can be seen and fixed rather than silently erasing the user's brain.
     */
    @Synchronized
    fun boot(
        context: Context,
        freshPassphrase: String = "",
        onStep: ((BootResult) -> Unit)? = null,
    ): BootResult {
        val theFirstAttempt: BootResult = bootOnce(context, freshPassphrase, onStep)
        if (attemptWasKeyOrphan(theFirstAttempt.error)) {
            Log.w(TAG, "vault header rejected: key-vault orphan, app-side re-seal of cerebrum dirs only")
            healOrphanedKeyPair(context)
            val healedAttempt: BootResult = bootOnce(context, freshPassphrase, onStep)
            if (healedAttempt.ok) {
                Log.i(TAG, "self-heal boot OK: fresh vault sealed, re-sealed pass, heal completed")
            }
            return healedAttempt
        }
        return theFirstAttempt
    }

    /**
     * True only when the vault's own header refused the passphrase.
     *
     * `PassphraseRejected` is raised by the header verifier and by nothing
     * else. Record-level failures report `AeadOpenFailed`, which is NOT an
     * orphan signal and must never trigger the wipe.
     */
    private fun attemptWasKeyOrphan(bootError: String?): Boolean =
        bootError?.contains("PassphraseRejected", ignoreCase = true) == true

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

    /**
     * Whether the vault directory holds anything that could be real brain data.
     *
     * Any regular file with content counts. A directory of empty directories, or
     * an absent directory, does not, and that distinction is what keeps a genuine
     * first run working while making a populated-but-unopenable vault impossible to
     * destroy. Deliberately conservative: a false positive here costs one refused
     * boot that a human has to look at, and a false negative costs the brain.
     */
    private fun vaultHasContent(context: Context): Boolean {
        val dir = vaultDataDir(context)
        if (!dir.exists()) return false
        return runCatching {
            dir.walkTopDown().any { it.isFile && it.length() > 0 }
        }.getOrDefault(true)
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
                    /*
                     * THE DECISION THIS BRANCH USED TO GET WRONG (issue 10).
                     *
                     * Reachable state: the Keystore envelope is gone or will not
                     * unseal, while a vault may or may not exist on disk. Losing the
                     * envelope is not a first run. It happens on an OS upgrade that
                     * invalidates the Keystore alias, a restore from a backup that
                     * carries the vault but not the key, a partial envelope write, or
                     * a wiped app keystore. In every one of those cases the vault is
                     * intact and the passphrase that opens it is what went missing.
                     *
                     * The old code dealt a fresh passphrase over the top and then
                     * called `vaultDataDir.deleteRecursively()`. So a lost envelope
                     * silently destroyed the human's entire brain and started clean,
                     * with nothing said at any layer. That is the "clear it and move
                     * on" failure this issue names as forbidden, and it is the worst
                     * bug in the file because the data cannot be recovered afterwards.
                     *
                     * The rule now: a vault that exists is never deleted to make a boot
                     * succeed. If there is real vault content and no key to open it,
                     * fail closed, leave every byte alone, and say exactly what
                     * happened so a human can decide whether to salvage it or accept
                     * the loss. Deciding that is a product call, not this object's.
                     *
                     * Deletion remains correct in the one case it was meant for: a
                     * genuinely empty vault directory on a true first run, where there
                     * is nothing to lose and a fresh key is the right answer.
                     */
                    if (vaultHasContent(context)) {
                        val orphanDetail =
                            "cerebrum key envelope is missing or unreadable while a vault exists at " +
                                "${vaultDataDir(context).absolutePath}. The vault has been LEFT INTACT and " +
                                "nothing was deleted. The passphrase that opens it is gone, so it cannot be " +
                                "read until it is salvaged or the loss is accepted. Refusing to overwrite it."
                        Log.e(TAG, orphanDetail)
                        val orphanRes = BootResult(false, 0L, orphanDetail)
                        onStep?.invoke(orphanRes)
                        return orphanRes
                    }
                    // Empty vault directory: nothing to lose, so this really is a
                    // first run and a fresh key is correct.
                    sealCerebrumPass(context, freshPassphrase)
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