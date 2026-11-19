// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.security

/**
 * The three ways a stored value can fail to be readable, kept apart on purpose.
 *
 * THE DISTINCTION IS LOAD-BEARING IN RECOVERY. A run whose tool arguments were redacted
 * can still resume, because redaction was a decision made before the write and the
 * session never held the value. A run whose arguments are locked has to wait, because
 * the value exists and the key is simply not available yet. A run whose arguments are
 * tampered must not resume at all, because somebody has altered the record and nothing
 * downstream of that can be trusted. Collapsing all three into "null" would make the
 * first two indistinguishable from the third, and a system that cannot tell a locked
 * record from a tampered one will eventually restore the wrong one.
 */
sealed interface ProtectedRead {
    /** The value, decrypted. */
    data class Value(val plaintext: String) : ProtectedRead

    /**
     * Deliberately not retained.
     *
     * A choice, recorded with its reason. Nothing can recover it, and nothing pretends
     * to try.
     */
    data class Redacted(val reason: String) : ProtectedRead

    /**
     * Retained, but the vault will not open it right now.
     *
     * The value exists and the key is not currently reachable: the device is locked,
     * the Keystore needs authentication, or the vault has not been initialised in this
     * process yet. Recovery waits rather than proceeding with a hole.
     */
    data class Locked(val reason: String) : ProtectedRead

    /**
     * Retained, and the ciphertext will not authenticate.
     *
     * Either the record was altered or the key no longer matches the envelope's
     * recorded version. This is never recoverable and is never a waiting state.
     */
    data class Tampered(val reason: String) : ProtectedRead

    /** Nothing was ever written for this field. */
    data class Absent(val reason: String) : ProtectedRead
}

/**
 * What a protection call produced.
 *
 * A write either sealed a value or deliberately withheld it. There is no third outcome,
 * because an "I tried and it did not work" that silently stored nothing would leave a
 * reader unable to tell a withholding from a bug.
 */
sealed interface ProtectedWrite {
    /** The envelope, ready to store in a TEXT column. */
    data class Sealed(val envelope: String) : ProtectedWrite

    /** Deliberately withheld, with the reason recorded beside it. */
    data class Redacted(val reason: String) : ProtectedWrite

    /**
     * The vault refused to seal it.
     *
     * Distinct from [Redacted]: this is a fault, not a decision, and a caller must not
     * store an empty string in place of a value it failed to protect. The record either
     * carries a real envelope or it does not get written.
     */
    data class Failed(val reason: String) : ProtectedWrite
}

/**
 * What a protected value is bound to.
 *
 * THE ASSOCIATED DATA IS THE POINT. An AES-GCM envelope that authenticates only its own
 * ciphertext can be lifted out of one event and pasted into another, and it will
 * decrypt perfectly because the bytes are genuine. Binding the purpose, the owning
 * record, the field name and the schema version into the authenticated data makes that
 * move fail: the ciphertext is real, but it was sealed for somewhere else.
 *
 * That is the difference between encrypting a value and protecting a record. Encryption
 * keeps it secret; binding keeps it in its place.
 */
data class ProtectionContext(
    /** What this value is for: a tool argument, a tool result, a checkpoint state. */
    val purpose: String,
    /** The row that owns it: a run id, an event id, a checkpoint id. */
    val ownerId: String,
    /** The field within that row. */
    val field: String,
    /** The payload schema version, so an old envelope is never read as a new shape. */
    val schemaVersion: Int,
) {
    /**
     * The bytes the cipher authenticates.
     *
     * Ordered and separator-delimited so two different contexts can never produce the
     * same bytes: without a separator, purpose "ab" with field "c" collides with
     * purpose "a" and field "bc", and a collision here is a value that authenticates in
     * the wrong place.
     */
    fun associatedData(): ByteArray =
        "$purpose\u001F$ownerId\u001F$field\u001F$schemaVersion".toByteArray(Charsets.UTF_8)

    companion object {
        const val PURPOSE_TOOL_ARGUMENT = "tool.argument"
        const val PURPOSE_TOOL_RESULT = "tool.result"
        const val PURPOSE_CHECKPOINT_STATE = "checkpoint.state"
        const val PURPOSE_RUN_SNAPSHOT = "run.snapshot"
        const val PURPOSE_EVENT_PAYLOAD = "event.payload"
    }
}
