// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-128-CBC with no padding of its own, which is exactly what a Broadlink packet
 * needs. Issue #187.
 *
 * WHY NOT javax.crypto THROUGHOUT. It IS javax.crypto throughout: this class exists
 * as a thin, named seam rather than as a reimplementation. Two reasons, both real:
 *
 *  - The packet builder and its tests then talk about "the Broadlink cipher" rather
 *    than repeating a transformation string and a mode in six places.
 *  - A missing provider becomes one honest null instead of an exception thrown out
 *    of the middle of packet assembly, where it would be reported as a network
 *    failure.
 *
 * NO PADDING IS ADDED OR REMOVED. The Broadlink payload is already padded to a
 * 16-byte boundary by the caller, and a cipher that silently padded would change
 * the bytes the device checksums.
 */
object AesCbc {

    private const val TRANSFORMATION = "AES/CBC/NoPadding"
    private const val ALGORITHM = "AES"

    /**
     * Encrypt [data], which must already be a multiple of 16 bytes.
     *
     * Returns an empty array when the provider is missing, so a caller can report
     * "this build has no AES provider" rather than a crash.
     */
    fun encrypt(data: ByteArray, key: IntArray, iv: IntArray): ByteArray =
        run(data, key, iv, Cipher.ENCRYPT_MODE)

    /**
     * Decrypt [data], which must already be a multiple of 16 bytes.
     *
     * Returns null on a failure, because a packet that will not decrypt is a fact
     * to report and not an exception to throw out of a UI-thread caller.
     */
    fun decrypt(data: ByteArray, key: IntArray, iv: IntArray): ByteArray? =
        run(data, key, iv, Cipher.DECRYPT_MODE).takeIf { it.isNotEmpty() }

    private fun run(data: ByteArray, key: IntArray, iv: IntArray, mode: Int): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        if (data.size % 16 != 0) return ByteArray(0)
        if (key.size != 16 || iv.size != 16) return ByteArray(0)
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                mode,
                SecretKeySpec(ByteArray(key.size) { key[it].toByte() }, ALGORITHM),
                IvParameterSpec(ByteArray(iv.size) { iv[it].toByte() }),
            )
            cipher.doFinal(data)
        }.getOrDefault(ByteArray(0))
    }
}
