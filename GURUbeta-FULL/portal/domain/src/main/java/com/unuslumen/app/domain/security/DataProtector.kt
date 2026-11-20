// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.security

/**
 * The seam between a ledger that must store protected values and the hardware that
 * protects them.
 *
 * WHY AN INTERFACE RATHER THAN A DIRECT OBJECT. The ledger has to be testable on a JVM,
 * where there is no Android Keystore at all. Without a seam the only options are to skip
 * protection in tests — which is exactly the state this interface removes — or to make the
 * tests run on a device, which puts the most frequently exercised logic behind the slowest
 * possible gate. A seam lets the production path use the real hardware and the test path
 * use a stand-in, and lets a test prove the case that matters most: that a protector which
 * cannot seal makes the write FAIL rather than store plaintext.
 */
interface DataProtector {

    /**
     * Seal a value for one place.
     *
     * A [ProtectedWrite.Failed] is a fault and callers must treat it as one. Storing the
     * plaintext in its place is the defect this contract exists to prevent.
     */
    fun seal(context: ProtectionContext, plaintext: String): ProtectedWrite

    /** Open a stored value, distinguishing absent, redacted, locked and tampered. */
    fun open(context: ProtectionContext, stored: String?): ProtectedRead

    /** Whether a stored string is an envelope this protector wrote. */
    fun isEnvelope(stored: String?): Boolean
}
