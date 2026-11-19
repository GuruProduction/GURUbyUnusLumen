// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.unuslumen.app.domain.security.ProtectionContext
import com.unuslumen.app.domain.security.ProtectedRead
import com.unuslumen.app.domain.security.ProtectedWrite
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The data protector for run records: snapshots, event payloads, checkpoints and the
 * sensitive halves of tool evidence.
 *
 * WHY THIS EXISTS BESIDE CredentialVault RATHER THAN INSIDE IT. The vault seals one
 * credential per registered service, keyed by a service name, with no key version and
 * no metadata slot. That is the right shape for "the GitHub token" and the wrong shape
 * for "the arguments of invocation 3 in run 8 of 12". This class keeps the same
 * hardware-backed primitives and adds the three things a ledger needs: a recorded key
 * version, authenticated context binding, and honest failure classes.
 *
 * WHAT BINDS A VALUE TO ITS PLACE. The cipher authenticates
 * `purpose|ownerId|field|schemaVersion` as associated data. Lift an envelope out of one
 * event and paste it into another and it will not decrypt, because it was sealed for a
 * different place. That is what stops a genuine ciphertext becoming a forged record.
 *
 * KEY VERSIONING, WHICH THE VAULT DOES NOT DO. Every envelope carries the version it
 * was sealed under. Reading an envelope written by a newer build than this one refuses
 * rather than guessing, which is the same rule the database snapshot engine already
 * follows for schema versions.
 *
 * NO PLAINTEXT DUPLICATES. Nothing here writes a clear copy anywhere. A caller that
 * needs a value in the clear holds it for the length of one call and lets it go.
 */
object ProtectedData {

    /**
     * The current envelope format.
     *
     * Bump this when the bytes change shape, and add a reader for the old one. An
     * envelope from a version this build does not know is refused rather than
     * half-parsed, because a half-parsed secret is worse than a missing one.
     */
    const val CURRENT_KEY_VERSION = 1

    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH = 12

    /** Envelope magic, so a value that is not one of ours is recognised as not one of ours. */
    private const val MAGIC = "PD1"

    /** One key for the whole protector, purpose-scoped by the associated data rather than the alias. */
    private const val ALIAS_PREFIX = "guru_protected_v"

    private fun aliasFor(keyVersion: Int): String = "$ALIAS_PREFIX$keyVersion"

    private fun getOrCreateKey(keyVersion: Int): SecretKey {
        val alias = aliasFor(keyVersion)
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        val existing = runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
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
     * Seal one value for one place.
     *
     * The envelope is `PD1:<keyVersion>:<base64 iv+ct>`, and the associated data is
     * rebuilt from the same [context] on the way back in. A caller that reuses the
     * envelope under a different context gets a tamper refusal, not a wrong value.
     */
    fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite {
        return try {
            val key = getOrCreateKey(CURRENT_KEY_VERSION)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(context.associatedData())
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "unexpected GCM IV length ${iv.size}" }
            val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val payload = ByteArray(iv.size + sealed.size)
            iv.copyInto(payload, 0)
            sealed.copyInto(payload, iv.size)

            val encoded = android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)
            ProtectedWrite.Sealed("$MAGIC:$CURRENT_KEY_VERSION:$encoded")
        } catch (e: Exception) {
            // A failure to seal is a fault, never a silent withholding. The caller must
            // not store an empty string in its place and call the value protected.
            ProtectedWrite.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Open one value, telling apart the five ways it can fail to be readable.
     *
     * [Locked] means wait. [Tampered] means stop. [Absent] and [Redacted] mean the
     * session can carry on without it, because it never had it. Collapsing these into
     * a null would make the first two indistinguishable from the last two and would let
     * a run resume against a hole it does not know it has.
     */
    fun open(context: ProtectionContext, envelope: String?): ProtectedRead {
        if (envelope.isNullOrBlank()) {
            return ProtectedRead.Absent("no value was written for this field")
        }
        val parts = envelope.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != MAGIC) {
            return ProtectedRead.Tampered("not a protected envelope")
        }
        val keyVersion = parts[1].toIntOrNull()
            ?: return ProtectedRead.Tampered("envelope names no readable key version")
        if (keyVersion > CURRENT_KEY_VERSION) {
            // Written by a newer build. Refusing beats guessing at a shape this build
            // has never seen, and a wrong guess here is a wrong secret.
            return ProtectedRead.Tampered(
                "sealed under key version $keyVersion, this build knows $CURRENT_KEY_VERSION"
            )
        }
        val payload = runCatching {
            android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return ProtectedRead.Tampered("envelope body is not valid base64")
        if (payload.size <= IV_LENGTH) return ProtectedRead.Tampered("envelope body is too short")

        return try {
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val ciphertext = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(keyVersion),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.updateAAD(context.associatedData())
            val plain = cipher.doFinal(ciphertext)
            ProtectedRead.Value(String(plain, Charsets.UTF_8))
        } catch (e: UserNotAuthenticatedException) {
            // The key exists and the device will not release it yet. Waiting is correct;
            // restoring a session with this field missing is not.
            ProtectedRead.Locked("the vault requires authentication before this can be read")
        } catch (e: AEADBadTagException) {
            // Genuine bytes, wrong place or altered bytes. Never recoverable.
            ProtectedRead.Tampered("the envelope failed authentication for this field")
        } catch (e: Exception) {
            // Anything else is a Keystore fault rather than a proven tamper, and it is
            // reported as locked so a transient problem is not mistaken for corruption.
            ProtectedRead.Locked(e.message ?: e::class.java.simpleName)
        }
    }

    /**
     * Whether a string looks like one of our envelopes.
     *
     * Used by readers that must tell a sealed value from a historical plaintext one
     * written before this facility existed. A legacy row is readable as it always was;
     * it simply is not protected, and the reader says so rather than pretending.
     */
    fun isEnvelope(value: String?): Boolean =
        value != null && value.startsWith("$MAGIC:")

    /** Wipe the protector's keys. Used by a full vault reset, never by ordinary operation. */
    fun destroyKeys() {
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE)
            keyStore.load(null)
            keyStore.aliases().toList().filter { it.startsWith(ALIAS_PREFIX) }.forEach {
                keyStore.deleteEntry(it)
            }
        }
    }
}
